package com.ramsbaby.preply.component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

import com.ramsbaby.preply.dto.FetchResult;
import com.ramsbaby.preply.dto.ParsedMail;
import com.ramsbaby.preply.port.MailCachePort;
import com.ramsbaby.preply.port.RateLoaderPort;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class MailIngestionService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final RateLoaderPort loader;
    private final MailCachePort supabase;

    public IngestResult ingestYear() {
        // 스타트업 부하 완화: 1년 → 6개월(180일)로 축소
        return ingest(180);
    }

    public IngestResult ingest(int lookBackDays) {
        if (!supabase.enabled()) {
            log.info("Supabase 미설정: ingest 스킵");
            return new IngestResult(0, 0, 0, 0, false);
        }

        FetchResult bookings = loader.fetchBookings(lookBackDays);
        FetchResult comps = loader.fetchCancellationCompensations(lookBackDays, LocalDate.now(KST));

        List<ParsedMail> all = new ArrayList<>(bookings.mails().size() + comps.mails().size());
        all.addAll(bookings.mails());
        all.addAll(comps.mails());

        int saved = supabase.upsert(all);
        int totalRead = bookings.totalScannedCount() + comps.totalScannedCount();

        log.info("Ingest 완료 (lookBack={}days): scan={}, saved={}", lookBackDays, totalRead, saved);

        return new IngestResult(bookings.mails().size(), comps.mails().size(), totalRead, saved, true);
    }

    public record IngestResult(int bookingCount, int compensationCount, int totalReadCount, int savedCount,
            boolean persisted) {
    }
}
