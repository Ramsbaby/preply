package com.ramsbaby.preply.port;

import java.math.BigDecimal;

import com.ramsbaby.preply.component.FxRateService;

public interface ExchangeRatePort {
    BigDecimal krwPer(String currency);

    FxRateService.Snapshot snapshot(String currency);
}

