package com.ramsbaby.preply.component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import com.ramsbaby.preply.config.AppProps;
import com.ramsbaby.preply.dto.Money;
import com.ramsbaby.preply.dto.ParsedMail;
import com.ramsbaby.preply.dto.RateEntry;
import com.ramsbaby.preply.port.MailCachePort;

import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class SupabaseMailRepository implements MailCachePort {

    private final AppProps props;
    private final NamedParameterJdbcTemplate jdbc;

    public SupabaseMailRepository(AppProps props, DataSource dataSource) {
        this.props = props;
        this.jdbc = new NamedParameterJdbcTemplate(dataSource);
    }

    public boolean enabled() {
        return props.supabase() != null
                && props.supabase().enabled()
                && props.supabase().table() != null
                && !props.supabase().table().isBlank();
    }

    public int upsert(List<ParsedMail> mails) {
        if (!enabled() || mails == null || mails.isEmpty())
            return 0;
        try {
            String sql = buildUpsertSql();

            List<MapSqlParameterSource> batch = mails.stream()
                    .map(this::toParams)
                    .toList();

            int[] affected = jdbc.batchUpdate(sql, batch.toArray(MapSqlParameterSource[]::new));
            return java.util.Arrays.stream(affected).sum();
        } catch (Exception e) {
            log.warn("Supabase upsert 실패", e);
            return 0;
        }
    }

    public Map<String, Money> findLatestBookingRates() {
        Map<String, Money> out = new HashMap<>();
        if (!enabled())
            return out;
        try {
            String sql = """
                    select student_normalized, amount, currency
                    from (
                        select student_normalized,
                               amount,
                               currency,
                               row_number() over (partition by student_normalized order by received_at desc) as rn
                        from %s
                        where kind = 'booking'
                    ) t
                    where rn = 1
                    """.formatted(props.supabase().table());
            jdbc.query(sql, rs -> {
                String key = rs.getString("student_normalized");
                if (key == null || out.containsKey(key))
                    return;
                BigDecimal amt = rs.getBigDecimal("amount");
                String cur = rs.getString("currency");
                if (amt == null || cur == null)
                    return;
                out.put(key, new Money(amt, cur.toUpperCase(Locale.ROOT)));
            });
        } catch (Exception e) {
            log.warn("Supabase 조회 실패: {}", e.toString());
        }
        return out;
    }

    public List<RateEntry> findTodayCompensations(ZoneId tz) {
        return findCompensations(LocalDate.now(tz), tz);
    }

    @Override
    public List<RateEntry> findCompensations(LocalDate date, ZoneId tz) {
        if (!enabled())
            return List.of();
        try {
            String sql = """
                    select student_normalized, amount, currency, lesson_date
                    from %s
                    where kind = 'cancellation_compensation'
                      and lesson_date = :lesson_date
                    order by received_at desc
                    limit 200
                    """.formatted(props.supabase().table());
            List<RateEntry> rows = jdbc.query(sql, Map.of("lesson_date", date), (rs, i) -> {
                String student = rs.getString("student_normalized");
                BigDecimal amt = rs.getBigDecimal("amount");
                String cur = rs.getString("currency");
                if (student == null || amt == null || cur == null)
                    return null;
                return new RateEntry(student, new Money(amt, cur.toUpperCase(Locale.ROOT)),
                        java.time.ZonedDateTime.now());
            });
            return rows.stream().filter(r -> r != null).toList();
        } catch (Exception e) {
            log.warn("Supabase 취소보상 조회 실패: {}", e.toString());
        }
        return List.of();
    }

    /**
     * 지정된 날짜 이전의 레슨 데이터를 삭제한다.
     * 
     * @param cutoffDate 이 날짜 이전 데이터 삭제
     * @return 삭제된 레코드 수
     */
    public int deleteOlderThan(LocalDate cutoffDate) {
        if (!enabled())
            return 0;
        try {
            String sql = "delete from %s where lesson_date < :cutoff".formatted(props.supabase().table());
            int deleted = jdbc.update(sql, Map.of("cutoff", cutoffDate));
            log.info("{}일 이전 데이터 {}건 삭제", cutoffDate, deleted);
            return deleted;
        } catch (Exception e) {
            log.warn("데이터 삭제 실패: {}", e.toString());
            return 0;
        }
    }

    private MapSqlParameterSource toParams(ParsedMail m) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("message_id", m.messageId());
        p.addValue("student_full_name", m.studentFullName());
        p.addValue("student_normalized", m.studentNormalized());
        p.addValue("amount", m.money().amount());
        p.addValue("currency", m.money().currency());
        p.addValue("received_at", java.sql.Timestamp.from(m.receivedAt().toInstant()));
        p.addValue("subject", m.subject());
        p.addValue("snippet", m.snippet());
        // 구독 메일은 DB에서 'booking'으로 저장한다:
        // (1) 프로덕션 테이블 kind CHECK 제약이 'booking'|'cancellation_compensation'만 허용,
        // (2) findLatestBookingRates가 kind='booking'만 조회하므로 구독 $40 단가가 요약에 반영되려면 booking이어야 함.
        // 구독 메일은 가입 시점(가장 이른 received_at)에 도착하므로, 이후 정식 예약이 received_at 최신순 정렬에서 자연히 우선한다.
        p.addValue("kind", "subscription".equals(m.kind()) ? "booking" : m.kind());
        p.addValue("lesson_date", m.lessonDate());
        return p;
    }

    private String buildUpsertSql() {
        String table = props.supabase().table();
        // H2 2.x (in PostgreSQL mode) and Real PostgreSQL both support ON CONFLICT
        return """
                insert into %s (message_id, student_full_name, student_normalized, amount, currency, received_at, subject, snippet, kind, lesson_date)
                values (:message_id, :student_full_name, :student_normalized, :amount, :currency, :received_at, :subject, :snippet, :kind, :lesson_date)
                on conflict (student_full_name, lesson_date) do update set
                  message_id = excluded.message_id,
                  student_normalized = excluded.student_normalized,
                  amount = excluded.amount,
                  currency = excluded.currency,
                  received_at = excluded.received_at,
                  subject = excluded.subject,
                  snippet = excluded.snippet,
                  kind = excluded.kind
                """
                .formatted(table);
    }

}
