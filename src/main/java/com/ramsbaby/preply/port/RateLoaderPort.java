package com.ramsbaby.preply.port;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import com.ramsbaby.preply.dto.Money;
import com.ramsbaby.preply.dto.ParsedMail;
import com.ramsbaby.preply.dto.RateEntry;

public interface RateLoaderPort {
    Map<String, Money> loadRates();

    List<RateEntry> loadTodayCancellationCompensations();

    List<ParsedMail> fetchBookings(int lookBackDays);

    List<ParsedMail> fetchCancellationCompensations(int lookBackDays, LocalDate today);
}

