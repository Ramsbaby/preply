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
        void upsert_inserts_and_updates_by_composite_key() {
                LocalDate today = LocalDate.now();
                LocalDate tomorrow = today.plusDays(1);

                // 동일 학생, 다른 lesson_date → 별도 레코드로 저장
                ParsedMail first = mail("msg-1", "alice", "booking", BigDecimal.ONE, today);
                ParsedMail second = mail("msg-2", "alice", "booking", BigDecimal.ONE, tomorrow);

                repo.upsert(List.of(first));
                repo.upsert(List.of(second));

                // 2개의 별도 레코드가 있어야 함 (날짜가 다르므로)
                int count = jdbc.queryForObject("select count(*) from preply_mail where student_full_name='alice'",
                                Integer.class);
                assertThat(count).isEqualTo(2);

                // 동일 학생 + 동일 lesson_date로 upsert하면 업데이트됨
                ParsedMail updated = new ParsedMail(
                                "msg-1-updated",
                                "alice",
                                "alice",
                                new Money(new BigDecimal("2.50"), "USD"), // 금액 변경
                                first.receivedAt(),
                                "updated subject",
                                "updated snippet",
                                "booking",
                                today); // 동일한 lesson_date
                repo.upsert(List.of(updated));

                // 여전히 2개여야 함 (오늘 날짜 레코드가 업데이트됨)
                count = jdbc.queryForObject("select count(*) from preply_mail where student_full_name='alice'",
                                Integer.class);
                assertThat(count).isEqualTo(2);

                // message_id가 업데이트되었는지 확인 (lesson_date 기준으로 조회)
                String messageId = jdbc.queryForObject(
                                "select message_id from preply_mail where student_full_name='alice' and lesson_date=?",
                                String.class,
                                java.sql.Date.valueOf(today));
                assertThat(messageId).isEqualTo("msg-1-updated");
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
                                lessonDate); // 항상 lessonDate 전달 (NOT NULL)
        }

        private static AppProps testProps() {
                return new AppProps(
                                new AppProps.Mail("", "", new AppProps.Mail.Imap("", 0),
                                                new AppProps.Mail.Smtp("", 0, "", List.of())),
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
