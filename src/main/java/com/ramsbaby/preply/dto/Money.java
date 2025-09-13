package com.ramsbaby.preply.dto;

import java.math.BigDecimal;

public record Money(BigDecimal amount, String currency) {
}
