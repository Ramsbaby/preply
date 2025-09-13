package com.ramsbaby.preply.component;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class FxRateService {

    private static final Duration TTL = Duration.ofMinutes(30);
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final ObjectMapper om = new ObjectMapper();
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public static String formatKrw(BigDecimal krw) {
        var df = new java.text.DecimalFormat("#,##0원");
        df.setRoundingMode(RoundingMode.HALF_UP);
        return df.format(krw.setScale(0, RoundingMode.HALF_UP));
    }

    public BigDecimal krwPer(String currency) {
        String cur = Objects.requireNonNull(currency, "currency").toUpperCase(Locale.ROOT);
        if ("KRW".equals(cur)) return BigDecimal.ONE;

        // 1) 캐시 적중
        Cached c = cache.get(cur);
        if (c != null && c.isFresh()) return c.rate();

        // 2) 페일오버 체인: provider1 → provider2
        BigDecimal fetched = fetchFromExchangerateHost(cur);
        String source = "exchangerate.host";
        if (fetched == null) {
            fetched = fetchFromErApi(cur);
            source = "open.er-api.com";
        }
        if (fetched == null) {
            // 마지막 성공값이라도 있으면 사용
            if (c != null) return c.rate();
            throw new IllegalStateException("환율 조회 실패: " + cur + "→KRW");
        }
        cache.put(cur, new Cached(fetched, Instant.now(), source));
        return fetched;
    }

    // === Provider 1: exchangerate.host ===
    private BigDecimal fetchFromExchangerateHost(String currency) {
        try {
            String url = "https://api.exchangerate.host/latest?base="
                    + URLEncoder.encode(currency, StandardCharsets.UTF_8)
                    + "&symbols=KRW";
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .header("Accept", "application/json")
                    .header("User-Agent", "Preply-Summary/1.0")
                    .timeout(Duration.ofSeconds(7))
                    .GET().build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                debugHttp("exchangerate.host", res);
                return null;
            }
            RateResp dto = om.readValue(res.body(), RateResp.class);
            return dto.rates() == null ? null : dto.rates().get("KRW");
        } catch (Exception e) {
            System.out.println("[FX] exchangerate.host error: " + e.getMessage());
            return null;
        }
    }

    // === Provider 2: open.er-api.com ===
    private BigDecimal fetchFromErApi(String currency) {
        try {
            // 예: https://open.er-api.com/v6/latest/USD
            String url = "https://open.er-api.com/v6/latest/" + URLEncoder.encode(currency, StandardCharsets.UTF_8);
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .header("Accept", "application/json")
                    .header("User-Agent", "Preply-Summary/1.0")
                    .timeout(Duration.ofSeconds(7))
                    .GET().build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                debugHttp("open.er-api.com", res);
                return null;
            }
            ErApiResp dto = om.readValue(res.body(), ErApiResp.class);
            if (!"success".equalsIgnoreCase(dto.result())) return null;
            return dto.rates() == null ? null : dto.rates().get("KRW");
        } catch (Exception e) {
            System.out.println("[FX] open.er-api.com error: " + e.getMessage());
            return null;
        }
    }

    private void debugHttp(String provider, HttpResponse<String> res) {
        String body = res.body();
        if (body != null && body.length() > 300) body = body.substring(0, 300) + "...";
        System.out.println("[FX] " + provider + " HTTP " + res.statusCode() + " body=" + body);
    }

    public Snapshot snapshot(String currency) {
        BigDecimal rate = krwPer(currency);
        Cached c = cache.get(currency.toUpperCase(Locale.ROOT));
        return new Snapshot(rate, c != null ? c.at : Instant.now(), c != null ? c.source : "unknown");
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record RateResp(Map<String, BigDecimal> rates) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ErApiResp(String result, Map<String, BigDecimal> rates) {
    }

    private record Cached(BigDecimal rate, Instant at, String source) {
        boolean isFresh() {
            return at.plus(TTL).isAfter(Instant.now());
        }
    }

    // 메일 본문에 사용할 스냅샷 (asOf 시각/출처 포함)
    public record Snapshot(BigDecimal krwPer, Instant asOf, String source) {
    }
}
