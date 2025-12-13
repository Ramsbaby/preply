package com.ramsbaby;

import java.util.concurrent.CompletableFuture;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.event.EventListener;

import com.ramsbaby.preply.component.DailySummaryJob;
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

    public static void main(String[] args) {
        SpringApplication.run(Main.class, args);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initAsync() {
        // 시작 시 ingest는 하지 않음 (스케줄러에서만 실행)
        // ingest는 ScheduledIngestionJob (매일 9시 KST)에서 처리
        log.info("Application started. ingest는 스케줄러에서 실행됩니다.");

        // autorun이 활성화되어 있으면 DB 조회 후 요약 메일 발송
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