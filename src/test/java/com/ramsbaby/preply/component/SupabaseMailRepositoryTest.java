package com.ramsbaby.preply.component;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.ramsbaby.preply.config.AppProps;
import com.ramsbaby.preply.dto.Money;
import com.ramsbaby.preply.dto.ParsedMail;
import com.ramsbaby.preply.dto.RateEntry;

class SupabaseMailRepositoryTest {

    private SupabaseMailRepository repo;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setup() {
        DataSource ds = h2DataSource();
        this.jdbc = new JdbcTemplate(ds);
        this.repo = new SupabaseMailRepository(testProps(), ds);

        jdbc.execute("""
                create table if not exists preply_mail (
                  message_id varchar primary key,
                  student_full_name varchar,
                  student_normalized varchar,
                  amount numeric,
                  currency varchar,
                  received_at timestamp with time zone,
                  subject varchar,
                  snippet varchar,
                  kind varchar,
                  lesson_date date
                );
                """);
        jdbc.execute("truncate table preply_mail");
    }

    @Test
    void upsert_inserts_and_updates_by_message_id() {
        ParsedMail first = mail("msg-1", "alice", "booking", BigDecimal.ONE, LocalDate.now());
        ParsedMail second = mail("msg-1", "alice", "booking", new BigDecimal("2.50"), LocalDate.now());

        repo.upsert(List.of(first));
        repo.upsert(List.of(second));

        Map<String, Object> row = jdbc.queryForMap("select amount, snippet from preply_mail where message_id='msg-1'");
        assertThat((BigDecimal) row.get("AMOUNT")).isEqualByComparingTo("2.50");
    }

    @Test
    void findLatestBookingRates_returns_latest_per_student() {
        repo.upsert(List.of(
                mail("m1", "alice", "booking", new BigDecimal("10"), LocalDate.now().minusDays(1)),
                mail("m2", "alice", "booking", new BigDecimal("11"), LocalDate.now()),
                mail("m3", "bob", "booking", new BigDecimal("20"), LocalDate.now())));

        Map<String, Money> rates = repo.findLatestBookingRates();

        assertThat(rates).hasSize(2);
        assertThat(rates.get("alice").amount()).isEqualByComparingTo("11");
        assertThat(rates.get("bob").amount()).isEqualByComparingTo("20");
    }

    @Test
    void findTodayCompensations_filters_by_today_lesson_date() {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        LocalDate yesterday = today.minusDays(1);

        repo.upsert(List.of(
                mail("c1", "alice", "cancellation_compensation", new BigDecimal("5"), today),
                mail("c2", "bob", "cancellation_compensation", new BigDecimal("7"), yesterday)));

        List<RateEntry> comps = repo.findTodayCompensations(ZoneId.of("Asia/Seoul"));

        assertThat(comps).hasSize(1);
        assertThat(comps.get(0).studentName()).isEqualTo("alice");
        assertThat(comps.get(0).money().amount()).isEqualTo(new BigDecimal("5"));
    }

    private ParsedMail mail(String id, String student, String kind, BigDecimal amount, LocalDate lessonDate) {
        return new ParsedMail(
                id,
                student,
                student,
                new Money(amount, "USD"),
                ZonedDateTime.of(lessonDate, java.time.LocalTime.NOON, ZoneId.of("Asia/Seoul")),
                "subj",
                "snippet",
                kind,
                kind.equals("cancellation_compensation") ? lessonDate : null);
    }

    private static AppProps testProps() {
        return new AppProps(
                new AppProps.Mail("", "", new AppProps.Mail.Imap("", 0), new AppProps.Mail.Smtp("", 0, "", List.of())),
                new AppProps.Gcal("", "", "", "", 0),
                new AppProps.Supabase("", "", "preply_mail", true),
                new AppProps.Run(""),
                false);
    }

    private static DataSource h2DataSource() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL(
                "jdbc:h2:mem:preply;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        ds.setPassword("");
        return ds;
    }
}
