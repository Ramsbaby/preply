package com.ramsbaby.preply.api;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ramsbaby.preply.component.SummaryService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Slf4j
public class ApiController {

    private final SummaryService summaryService;

    @GetMapping("/today")
    public ResponseEntity<?> todaySummary() {
        try {
            var summary = summaryService.buildTodaySummary();
            return ResponseEntity.ok(summaryService.toApiResponse(summary));
        } catch (Exception e) {
            log.error("API /today 실패", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", e.getMessage() != null ? e.getMessage() : "Internal error"));
        }
    }
}
