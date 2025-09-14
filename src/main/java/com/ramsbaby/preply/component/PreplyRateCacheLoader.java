package com.ramsbaby.preply.component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import com.ramsbaby.preply.config.AppProps;
import com.ramsbaby.preply.dto.Money;
import com.ramsbaby.preply.dto.RateEntry;

import jakarta.mail.FetchProfile;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.search.AndTerm;
import jakarta.mail.search.ComparisonTerm;
import jakarta.mail.search.OrTerm;
import jakarta.mail.search.ReceivedDateTerm;
import jakarta.mail.search.SearchTerm;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class PreplyRateCacheLoader {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private final AppProps props;

    // 이름 정규화(캘린더 매칭용): 괄호 alias 제거, 접미사/여백 정리, 케이스-인센시티브 키
    public static String normalize(String raw) {
        if (raw == null)
            return "";
        // 괄호 alias / 접미사 제거
        String s = raw.replaceAll("\\s*\\(.*?\\)\\s*", " ")
                .replaceAll("\\s+-\\s*Preply lesson\\s*$", "")
                .replace(".", " ") // 성 이니셜의 점 제거: "S." -> "S"
                .trim();
        s = s.replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT);

        // 토큰 기준 후처리: "camila s" 처럼 두 번째 토큰이 1글자면 버림
        String[] t = s.split(" ");
        if (t.length >= 2 && t[1].length() == 1) {
            s = t[0]; // camila
        }
        return s;
    }

    private static String cleanText(String s) {
        if (s == null)
            return "";
        return s
                .replace('\u00A0', ' ') // NBSP → space
                .replace('\u202F', ' ') // NNBSP → space
                .replaceAll("[\\u200B\\u200C\\u200D\\uFEFF\\u2060\\u00AD]", "") // ZWSP/ZWJ/BOM/SHY 제거
                .replaceAll("[\\s\\u0000-\\u001F]+", " ") // 제어문자/여러 공백 → 한 칸
                .trim();
    }

    private static String firstMatch(String s, Pattern... ps) {
        for (Pattern p : ps) {
            Matcher m = p.matcher(s);
            if (m.find())
                return m.group(1);
        }
        return null;
    }

    public Map<String, Money> loadRates() {
        Map<String, Money> rateByStudent = new HashMap<>();
        Properties p = new Properties();
        p.put("mail.store.protocol", "imaps");
        p.put("mail.imaps.host", props.mail().imap().host());
        p.put("mail.imaps.port", String.valueOf(props.mail().imap().port()));
        p.put("mail.imaps.ssl.enable", "true");
        p.put("mail.mime.allowutf8", "true");

        Session session = Session.getInstance(p);
        try (Store store = session.getStore("imaps")) {
            store.connect(props.mail().user(), props.mail().pass());
            Folder inbox = store.getFolder("INBOX");
            inbox.open(Folder.READ_ONLY);

            // 기간: lookBackDays ~ 내일 00:00
            LocalDate today = LocalDate.now(KST);
            Date start = Date.from(today.minusDays(props.gcal().lookBackDays()).atStartOfDay(KST).toInstant());
            Date end = Date.from(today.plusDays(1).atStartOfDay(KST).toInstant());

            // 제목(국/영) + 수신일 범위(사용자 요청 120일 기준 props로 제어)
            SearchTerm subjOr = new OrTerm(
                    new jakarta.mail.search.SubjectTerm("예약했어요"),
                    new jakarta.mail.search.SubjectTerm("scheduled a new lesson"));

            SearchTerm dateTerm = new AndTerm(
                    new ReceivedDateTerm(ComparisonTerm.GE, start),
                    new ReceivedDateTerm(ComparisonTerm.LT, end));

            SearchTerm term = new AndTerm(subjOr, dateTerm);

            Message[] found = inbox.search(term);

            // 프리페치
            FetchProfile fp = new FetchProfile();
            fp.add(FetchProfile.Item.ENVELOPE);
            fp.add(FetchProfile.Item.CONTENT_INFO);
            inbox.fetch(found, fp);

            for (Message m : found) {
                extractRate(m).ifPresent(re -> {
                    String key = normalize(re.studentName());
                    // 최신 메일 우선(수신일이 더 최근이면 갱신)
                    Money prev = rateByStudent.get(key);
                    if (prev == null)
                        rateByStudent.put(key, re.money());
                    else
                        rateByStudent.put(key, re.money()); // 필요 시 통화 우선 규칙 추가
                });
            }
            inbox.close(false);
        } catch (MessagingException e) {
            throw new IllegalStateException("IMAP 읽기 실패", e);
        }

        return rateByStudent;
    }

    private static boolean isInRange(Message m, Date start, Date end) {
        try {
            Date r = m.getReceivedDate();
            Date s = m.getSentDate();
            if (r != null && !r.before(start) && r.before(end))
                return true;
            if (s != null && !s.before(start) && s.before(end))
                return true;
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private Optional<RateEntry> extractRate(Message msg) {
        try {
            // 1) HTML 우선으로 꺼내서 평탄화
            String html = extractHtml(msg).orElseGet(() -> {
                try {
                    return extractText(msg).orElse("");
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            if (html.isBlank())
                return Optional.empty();

            String text = org.jsoup.Jsoup.parse(html).text();
            String cleaned = cleanText(text);

            // 2) 학생 이름 (KO/EN 라벨 모두 지원, 다음 라벨 직전까지만 비탐욕 캡처)
            Pattern STU_KO = Pattern.compile(
                    "학생\\s*[:：]\\s*(.+?)\\s*(?=(레슨\\s*시간|Lesson\\s*time|비용|Price)\\s*[:：]|$)",
                    Pattern.CASE_INSENSITIVE);
            Pattern STU_EN = Pattern.compile(
                    "Student\\s*[:：]\\s*(.+?)\\s*(?=(Lesson\\s*time|레슨\\s*시간|Price|비용)\\s*[:：]|$)",
                    Pattern.CASE_INSENSITIVE);
            String student = firstMatch(cleaned, STU_KO, STU_EN);
            if (student == null || student.isBlank()) {
                return Optional.empty();
            }
            student = student.trim();

            // 3) 금액/통화 (다양한 표기 대응: 라벨/콜론 선택, 기호 앞뒤, 통화 코드/기호)
            Pattern P_PRICE = Pattern.compile(
                    "(?:비용|Price)\\s*[:：]?\\s*(\\$|USD|₩|KRW)?\\s*([0-9][0-9,]*\\.?[0-9]{0,2})\\s*(USD|KRW|\\$|₩)?",
                    Pattern.CASE_INSENSITIVE);
            String num = null;
            String curRaw = null;
            Matcher mp = P_PRICE.matcher(cleaned);
            if (mp.find()) {
                num = mp.group(2);
                curRaw = mp.group(1) != null && !mp.group(1).isBlank() ? mp.group(1) : mp.group(3);
            }

            if (num == null) {
                Matcher m1 = Pattern.compile("\\$\\s*([0-9][0-9,]*\\.?[0-9]{0,2})").matcher(cleaned);
                if (m1.find()) {
                    num = m1.group(1);
                    curRaw = "$";
                }
            }

            if (num == null) {
                Matcher m2 = Pattern
                        .compile("([0-9][0-9,]*\\.?[0-9]{0,2})\\s*(USD|KRW)", Pattern.CASE_INSENSITIVE)
                        .matcher(cleaned);
                if (m2.find()) {
                    num = m2.group(1);
                    curRaw = m2.group(2);
                }
            }

            if (num == null) {
                return Optional.empty();
            }

            BigDecimal amount = new BigDecimal(num.replace(",", "")).multiply(new BigDecimal("0.82"));

            String currency = switch (curRaw) {
                case "$", "USD", "usd" -> "USD";
                case "₩", "KRW", "krw" -> "KRW";
                default -> curRaw.toUpperCase(Locale.ROOT);
            };

            // 4) 수신 시각(KST)
            ZonedDateTime receivedAt = Optional.ofNullable(msg.getReceivedDate())
                    .map(d -> d.toInstant().atZone(KST))
                    .orElse(ZonedDateTime.now(KST));

            // 5) 캐싱에 들어갈 엔트리 반환
            return Optional.of(new RateEntry(student, new Money(amount, currency), receivedAt));

        } catch (Exception e) {
            // log.warn("extractRate error: {}", e.toString());
            return Optional.empty();
        }
    }

    private Optional<String> extractHtml(Part p) throws Exception {
        if (p.isMimeType("text/html"))
            return Optional.of((String) p.getContent());
        if (p.isMimeType("multipart/*")) {
            Multipart mp = (Multipart) p.getContent();
            for (int i = 0; i < mp.getCount(); i++) {
                var r = extractHtml(mp.getBodyPart(i));
                if (r.isPresent())
                    return r;
            }
        }
        if (p.isMimeType("message/rfc822"))
            return extractHtml((Part) p.getContent());
        return Optional.empty();
    }

    private Optional<String> extractText(Part p) throws Exception {
        if (p.isMimeType("text/plain"))
            return Optional.of((String) p.getContent());
        if (p.isMimeType("multipart/*")) {
            Multipart mp = (Multipart) p.getContent();
            for (int i = 0; i < mp.getCount(); i++) {
                var r = extractText(mp.getBodyPart(i));
                if (r.isPresent())
                    return r;
            }
        }
        if (p.isMimeType("message/rfc822"))
            return extractText((Part) p.getContent());
        return Optional.empty();
    }
}