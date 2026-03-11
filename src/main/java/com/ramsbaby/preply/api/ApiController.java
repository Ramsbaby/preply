package com.ramsbaby.preply.api;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ramsbaby.preply.component.SummaryService;
import com.ramsbaby.preply.config.AppProps;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.ZoneId;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Slf4j
public class ApiController {

    private final SummaryService summaryService;
    private final AppProps props;

    /**
     * GET /api/today[?date=YYYY-MM-DD]
     * date 파라미터 없으면 오늘, 있으면 해당 날짜 요약 반환.
     */
    @GetMapping("/today")
    public ResponseEntity<?> todaySummary(
            @RequestParam(name = "date", required = false) String dateParam) {
        try {
            LocalDate date;
            if (dateParam != null && !dateParam.isBlank()) {
                try {
                    date = LocalDate.parse(dateParam);
                } catch (DateTimeParseException e) {
                    return ResponseEntity.badRequest()
                            .body(Map.of("error", "Invalid date format. Use YYYY-MM-DD (e.g. 2026-03-10)"));
                }
            } else {
                date = LocalDate.now(ZoneId.of(props.gcal().timeZone()));
            }
            var summary = summaryService.buildSummary(date);
            return ResponseEntity.ok(summaryService.toApiResponse(summary));
        } catch (Exception e) {
            log.error("API /today 실패", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", e.getMessage() != null ? e.getMessage() : "Internal error"));
        }
    }
}
