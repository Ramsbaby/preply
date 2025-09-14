package com.ramsbaby.preply.component;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.calendar.Calendar;
import com.google.api.services.calendar.model.Events;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.GoogleCredentials;
import com.ramsbaby.preply.config.AppProps;
import com.ramsbaby.preply.dto.LessonEvent;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class GcalReader {
    private final AppProps props;
    private final ResourceLoader resourceLoader;

    private HttpCredentialsAdapter requestInitializer() throws IOException {
        String cfg = props.gcal().credentialsPath(); // "classpath:calendar-json/preply-sa.json"
        var resource = resourceLoader.getResource(cfg); // classpath:/ 파일:/ 둘 다 지원
        try (var in = resource.getInputStream()) {
            var creds = GoogleCredentials.fromStream(in)
                    .createScoped("https://www.googleapis.com/auth/calendar.readonly");
            return new HttpCredentialsAdapter(creds);
        }
    }

    public List<LessonEvent> loadTodayPreplyEvents() {
        try {
            var http = GoogleNetHttpTransport.newTrustedTransport();
            var json = GsonFactory.getDefaultInstance();
            var reqInit = requestInitializer();

            // 2) Calendar 클라이언트
            Calendar client = new Calendar.Builder(http, json, reqInit)
                    .setApplicationName("Preply Summary")
                    .build();

            // 3) 오늘 일정 조회
            var tz = ZoneId.of(props.gcal().timeZone());
            var todayStart = LocalDate.now(tz).atStartOfDay(tz).toInstant();
            var tomorrowStart = LocalDate.now(tz).plusDays(1).atStartOfDay(tz).toInstant();

            Events resp = client.events().list(props.gcal().calendarId())
                    .setTimeMin(new com.google.api.client.util.DateTime(java.util.Date.from(todayStart)))
                    .setTimeMax(new com.google.api.client.util.DateTime(java.util.Date.from(tomorrowStart)))
                    .setSingleEvents(true)
                    .setOrderBy("startTime")
                    .execute();

            // 4) " - Preply lesson" 으로 끝나는 이벤트만 파싱
            List<LessonEvent> out = new ArrayList<>();
            String suffix = props.gcal().preplySuffix();
            resp.getItems().forEach(item -> {
                String summary = item.getSummary();
                if (summary == null || !summary.endsWith(suffix))
                    return;

                String base = summary.substring(0, summary.length() - suffix.length()).trim();
                String name = base.replaceAll("\\s*\\(.*?\\)\\s*", " ").replaceAll("\\s+", " ").trim();
                long startMillis = item.getStart().getDateTime() != null
                        ? item.getStart().getDateTime().getValue()
                        : item.getStart().getDate().getValue();
                var startAt = ZonedDateTime.ofInstant(Instant.ofEpochMilli(startMillis), tz);

                out.add(new LessonEvent(PreplyRateCacheLoader.normalize(name), startAt));
            });
            return out;

        } catch (GoogleJsonResponseException gjre) {
            var code = gjre.getStatusCode();
            var details = gjre.getDetails();
            var reason = (details != null && details.getErrors() != null && !details.getErrors().isEmpty())
                    ? details.getErrors().get(0).getReason()
                    : "unknown";
            throw new IllegalStateException("Google Calendar 읽기 실패: HTTP " + code + " / reason=" + reason
                    + " / message=" + gjre.getMessage()
                    + "\n- 캘린더 공유 여부(서비스 계정 이메일)와 calendarId를 확인하세요.", gjre);
        } catch (IOException ioe) {
            throw new IllegalStateException("Google Calendar 자격증명 로딩 실패: "
                    + ioe.getMessage()
                    + "\n- GOOGLE_APPLICATION_CREDENTIALS 또는 app.gcal.credentialsPath를 확인하세요.", ioe);
        } catch (Exception e) {
            throw new IllegalStateException("Google Calendar 읽기 실패(기타): " + e.getMessage(), e);
        }

    }

}