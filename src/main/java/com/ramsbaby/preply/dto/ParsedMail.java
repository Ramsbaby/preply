package com.ramsbaby.preply.dto;

import java.time.LocalDate;
import java.time.ZonedDateTime;

/**
 * IMAP 메일 파싱 결과를 Supabase에 저장하기 위한 DTO.
 */
public record ParsedMail(
        String messageId,
        String studentFullName,
        String studentNormalized,
        Money money,
        ZonedDateTime receivedAt,
        String subject,
        String snippet,
        String kind,          // booking | cancellation_compensation
        LocalDate lessonDate   // 취소 보상 메일에 포함된 레슨 날짜(없으면 null)
) {
}

