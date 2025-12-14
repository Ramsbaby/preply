package com.ramsbaby.preply.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ramsbaby.preply.component.DailySummaryJob;
import com.ramsbaby.preply.component.MailIngestionService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/run")
@RequiredArgsConstructor
public class RunController {
    private final DailySummaryJob job;
    private final MailIngestionService ingestionService;

    @GetMapping
    public ResponseEntity<String> run() {
        job.generateAndSend();
        return ResponseEntity.ok("OK");
    }

    /**
     * 수동 ingest 엔드포인트.
     * 첫 배포 시 /run/ingest?days=180 호출하여 6개월치 메일 파싱.
     */
    @GetMapping("/ingest")
    public ResponseEntity<String> ingest(@RequestParam(defaultValue = "180") int days) {
        var result = ingestionService.ingest(days);
        return ResponseEntity.ok(formatResult("Manual ingest(" + days + " days)", result));
    }

    /**
     * 매일 아침 7일치 ingest (GCP Cloud Scheduler 호출용).
     * 매일 09:00 KST에 호출하도록 Cloud Scheduler 설정.
     */
    @GetMapping("/ingest/daily")
    public ResponseEntity<String> ingestDaily() {
        var result = ingestionService.ingest(7);
        return ResponseEntity.ok(formatResult("Daily ingest", result));
    }

    /**
     * 주 1회 6개월치 ingest (GCP Cloud Scheduler 호출용).
     * 매주 월요일 15:00 KST에 호출하도록 Cloud Scheduler 설정.
     */
    @GetMapping("/ingest/weekly")
    public ResponseEntity<String> ingestWeekly() {
        var result = ingestionService.ingest(180);
        return ResponseEntity.ok(formatResult("Weekly ingest", result));
    }

    private String formatResult(String title, com.ramsbaby.preply.component.MailIngestionService.IngestResult r) {
        return String.format("[%s] Total scanned: %d, Saved: %d (Bookings: %d, Compensations: %d)",
                title, r.totalReadCount(), r.savedCount(), r.bookingCount(), r.compensationCount());
    }
}