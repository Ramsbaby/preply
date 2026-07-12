package com.ramsbaby.preply.component;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

class PreplyRateCacheLoaderTest {

    @Test
    void normalize_removesAliasAndSuffix() {
        assertThat(PreplyRateCacheLoader.normalize("Camila S. - Preply lesson"))
                .isEqualTo("camila");
    }

    @Test
    void normalize_trimsAndLowers() {
        assertThat(PreplyRateCacheLoader.normalize("  John   Doe  "))
                .isEqualTo("john doe");
    }

    // --- 취소 보상 메일 레슨 날짜 파싱: 4가지 형식 모두 인식해야 함 (수입 누락 방지) ---

    private static final LocalDate TODAY = LocalDate.of(2026, 7, 13);

    @Test
    void extractLessonDate_recognizesLegacyFormat() {
        // 기존에도 되던 형식 (회귀 방지)
        assertThat(PreplyRateCacheLoader.extractLessonDate("레슨: 7월 13일 월요일", TODAY))
                .isEqualTo(TODAY);
    }

    @Test
    void extractLessonDate_recognizesScheduleFormat() {
        // "일정: M월 D일" — 기존 취소 파서가 놓치던 형식 (진범 후보)
        assertThat(PreplyRateCacheLoader.extractLessonDate("일정: 7월 13일 월요일", TODAY))
                .isEqualTo(TODAY);
    }

    @Test
    void extractLessonDate_recognizesLessonStartFormat() {
        // "레슨 시작: M월 D일" — 기존 취소 파서가 놓치던 형식
        assertThat(PreplyRateCacheLoader.extractLessonDate("레슨 시작: 7월 13일", TODAY))
                .isEqualTo(TODAY);
    }

    @Test
    void extractLessonDate_recognizesEnglishFormat() {
        // 영문 "Lesson time: ... Jul 13" — 기존 취소 파서가 놓치던 형식
        assertThat(PreplyRateCacheLoader.extractLessonDate("Lesson time: Monday, Jul 13, 2026", TODAY))
                .isEqualTo(TODAY);
    }

    @Test
    void extractLessonDate_returnsNullWhenNoDate() {
        assertThat(PreplyRateCacheLoader.extractLessonDate("날짜 정보 없음", TODAY))
                .isNull();
    }
}



