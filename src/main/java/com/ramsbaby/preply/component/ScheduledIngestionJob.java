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

    private static final int LOOKBACK_DAYS = 7; // 매일 최근 7일치만 파싱

    private final MailIngestionService mailIngestionService;

    // 매일 9시 KST, 최근 7일치 메일 파싱하여 Supabase에 upsert
    @Scheduled(cron = "0 0 9 * * *", zone = "Asia/Seoul")
    public void ingestDaily() {
        try {
            mailIngestionService.ingest(LOOKBACK_DAYS);
        } catch (Exception e) {
            log.warn("일간 ingest 실패: {}", e.toString());
        }
    }
}
