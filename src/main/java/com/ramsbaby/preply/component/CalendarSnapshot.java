package com.ramsbaby.preply.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.GoogleCredentials;
import com.ramsbaby.preply.dto.TodaySummaryResponse.LessonDetail;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.*;

@Component
public class CalendarSnapshot {
    private final String bucket;
    private final ObjectMapper json;
    private final BigDecimal payoutShare;
    private final LocalDate firstSnapshot;
    private final NetHttpTransport http = new NetHttpTransport();
    private GoogleCredentials credentials;

    public CalendarSnapshot(@Value("${APP_LESSON_BUCKET:}") String bucket, ObjectMapper json,
                            @Value("${APP_TUTOR_PAYOUT_SHARE:0.82}") BigDecimal payoutShare,
                            @Value("${APP_LESSON_FIRST_DATE:1970-01-01}") String firstSnapshot) {
        this.bucket = bucket;
        this.json = json;
        this.payoutShare = payoutShare;
        this.firstSnapshot = LocalDate.parse(firstSnapshot);
    }

    public boolean enabled() { return !bucket.isBlank(); }

    boolean covers(LocalDate date) { return enabled() && !date.isBefore(firstSnapshot); }

    record Lesson(String id, Instant start, Instant end, String student, String status,
                  BigDecimal price, BigDecimal duration, BigDecimal earned, boolean trial) {}
    record Snapshot(LocalDate date, Instant capturedAt, List<Lesson> lessons) {}

    private synchronized GoogleCredentials credentials() throws IOException {
        if (credentials == null) {
            credentials = GoogleCredentials.getApplicationDefault()
                    .createScoped("https://www.googleapis.com/auth/devstorage.read_only");
        }
        return credentials;
    }

    Snapshot read(LocalDate date) {
        try {
            var url = new GenericUrl("https://storage.googleapis.com/storage/v1/b/" + bucket
                    + "/o/" + date + ".json?alt=media");
            var req = http.createRequestFactory(new HttpCredentialsAdapter(credentials())).buildGetRequest(url);
            req.setConnectTimeout(5000);
            req.setReadTimeout(10000);
            var response = req.execute();
            try { return json.readValue(response.getContent(), Snapshot.class); }
            finally { response.disconnect(); }
        } catch (IOException e) {
            throw new IllegalStateException("Preply 일정 조회본을 읽을 수 없습니다: " + date, e);
        }
    }

    SummaryService.TodaySummary summary(LocalDate date, ZoneId zone, boolean forDelivery) {
        Instant now = Instant.now();
        var snapshot = read(date);
        // The Mac collector and Cloud Scheduler start at the same minute.
        if (forDelivery && date.atTime(22, 0).atZone(zone).toInstant().isBefore(now)) {
            var cutoff = date.atTime(22, 0).atZone(zone).toInstant();
            for (int i = 0; snapshot.capturedAt().isBefore(cutoff) && i < 35; i++) {
                try { Thread.sleep(3000); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                snapshot = read(date);
            }
            if (snapshot.capturedAt().isBefore(cutoff))
                throw new IllegalStateException("22시 이후 Preply 일정 갱신 대기 중");
        }
        if (!date.equals(snapshot.date()) || snapshot.capturedAt().isAfter(Instant.now().plusSeconds(60)))
            throw new IllegalStateException("일정 조회본 날짜가 맞지 않습니다");
        if (forDelivery && snapshot.capturedAt().isBefore(Instant.now().minusSeconds(600)))
            throw new IllegalStateException("최신 일정이 없어 메일 발송을 중단합니다");
        return calculate(snapshot, zone);
    }

    SummaryService.TodaySummary calculate(Snapshot snapshot, ZoneId zone) {
        List<LessonDetail> scheduled = new ArrayList<>(), paidCancellations = new ArrayList<>(), upcoming = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        BigDecimal total = BigDecimal.ZERO;
        for (Lesson lesson : snapshot.lessons()) {
            if (lesson.id() == null || !seen.add(lesson.id()) || !snapshot.date().equals(lesson.start().atZone(zone).toLocalDate())
                    || !lesson.end().isAfter(lesson.start())) throw new IllegalStateException("Invalid Preply lesson identity/time");
            boolean compensated = "WASTED".equals(lesson.status());
            boolean pending = "BOOKED".equals(lesson.status());
            boolean notEnded = lesson.end().isAfter(snapshot.capturedAt());
            BigDecimal amount = lesson.earned();
            if (amount == null || amount.signum() < 0) throw new IllegalStateException("Missing Preply earnings");
            switch (lesson.status()) {
                case "CANCELLED", "CANCELED", "RESCHEDULED" -> { if (amount.signum()==0) continue; compensated=true; }
                case "COMPLETED", "AUTOCOMPLETED", "WASTED", "BOOKED" -> {}
                default -> throw new IllegalStateException("Unrecognized Preply lesson status: " + lesson.status());
            }
            if (pending && !lesson.trial() && amount.signum()==0) {
                if (lesson.price()==null || lesson.price().signum()<=0 || lesson.duration()==null || lesson.duration().signum()<=0)
                    throw new IllegalStateException("Missing booked lesson rate or duration");
                amount = lesson.price().multiply(lesson.duration()).multiply(payoutShare).setScale(2, RoundingMode.HALF_UP);
            }
            if (compensated && amount.signum()==0) continue;
            String start = lesson.start().atZone(zone).toLocalTime().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"));
            var detail = new LessonDetail(lesson.student(), amount, "USD", start);
            if (notEnded) { upcoming.add(detail); continue; }
            (compensated ? paidCancellations : scheduled).add(detail);
            total = total.add(amount);
        }
        return new SummaryService.TodaySummary(snapshot.date(), scheduled, paidCancellations, List.of(),
                Map.of("USD", total), upcoming, snapshot.capturedAt());
    }
}
