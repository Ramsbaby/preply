package com.ramsbaby;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

import com.ramsbaby.preply.component.DailySummaryJob;
import com.ramsbaby.preply.config.AppProps;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;

@SpringBootApplication
@ConfigurationPropertiesScan
@RequiredArgsConstructor
public class Main {
    private final DailySummaryJob job;
    private final AppProps props;

    public static void main(String[] args) {
        SpringApplication.run(Main.class, args);
    }

    @PostConstruct
    public void init() throws Exception {
        if (props.autorun()) {
            job.run();
        }
    }
}