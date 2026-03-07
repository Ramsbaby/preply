package com.ramsbaby.preply.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public record TodaySummaryResponse(
        LocalDate date,
        List<LessonDetail> scheduledLessons,
        List<LessonDetail> cancelledCompensations,
        List<String> unmatchedStudents,
        Map<String, BigDecimal> totalsByCurrency,
        int scheduledCount,
        int cancelledCount
) {
    public record LessonDetail(
            String student,
            BigDecimal amount,
            String currency,
            String startAt
    ) {}
}
