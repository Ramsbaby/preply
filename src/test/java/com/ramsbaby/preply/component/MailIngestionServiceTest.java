package com.ramsbaby.preply.component;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import javax.sql.DataSource;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.ramsbaby.preply.config.AppProps;
import com.ramsbaby.preply.dto.Money;
import com.ramsbaby.preply.dto.ParsedMail;

class MailIngestionServiceTest {

    private JdbcTemplate jdbc;
    private SupabaseMailRepository supabase;
    private StubLoader loader;
    private MailIngestionService service;

    @BeforeEach
    void setup() {
        DataSource ds = h2DataSource();
        this.jdbc = new JdbcTemplate(ds);
        this.supabase = new SupabaseMailRepository(testProps(), ds);
        this.loader = new StubLoader(testProps());
        this.service = new MailIngestionService(loader, supabase);

        jdbc.execute("""
                create table if not exists preply_mail (
                  message_id varchar,
                  student_full_name varchar not null,
                  student_normalized varchar,
                  amount numeric not null,
                  currency varchar,
                  received_at timestamp with time zone not null,
                  subject varchar,
                  snippet varchar,
                  kind varchar,
                  lesson_date date not null,
                  primary key (student_full_name, lesson_date)
                );
                """);
        jdbc.execute("truncate table preply_mail");
    }

    @Test
    void ingestYear_inserts_bookings_into_db() {
        loader.setBookings(List.of(
                mail("m1", "alice", "booking", "11"),
                mail("m2", "bob", "booking", "20")));

        MailIngestionService.IngestResult result = service.ingestYear();

        List<java.util.Map<String, Object>> rows = jdbc.queryForList(
                "select student_full_name, amount, currency from preply_mail order by student_full_name");

        assertThat(result.persisted()).isTrue();
        assertThat(result.bookingCount()).isEqualTo(2);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get("STUDENT_FULL_NAME")).isEqualTo("alice");
        assertThat((BigDecimal) rows.get(0).get("AMOUNT")).isEqualByComparingTo("11");
        assertThat(rows.get(1).get("STUDENT_FULL_NAME")).isEqualTo("bob");
        assertThat((BigDecimal) rows.get(1).get("AMOUNT")).isEqualByComparingTo("20");
    }

    private ParsedMail mail(String id, String student, String kind, String amount) {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        return new ParsedMail(
                id,
                student,
                student,
                new Money(new BigDecimal(amount), "USD"),
                ZonedDateTime.of(today, java.time.LocalTime.NOON, ZoneId.of("Asia/Seoul")),
                "subject",
                "snippet",
                kind,
                today); // lesson_date는 NOT NULL
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

    // 테스트 전용 로더: IMAP 대신 준비된 데이터를 반환
    private static class StubLoader extends PreplyRateCacheLoader {
        private List<ParsedMail> bookings = List.of();
        private List<ParsedMail> comps = List.of();

        StubLoader(AppProps props) {
            super(props);
        }

        void setBookings(List<ParsedMail> mails) {
            this.bookings = mails;
        }

        @Override
        public List<ParsedMail> fetchBookings(int lookBackDays) {
            return bookings;
        }

        @Override
        public List<ParsedMail> fetchCancellationCompensations(int lookBackDays, LocalDate asOf) {
            return comps;
        }
    }
}
