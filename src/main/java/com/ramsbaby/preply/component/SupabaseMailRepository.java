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

import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class SupabaseMailRepository {

    private final AppProps props;
    private final NamedParameterJdbcTemplate jdbc;
    private final boolean isH2;

    public SupabaseMailRepository(AppProps props, DataSource dataSource) {
        this.props = props;
        this.jdbc = new NamedParameterJdbcTemplate(dataSource);
        this.isH2 = detectH2(dataSource);
    }

    public boolean enabled() {
        return props.supabase() != null && props.supabase().table() != null && !props.supabase().table().isBlank();
    }

    public void upsert(List<ParsedMail> mails) {
        if (!enabled() || mails == null || mails.isEmpty())
            return;
        try {
            String sql = buildUpsertSql();

            List<MapSqlParameterSource> batch = mails.stream()
                    .map(this::toParams)
                    .toList();

            jdbc.batchUpdate(sql, batch.toArray(MapSqlParameterSource[]::new));
        } catch (Exception e) {
            log.warn("Supabase upsert 실패: {}", e.toString());
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
        if (!enabled())
            return List.of();
        LocalDate today = LocalDate.now(tz);
        try {
            String sql = """
                    select student_normalized, amount, currency, lesson_date
                    from %s
                    where kind = 'cancellation_compensation'
                      and lesson_date = :lesson_date
                    order by received_at desc
                    limit 200
                    """.formatted(props.supabase().table());
            List<RateEntry> rows = jdbc.query(sql, Map.of("lesson_date", today), (rs, i) -> {
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
        p.addValue("kind", m.kind());
        p.addValue("lesson_date", m.lessonDate());
        return p;
    }

    private String buildUpsertSql() {
        String table = props.supabase().table();
        if (isH2) {
            // H2(PostgreSQL 모드)에서 ON CONFLICT 동작 제약을 피하기 위해 MERGE 사용
            return """
                    merge into %s (message_id, student_full_name, student_normalized, amount, currency, received_at, subject, snippet, kind, lesson_date)
                    key(message_id)
                    values (:message_id, :student_full_name, :student_normalized, :amount, :currency, :received_at, :subject, :snippet, :kind, :lesson_date)
                    """
                    .formatted(table);
        }
        return """
                insert into %s (message_id, student_full_name, student_normalized, amount, currency, received_at, subject, snippet, kind, lesson_date)
                values (:message_id, :student_full_name, :student_normalized, :amount, :currency, :received_at, :subject, :snippet, :kind, :lesson_date)
                on conflict (message_id) do update set
                  student_full_name = excluded.student_full_name,
                  student_normalized = excluded.student_normalized,
                  amount = excluded.amount,
                  currency = excluded.currency,
                  received_at = excluded.received_at,
                  subject = excluded.subject,
                  snippet = excluded.snippet,
                  kind = excluded.kind,
                  lesson_date = excluded.lesson_date
                """
                .formatted(table);
    }

    private static boolean detectH2(DataSource ds) {
        try (var conn = ds.getConnection()) {
            String name = conn.getMetaData().getDatabaseProductName();
            return name != null && name.toLowerCase(Locale.ROOT).contains("h2");
        } catch (Exception e) {
            return false;
        }
    }
}
