package com.ramsbaby.preply.component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

import com.ramsbaby.preply.dto.ParsedMail;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class MailIngestionService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final PreplyRateCacheLoader loader;
    private final SupabaseMailRepository supabase;

    public IngestResult ingestYear() {
        // 스타트업 부하 완화: 1년 → 6개월(180일)로 축소
        return ingest(180);
    }

    public IngestResult ingest(int lookBackDays) {
        if (!supabase.enabled()) {
            log.info("Supabase 미설정: ingest 스킵");
            return new IngestResult(0, 0, false);
        }

        List<ParsedMail> bookings = loader.fetchBookings(lookBackDays);
        List<ParsedMail> comps = loader.fetchCancellationCompensations(lookBackDays, LocalDate.now(KST));

        List<ParsedMail> all = new ArrayList<>(bookings.size() + comps.size());
        all.addAll(bookings);
        all.addAll(comps);

        supabase.upsert(all);
        return new IngestResult(bookings.size(), comps.size(), true);
    }

    public record IngestResult(int bookingCount, int compensationCount, boolean persisted) {
    }
}
