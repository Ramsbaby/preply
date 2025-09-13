package com.ramsbaby.preply.dto;

import java.time.ZonedDateTime;

public record RateEntry(
        String studentName,            // 메일에서 추출
        Money money,                   // 단가
        ZonedDateTime receivedAt       // 메일 수신 시각
) {
}