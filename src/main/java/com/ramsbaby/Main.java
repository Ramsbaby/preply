package com.ramsbaby;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.CompletableFuture;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.event.EventListener;

import com.ramsbaby.preply.component.DailySummaryJob;
import com.ramsbaby.preply.component.MailIngestionService;
import com.ramsbaby.preply.component.SupabaseMailRepository;
import com.ramsbaby.preply.config.AppProps;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@SpringBootApplication
@ConfigurationPropertiesScan
@RequiredArgsConstructor
@Slf4j
public class Main {
    private final DailySummaryJob job;
    private final AppProps props;
    private final MailIngestionService ingestionService;
    private final SupabaseMailRepository supabase;

    private static final int INGEST_DAYS = 180; // 6개월

    public static void main(String[] args) {
        SpringApplication.run(Main.class, args);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initAsync() {
        // 시작 시 6개월치 ingest + 오래된 데이터 삭제 (비동기)
        CompletableFuture.runAsync(() -> {
            try {
                // 1. 6개월 이전 데이터 삭제
                LocalDate cutoff = LocalDate.now(ZoneId.of("Asia/Seoul")).minusDays(INGEST_DAYS);
                supabase.deleteOlderThan(cutoff);

                // 2. 6개월치 메일 파싱 및 저장
                var result = ingestionService.ingest(INGEST_DAYS);
                log.info("Startup ingest 완료: {} bookings, {} compensations",
                        result.bookingCount(), result.compensationCount());
            } catch (Exception e) {
                log.warn("Startup ingest 실패: {}", e.toString());
            }
        });

        // autorun이 활성화되어 있으면 메일도 발송
        if (props.autorun()) {
            CompletableFuture.runAsync(() -> {
                try {
                    job.run();
                } catch (Exception e) {
                    log.warn("Autorun 실패: {}", e.toString());
                }
            });
        }
    }
}