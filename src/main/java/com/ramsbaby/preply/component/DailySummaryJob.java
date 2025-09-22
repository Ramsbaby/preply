package com.ramsbaby.preply.component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import com.ramsbaby.preply.config.AppProps;
import com.ramsbaby.preply.dto.LessonEvent;
import com.ramsbaby.preply.dto.Money;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class DailySummaryJob {

    private final AppProps props;
    private final PreplyRateCacheLoader rateLoader;
    private final GcalReader gcal;
    private final JavaMailSender mailSender;
    private final FxRateService fx;

    private static record Row(String student, Money money) {
    }

    private static record MatchResult(List<Row> rows, List<String> unknown) {
    }

    private static String fmtAmount(BigDecimal v, String currency) {
        if (v == null)
            return "-";
        java.math.BigDecimal n = (v.compareTo(BigDecimal.ZERO) == 0)
                ? BigDecimal.ZERO
                : v.stripTrailingZeros();

        // 통화별 최대 소수 자리 (KRW 0자리, 그 외 최대 2자리)
        int maxScale = "KRW".equalsIgnoreCase(currency) ? 0 : 2;
        int scale = Math.min(Math.max(n.scale(), 0), maxScale);
        n = n.setScale(scale, java.math.RoundingMode.HALF_UP);

        return n.toPlainString() + " " + currency;
    }

    // 필요시 스케줄링 켜기: @EnableScheduling 추가
    // @Scheduled(cron = "0 5 23 * * *", zone = "Asia/Seoul")
    public void run() {
        generateAndSend();
    }

    // 수동 호출도 가능하게 분리
    public void generateAndSend() {
        Map<String, Money> rateByStudent = rateLoader.loadRates();
        List<LessonEvent> events = gcal.loadTodayPreplyEvents();

        MatchResult match = matchEventsWithRates(events, rateByStudent);
        List<Row> rows = new ArrayList<>(match.rows());
        addTodayCancellationCompensations(rows);

        Map<String, BigDecimal> totals = computeTotalsByCurrency(rows);
        var tz = ZoneId.of(props.gcal().timeZone());
        Set<String> currencies = collectCurrencies(totals);
        BigDecimal krwTotal = computeKrwTotal(totals);

        String body = buildEmailBody(totals, krwTotal, currencies, rows, match.unknown(), tz);
        String subject = "[Preply] 오늘 레슨 요약 (" + LocalDate.now(tz) + ")";
        String[] recipients = resolveRecipients();
        sendEmail(subject, body, recipients);
    }

    private MatchResult matchEventsWithRates(List<LessonEvent> events, Map<String, Money> rateByStudent) {
        List<Row> rows = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        for (LessonEvent e : events) {
            Money rate = rateByStudent.get(e.studentName());
            if (rate == null)
                unknown.add(e.studentName());
            else
                rows.add(new Row(e.studentName(), rate));
        }
        return new MatchResult(rows, unknown);
    }

    private void addTodayCancellationCompensations(List<Row> rows) {
        try {
            var compList = rateLoader.loadTodayCancellationCompensations();
            java.util.Set<String> existing = rows.stream().map(Row::student).collect(Collectors.toSet());
            for (var re : compList) {
                if (!existing.contains(re.studentName())) {
                    rows.add(new Row(re.studentName(), re.money()));
                    existing.add(re.studentName());
                }
            }
        } catch (Exception ignore) {
        }
    }

    private Map<String, BigDecimal> computeTotalsByCurrency(List<Row> rows) {
        return rows.stream().collect(Collectors.groupingBy(
                r -> r.money().currency(),
                Collectors.mapping(r -> r.money().amount(), Collectors.reducing(BigDecimal.ZERO, BigDecimal::add))));
    }

    private Set<String> collectCurrencies(Map<String, BigDecimal> totals) {
        Set<String> currencies = new LinkedHashSet<>();
        currencies.addAll(totals.keySet().stream().map(String::toUpperCase).toList());
        return currencies;
    }

    private BigDecimal computeKrwTotal(Map<String, BigDecimal> totals) {
        return totals.entrySet().stream()
                .map(e -> e.getValue().multiply(fx.krwPer(e.getKey())))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(0, java.math.RoundingMode.HALF_UP);
    }

    private String buildEmailBody(
            Map<String, BigDecimal> totals,
            BigDecimal krwTotal,
            Set<String> currencies,
            List<Row> rows,
            List<String> unknown,
            ZoneId tz) {
        StringBuilder sb = new StringBuilder();
        sb.append("[Preply 오늘 수입 요약]\n");

        if (totals.isEmpty())
            sb.append("- 합계: 없음\n");
        else
            totals.forEach((cur, amt) -> sb.append("- ").append(cur).append(": ")
                    .append(fmtAmount(amt, cur)).append('\n'));

        sb.append("\n[원화 환산 합계]\n");
        sb.append("- ").append(FxRateService.formatKrw(krwTotal)).append('\n');

        sb.append("\n[적용 환율]\n");
        List<String> rateLines = new ArrayList<>();
        for (String cur : currencies) {
            if ("KRW".equalsIgnoreCase(cur))
                continue;
            var snap = fx.snapshot(cur);
            String asOfKst = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(tz)
                    .format(snap.asOf());
            rateLines.add(
                    String.format("- 1 %s = %s KRW (as of %s %s)", cur, fmtAmount(snap.krwPer(), "KRW"), asOfKst, tz));
        }
        if (rateLines.isEmpty())
            sb.append("- 환산 없음(KRW only)\n");
        else
            sb.append(String.join("\n", rateLines)).append('\n');

        sb.append("\n[상세]\n");
        if (rows.isEmpty())
            sb.append("- 없음\n");
        else
            rows.forEach(r -> sb.append("• ").append(r.student()).append(" / ")
                    .append(fmtAmount(r.money().amount(), r.money().currency())).append('\n'));

        if (!unknown.isEmpty()) {
            sb.append("\n[단가 미매칭]\n");
            unknown.stream().sorted().forEach(n -> sb.append("• ").append(n).append('\n'));
        }

        return sb.toString();
    }

    private String[] resolveRecipients() {
        List<String> toList = new ArrayList<>();
        if (props.mail().smtp().to() != null)
            toList.addAll(props.mail().smtp().to());
        toList.add(props.mail().smtp().from());
        return toList.stream().filter(s -> s != null && !s.isBlank()).distinct().toArray(String[]::new);
    }

    private void sendEmail(String subject, String body, String[] recipients) {
        var msg = mailSender.createMimeMessage();
        try {
            var helper = new MimeMessageHelper(msg, false, "UTF-8");
            helper.setFrom(props.mail().smtp().from());
            helper.setTo(recipients);
            helper.setSubject(subject);
            helper.setText(body, false);
            mailSender.send(msg);
        } catch (Exception e) {
            throw new IllegalStateException("요약 메일 발송 실패", e);
        }
    }
}
