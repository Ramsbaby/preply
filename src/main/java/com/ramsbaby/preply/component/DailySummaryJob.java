package com.ramsbaby.preply.component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import com.ramsbaby.preply.config.AppProps;
import com.ramsbaby.preply.dto.Money;
import com.ramsbaby.preply.port.ExchangeRatePort;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Component
@RequiredArgsConstructor
@Slf4j
public class DailySummaryJob {

    private final AppProps props;
    private final SummaryService summaryService;
    private final JavaMailSender mailSender;
    private final ExchangeRatePort fx;

    public record Row(String student, Money money) {}

    private static String fmtAmount(BigDecimal v, String currency) {
        if (v == null) return "-";
        BigDecimal n = (v.compareTo(BigDecimal.ZERO) == 0)
                ? BigDecimal.ZERO
                : v.stripTrailingZeros();
        int maxScale = "KRW".equalsIgnoreCase(currency) ? 0 : 2;
        int scale = Math.min(Math.max(n.scale(), 0), maxScale);
        n = n.setScale(scale, RoundingMode.HALF_UP);
        return n.toPlainString() + " " + currency;
    }

    public void run() {
        generateAndSend();
    }

    public void generateAndSend() {
        var summary = summaryService.buildTodaySummary();
        List<Row> rows = summaryService.toRows(summary);
        Map<String, BigDecimal> totals = summary.totalsByCurrency();
        var tz = ZoneId.of(props.gcal().timeZone());

        Set<String> currencies = summaryService.collectCurrencies(totals);
        Map<String, FxRateService.Snapshot> rateSnapshots = new HashMap<>();
        Map<String, String> rateErrors = new HashMap<>();
        currencies.forEach(cur -> {
            if ("KRW".equalsIgnoreCase(cur)) return;
            try {
                rateSnapshots.put(cur, fx.snapshot(cur));
            } catch (Exception e) {
                rateErrors.put(cur, e.getMessage() != null ? e.getMessage() : "unknown error");
            }
        });
        BigDecimal krwTotal = computeKrwTotal(totals, rateSnapshots, rateErrors);

        String body = buildEmailBody(totals, krwTotal, currencies, rows,
                summary.unmatched(), tz, rateSnapshots, rateErrors);
        String subject = "[Preply] 오늘 레슨 요약 (" + LocalDate.now(tz) + ")";
        String[] recipients = resolveRecipients();
        sendEmail(subject, body, recipients);
    }

    private BigDecimal computeKrwTotal(Map<String, BigDecimal> totals,
            Map<String, FxRateService.Snapshot> rateSnapshots,
            Map<String, String> rateErrors) {
        BigDecimal acc = BigDecimal.ZERO;
        for (var e : totals.entrySet()) {
            String cur = e.getKey();
            BigDecimal amt = e.getValue();
            if ("KRW".equalsIgnoreCase(cur)) {
                acc = acc.add(amt);
                continue;
            }
            var snap = rateSnapshots.get(cur);
            if (snap == null) continue;
            acc = acc.add(amt.multiply(snap.krwPer()));
        }
        return acc.setScale(0, RoundingMode.HALF_UP);
    }

    private String buildEmailBody(
            Map<String, BigDecimal> totals,
            BigDecimal krwTotal,
            Set<String> currencies,
            List<Row> rows,
            List<String> unknown,
            ZoneId tz,
            Map<String, FxRateService.Snapshot> rateSnapshots,
            Map<String, String> rateErrors) {
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
            if ("KRW".equalsIgnoreCase(cur)) continue;
            if (rateErrors.containsKey(cur)) {
                rateLines.add(String.format("- %s: 조회 실패 (%s)", cur, rateErrors.get(cur)));
                continue;
            }
            var snap = rateSnapshots.get(cur);
            if (snap == null) {
                rateLines.add(String.format("- %s: 조회 실패 (unknown)", cur));
                continue;
            }
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
