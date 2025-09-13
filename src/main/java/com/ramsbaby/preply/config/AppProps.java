package com.ramsbaby.preply.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppProps(Mail mail, Gcal gcal, boolean autorun) {
    public record Mail(String user, String pass, Imap imap, Smtp smtp) {
        public record Imap(String host, int port) {
        }

        public record Smtp(String host, int port, String from, List<String> to) {
        }
    }

    public record Gcal(String credentialsPath, String calendarId, String timeZone, String preplySuffix,
            int lookBackDays) {
    }
}