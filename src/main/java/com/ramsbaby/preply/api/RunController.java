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
        return ResponseEntity.ok("Ingested: " + result.bookingCount() + " bookings, "
                + result.compensationCount() + " compensations");
    }
}