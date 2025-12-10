package com.ramsbaby.preply.component;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Component
@ConditionalOnProperty(prefix = "app.scheduler", name = "enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class ScheduledIngestionJob {

    private static final int LOOKBACK_DAYS = 180;

    private final MailIngestionService mailIngestionService;

    // 주 1회 월요일 15시 KST, Supabase에 6개월치 캐시 upsert
    @Scheduled(cron = "0 0 15 ? * MON", zone = "Asia/Seoul")
    public void ingestWeekly() {
        try {
            mailIngestionService.ingest(LOOKBACK_DAYS);
        } catch (Exception e) {
            log.warn("주간 ingest 실패: {}", e.toString());
        }
    }
}
