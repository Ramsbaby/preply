package com.ramsbaby.preply.port;

import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import com.ramsbaby.preply.dto.Money;
import com.ramsbaby.preply.dto.ParsedMail;
import com.ramsbaby.preply.dto.RateEntry;

public interface MailCachePort {
    boolean enabled();

    void upsert(List<ParsedMail> mails);

    Map<String, Money> findLatestBookingRates();

    List<RateEntry> findTodayCompensations(ZoneId tz);
}

