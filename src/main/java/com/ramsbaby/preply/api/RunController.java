package com.ramsbaby.preply.api;

import com.ramsbaby.preply.component.DailySummaryJob;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/run")
@RequiredArgsConstructor
public class RunController {
    private final DailySummaryJob job;

    @GetMapping
    public ResponseEntity<String> run() {
        job.generateAndSend();
        return ResponseEntity.ok("OK");
    }
}