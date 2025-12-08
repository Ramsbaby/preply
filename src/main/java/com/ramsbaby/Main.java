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
        if (!props.autorun()) {
            return;
        }
        // 부팅 지연 방지를 위해 비동기 실행
        CompletableFuture.runAsync(() -> {
            try {
                job.run();
            } catch (Exception e) {
                log.warn("Autorun 실패: {}", e.toString());
            }
        });
    }
}