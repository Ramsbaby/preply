package com.ramsbaby.preply.scenario;

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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.ramsbaby.preply.component.MailIngestionService;
import com.ramsbaby.preply.component.PreplyRateCacheLoader;
import com.ramsbaby.preply.component.SupabaseMailRepository;
import com.ramsbaby.preply.config.AppProps;
import com.ramsbaby.preply.dto.Money;
import com.ramsbaby.preply.dto.ParsedMail;

/**
 * TDD: 전체 시스템 흐름을 검증하는 통합 시나리오 테스트.
 * - 배포 직후 초기 데이터 적재
 * - 매일 스케줄러 실행 시 데이터 갱신
 * - 오래된 데이터 삭제 로직
 */
class EndToEndScenarioTest {

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

        // H2 DB 스키마 초기화 (실제 운영 DB와 동일한 구조)
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
    @DisplayName("시나리오: 배포 후 초기 적재 → 매일 갱신 → 데이터 정리")
    void full_lifecycle_scenario() {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));

        // --- 1. 배포 직후: 180일치 Ingest (Startup) ---
        // 상황: 과거 3개월 전 예약(old)과 다음 달 예약(future)이 메일함에 있음
        ParsedMail oldMail = mail("old-1", "Alice", "booking", "10", today.minusMonths(3));
        ParsedMail futureMail = mail("future-1", "Bob", "booking", "20", today.plusMonths(1));

        loader.setBookings(List.of(oldMail, futureMail)); // 메일함 상태 설정

        // 실행: Startup Ingest (180일)
        var result1 = service.ingest(180);

        // 검증: 2건 저장됨
        assertThat(result1.bookingCount()).isEqualTo(2);
        assertThat(countRows()).isEqualTo(2);

        // 검증: 데이터가 올바르게 들어갔는지
        Map<String, Money> rates = supabase.findLatestBookingRates();
        assertThat(rates.get("Alice").amount()).isEqualByComparingTo("10");
        assertThat(rates.get("Bob").amount()).isEqualByComparingTo("20");

        // --- 2. 매일 스케줄러 실행: 7일치 Ingest (Daily) ---
        // 상황: Charlie가 내일 레슨을 새로 예약함 (최근 메일)
        // Alice는 그대로 유지되어야 함 (최근 7일 내 메일이 아니더라도 DB에는 남아있어야 함)
        ParsedMail newMail = mail("new-1", "Charlie", "booking", "30", today.plusDays(1));

        // Loader는 최근 7일치 요청 시 newMail만 반환한다고 가정 (IMAP 동작 모사)
        loader.setBookings(List.of(newMail));

        // 실행: Daily Ingest (7일)
        var result2 = service.ingest(7);

        // 검증: 1건 추가되어 총 3건
        assertThat(result2.bookingCount()).isEqualTo(1);
        assertThat(countRows()).isEqualTo(3);

        // Charlie 데이터 확인
        rates = supabase.findLatestBookingRates();
        assertThat(rates.get("Charlie").amount()).isEqualByComparingTo("30");
        // Alice 데이터도 여전히 존재해야 함 (삭제되지 않음)
        assertThat(rates.get("Alice")).isNotNull();

        // --- 3. 데이터 정리: 6개월 이전 데이터 삭제 (Cleanup) ---
        // 상황: Alice의 레슨 날짜(3개월 전)보다 더 이후인 '1개월 전'을 기준으로 삭제 시도
        // 즉, Alice 데이터는 삭제되어야 함. (Charlie와 Bob은 유지)

        LocalDate cutoffDate = today.minusMonths(1); // 1개월 이전 데이터 삭제

        // 실행
        int deletedCount = supabase.deleteOlderThan(cutoffDate);

        // 검증: Alice 데이터 1건 삭제됨
        assertThat(deletedCount).isEqualTo(1);
        assertThat(countRows()).isEqualTo(2); // Bob, Charlie 남음

        // 최종 데이터 확인
        rates = supabase.findLatestBookingRates();
        assertThat(rates.get("Alice")).isNull(); // 삭제됨
        assertThat(rates.get("Bob")).isNotNull(); // 유지
        assertThat(rates.get("Charlie")).isNotNull(); // 유지
    }

    // --- Helpers ---

    private int countRows() {
        return jdbc.queryForObject("select count(*) from preply_mail", Integer.class);
    }

    private ParsedMail mail(String id, String student, String kind, String amount, LocalDate lessonDate) {
        return new ParsedMail(
                id,
                student,
                student,
                new Money(new BigDecimal(amount), "USD"),
                ZonedDateTime.now(),
                "subject",
                "snippet",
                kind,
                lessonDate);
    }

    private DataSource h2DataSource() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL(
                "jdbc:h2:mem:preply-scenario;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        ds.setPassword("");
        return ds;
    }

    private static AppProps testProps() {
        return new AppProps(
                new AppProps.Mail("", "", new AppProps.Mail.Imap("", 0),
                        new AppProps.Mail.Smtp("", 0, "", List.of())),
                new AppProps.Gcal("", "", "Asia/Seoul", "", 7),
                new AppProps.Supabase("", "", "preply_mail", true),
                new AppProps.Run(""),
                false);
    }

    // Stub Loader
    private static class StubLoader extends PreplyRateCacheLoader {
        private List<ParsedMail> bookings = List.of();

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
            return List.of();
        }
    }
}
