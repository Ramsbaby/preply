package com.ramsbaby.preply.dto;

import java.util.List;

public record FetchResult(List<ParsedMail> mails, int totalScannedCount) {
}
