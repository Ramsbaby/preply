package com.ramsbaby.preply.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ramsbaby.preply.config.AppProps;
import com.ramsbaby.preply.dto.LessonEvent;
import com.ramsbaby.preply.dto.Money;
import com.ramsbaby.preply.dto.RateEntry;
import com.ramsbaby.preply.dto.TodaySummaryResponse;
import com.ramsbaby.preply.port.LessonEventsPort;
import com.ramsbaby.preply.port.MailCachePort;
import com.ramsbaby.preply.port.RateLoaderPort;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ApiController {

    private final AppProps props;
    private final RateLoaderPort rateLoader;
    private final MailCachePort supabase;
    private final LessonEventsPort gcal;

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    @GetMapping("/today")
    public ResponseEntity<TodaySummaryResponse> todaySummary() {
        ZoneId tz = ZoneId.of(props.gcal().timeZone());
        LocalDate today = LocalDate.now(tz);

        Map<String, Money> rateByStudent;
        List<RateEntry> compensations;

        if (supabase.enabled()) {
            rateByStudent = supabase.findLatestBookingRates();
            compensations = supabase.findTodayCompensations(tz);
        } else {
            rateByStudent = rateLoader.loadRates();
            compensations = rateLoader.loadTodayCancellationCompensations();
        }

        List<LessonEvent> events = gcal.loadTodayPreplyEvents();

        List<TodaySummaryResponse.LessonDetail> scheduled = new ArrayList<>();
        List<String> unmatched = new ArrayList<>();

        for (LessonEvent e : events) {
            Money rate = rateByStudent.get(e.studentName());
            String time = e.startAt() != null ? e.startAt().format(TIME_FMT) : null;
            if (rate != null) {
                scheduled.add(new TodaySummaryResponse.LessonDetail(
                        e.studentName(), rate.amount(), rate.currency(), time));
            } else {
                unmatched.add(e.studentName());
            }
        }

        List<TodaySummaryResponse.LessonDetail> cancelled = compensations.stream()
                .map(re -> new TodaySummaryResponse.LessonDetail(
                        re.studentName(), re.money().amount(), re.money().currency(), null))
                .toList();

        // Compute totals: scheduled + compensations (avoid double-counting)
        List<TodaySummaryResponse.LessonDetail> all = new ArrayList<>(scheduled);
        Set<String> scheduledNames = scheduled.stream()
                .map(TodaySummaryResponse.LessonDetail::student)
                .collect(Collectors.toSet());
        for (var c : cancelled) {
            if (!scheduledNames.contains(c.student())) {
                all.add(c);
            }
        }

        Map<String, BigDecimal> totals = all.stream()
                .collect(Collectors.groupingBy(
                        TodaySummaryResponse.LessonDetail::currency,
                        Collectors.mapping(TodaySummaryResponse.LessonDetail::amount,
                                Collectors.reducing(BigDecimal.ZERO, BigDecimal::add))));

        return ResponseEntity.ok(new TodaySummaryResponse(
                today, scheduled, cancelled, unmatched, totals,
                scheduled.size(), cancelled.size()));
    }
}
