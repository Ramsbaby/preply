package com.ramsbaby.preply.dto;

import java.time.ZonedDateTime;

public record LessonEvent(
        String studentName,            // 캘린더에서 추출
        ZonedDateTime startAt          // 캘린더 시작 시각(KST)
) {
}