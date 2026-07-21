package com.ramsbaby.preply.component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
// import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import com.ramsbaby.preply.config.AppProps;
import com.ramsbaby.preply.dto.FetchResult;
import com.ramsbaby.preply.dto.Money;
import com.ramsbaby.preply.dto.ParsedMail;
import com.ramsbaby.preply.dto.RateEntry;
import com.ramsbaby.preply.port.RateLoaderPort;

import jakarta.mail.FetchProfile;
import jakarta.mail.Folder;
import jakarta.mail.FolderClosedException;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.StoreClosedException;
import jakarta.mail.search.AndTerm;
import jakarta.mail.search.ComparisonTerm;
import jakarta.mail.search.OrTerm;
import jakarta.mail.search.ReceivedDateTerm;
import jakarta.mail.search.SearchTerm;
import jakarta.mail.search.SubjectTerm;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class PreplyRateCacheLoader implements RateLoaderPort {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private final AppProps props;
    private static final int SNIPPET_LIMIT = 200;

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

    private static String normalizeCurrency(String rawCurrency) {
        if (rawCurrency == null || rawCurrency.isBlank()) {
            return "USD";
        }
        return switch (rawCurrency) {
            case "$", "USD", "usd" -> "USD";
            case "₩", "KRW", "krw" -> "KRW";
            default -> rawCurrency.toUpperCase(Locale.ROOT);
        };
    }

    // --------- Patterns & Functional Predicates (reuse across parsers) ---------
    private static final Pattern P_LESSON_DATE = Pattern.compile("레슨\\s*[:：]\\s*(\\d{1,2})월\\s*(\\d{1,2})일");
    private static final Pattern P_LESSON_START_KO = Pattern.compile("레슨\\s*시작\\s*[:：]\\s*(\\d{1,2})월\\s*(\\d{1,2})일");
    // "일정: 7월 25일 금요일" 형식도 지원
    private static final Pattern P_LESSON_DATE_ALT = Pattern.compile("일정\\s*[:：]\\s*(\\d{1,2})월\\s*(\\d{1,2})일");
    private static final Pattern P_LESSON_START_EN = Pattern.compile(
            "Lesson\\s*time\\s*[:：].*?(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\\s+(\\d{1,2})",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern P_STUDENT_BODY = Pattern.compile(
            "학생\\s*[:：]\\s*(.+?)\\s*(?=(레슨|Lesson|비용|Price)\\s*[:：]|$)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern P_SUBJECT_CANCEL_STUDENT = Pattern.compile("(.+?)\\s*학생이\\s*수업을\\s*취소했습니다");
    private static final Pattern P_USD_INLINE = Pattern.compile("\\$\\s*([0-9][0-9,]*\\.?[0-9]{0,2})");
    private static final Pattern P_AMOUNT_CURRENCY = Pattern.compile(
            "([0-9][0-9,]*\\.?[0-9]{0,2})\\s*(USD|KRW|\\$|₩)", Pattern.CASE_INSENSITIVE);

    // 신규 구독 메일: 제목/본문에서 학생명 추출 (예: "Anna Y. 학생이 구독했어요!").
    // "학생이"가 표준 표기(주인님 확인)이며, 혹시 모를 "님이" 변형도 유연하게 허용.
    private static final Pattern P_SUBSCRIPTION_STUDENT = Pattern.compile("(.+?)\\s*(?:학생이|님이)\\s*구독");
    // 신규 구독 학생 기본 단가: $40 순수익 표시값. 예약 메일과 달리 ×0.82(수수료 제외) 적용하지 않는다.
    private static final BigDecimal SUBSCRIPTION_DEFAULT_USD = new BigDecimal("40.00");
    // 구독 메일 in-memory 종류 마커. DB 저장 시에는 SupabaseMailRepository가 'booking'으로 변환한다
    // (프로덕션 테이블 kind CHECK 제약이 'booking'|'cancellation_compensation'만 허용 + findLatestBookingRates가 kind='booking'만 조회).
    static final String KIND_SUBSCRIPTION = "subscription";

    private static final java.util.function.Predicate<String> IS_COMPENSATION_TEXT = t -> t
            .contains("예정된 시작 시간 12시간 전에 취소") && t.contains("지불해 드립니다");

    public Map<String, Money> loadRates() {
        Map<String, Money> rateByStudent = new HashMap<>();
        List<ParsedMail> mails = fetchBookings(props.gcal().lookBackDays()).mails();
        // 병합 규칙: 정식 예약 메일의 실제 단가가 항상 우선. 구독 $40은 그 학생의 예약 단가가
        // 없을 때만 fallback. 순서에 의존하지 않도록 (1) 구독을 먼저 깔고 (2) 예약으로 덮어쓴다.
        mails.stream()
                .filter(pm -> KIND_SUBSCRIPTION.equals(pm.kind()))
                .forEach(pm -> rateByStudent.put(pm.studentNormalized(), pm.money()));
        mails.stream()
                .filter(pm -> !KIND_SUBSCRIPTION.equals(pm.kind()))
                .forEach(pm -> rateByStudent.put(pm.studentNormalized(), pm.money()));
        return rateByStudent;
    }

    /**
     * 오늘(KST) 레슨이 12시간 이내 취소되어 보상 지급되는 메일을 파싱하여 금액을 반환한다.
     * 조건:
     * - 제목에 "수업을 취소했습니다" 포함 (예: "Roth 학생이 수업을 취소했습니다")
     * - 본문에 "예정된 시작 시간 12시간 전에 취소" 및 "지불해 드립니다" 포함
     * - 본문에 "레슨: {M}월 {D}일" 포함하고, 그 날짜가 오늘과 동일
     * - 금액/통화(예: 22.00 $, 22 USD 등) 추출
     */
    public List<RateEntry> loadTodayCancellationCompensations() {
        LocalDate today = LocalDate.now(KST);
        return fetchCancellationCompensations(props.gcal().lookBackDays(), today).mails().stream()
                .filter(pm -> pm.lessonDate() != null && pm.lessonDate().equals(today))
                .map(pm -> new RateEntry(pm.studentNormalized(), pm.money(), pm.receivedAt()))
                .toList();
    }

    /**
     * 예약 메일을 lookBackDays 범위에서 파싱하여 반환한다.
     */
    public FetchResult fetchBookings(int lookBackDays) {
        List<ParsedMail> all = new ArrayList<>();
        int totalScanned = 0;
        LocalDate today = LocalDate.now(KST);
        LocalDate start = today.minusDays(lookBackDays);
        LocalDate end = today.plusDays(1);

        LocalDate current = start;
        // end 날짜까지 7일 단위 반복
        while (current.isBefore(end)) {
            LocalDate next = current.plusDays(7);
            if (next.isAfter(end)) {
                next = end;
            }

            // log.info("Booking fetch chunk: {} ~ {}", current, next);
            try {
                FetchResult chunk = fetchBookingsChunk(current, next);
                all.addAll(chunk.mails());
                totalScanned += chunk.totalScannedCount();
            } catch (Exception e) {
                // 한 덩어리 실패 시 전체 중단 (데이터 정합성 위해)
                throw new RuntimeException("Chunk fetch failed for " + current + "~" + next, e);
            }
            current = next;
        }

        return new FetchResult(all, totalScanned);
    }

    private FetchResult fetchBookingsChunk(LocalDate start, LocalDate end) {
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

            Date dateStart = Date.from(start.atStartOfDay(KST).toInstant());
            Date dateEnd = Date.from(end.atStartOfDay(KST).toInstant());

            // Subject Term + Date Range Term
            // "구독" 추가: 신규 학생의 "◯◯ 학생이 구독했어요!" 메일도 함께 검색한다.
            SearchTerm subjectTerm = new OrTerm(new SearchTerm[] {
                    new SubjectTerm("예약했어요"),
                    new SubjectTerm("scheduled a new lesson"),
                    new SubjectTerm("구독")
            });
            SearchTerm dateTerm = new AndTerm(
                    new ReceivedDateTerm(ComparisonTerm.GE, dateStart),
                    new ReceivedDateTerm(ComparisonTerm.LT, dateEnd));
            SearchTerm term = new AndTerm(subjectTerm, dateTerm);

            Message[] found = inbox.search(term);
            log.info("Gmail 검색(booking) 결과 [{} ~ {}]: {}건", start, end, found.length);

            FetchProfile fp = new FetchProfile();
            fp.add(FetchProfile.Item.ENVELOPE);
            fp.add(FetchProfile.Item.CONTENT_INFO);
            inbox.fetch(found, fp);

            List<ParsedMail> results = Arrays.stream(found)
                    // isInRange는 이미 SearchQuery로 필터링했으므로 제거 가능하지만, 더 안전하게 유지해도 됨
                    // 여기서는 SearchTerm을 믿고 바로 매핑. 제목에 "구독"이 있으면 구독 파서로 분기.
                    .map(this::parseBookingOrSubscription)
                    .flatMap(Optional::stream)
                    .toList();
            inbox.close(false);
            return new FetchResult(results, found.length);
        } catch (MessagingException e) {
            throw new IllegalStateException("IMAP 읽기 실패", e);
        }
    }

    /**
     * 취소 보상 메일을 lookBackDays 범위에서 파싱하여 반환한다.
     */
    public FetchResult fetchCancellationCompensations(int lookBackDays, LocalDate today) {
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

            Date start = Date.from(today.minusDays(lookBackDays).atStartOfDay(KST).toInstant());
            Date end = Date.from(today.plusDays(1).atStartOfDay(KST).toInstant());

            Message[] found = inbox.search(new jakarta.mail.search.SubjectTerm("수업을 취소했습니다"));
            FetchProfile fp = new FetchProfile();
            fp.add(FetchProfile.Item.ENVELOPE);
            fp.add(FetchProfile.Item.CONTENT_INFO);
            inbox.fetch(found, fp);

            List<ParsedMail> results = Arrays.stream(found)
                    .filter(m -> isInRange(m, start, end))
                    .map(m -> parseCancellationCompensation(m, today))
                    .flatMap(Optional::stream)
                    .toList();

            inbox.close(false);
            return new FetchResult(results, found.length);
        } catch (MessagingException e) {
            throw new IllegalStateException("IMAP 읽기 실패(취소 보상)", e);
        }
    }

    private Optional<ParsedMail> parseCancellationCompensation(Message m, LocalDate today) {
        try {
            String html = extractHtml(m).orElseGet(() -> {
                try {
                    return extractText(m).orElse("");
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            if (html.isBlank())
                return Optional.empty();

            String cleaned = cleanText(org.jsoup.Jsoup.parse(html).text());
            if (!IS_COMPENSATION_TEXT.test(cleaned))
                return Optional.empty();

            LocalDate lessonDate = extractLessonDate(cleaned, today);
            if (lessonDate == null || !lessonDate.equals(today))
                return Optional.empty();

            String student = extractStudent(cleaned).or(() -> extractStudentFromSubject(m)).map(String::trim)
                    .orElse("");
            if (student.isBlank())
                return Optional.empty();

            var amountAndCurrency = extractAmountAndCurrency(cleaned).orElse(null);
            if (amountAndCurrency == null)
                return Optional.empty();

            BigDecimal amount = amountAndCurrency.amount().multiply(new BigDecimal("0.82"));
            String currency = normalizeCurrency(amountAndCurrency.currencyRaw());

            ZonedDateTime receivedAt = Optional.ofNullable(m.getReceivedDate())
                    .map(d -> d.toInstant().atZone(KST))
                    .orElse(ZonedDateTime.now(KST));

            String msgId = messageId(m, receivedAt, student);
            String snippet = snippet(cleaned);

            return Optional.of(new ParsedMail(
                    msgId,
                    student,
                    normalize(student),
                    new Money(amount, currency),
                    receivedAt,
                    Optional.ofNullable(m.getSubject()).orElse(""),
                    snippet,
                    "cancellation_compensation",
                    lessonDate));
        } catch (Exception e) {
            log.warn("취소 보상 파싱 오류: {}", e.toString());
            return Optional.empty();
        }
    }

    // package-private static: 순수 함수(인스턴스 상태 미사용)라 단위 테스트에서 직접 검증 가능.
    static LocalDate extractLessonDate(String cleaned, LocalDate today) {
        // 취소 보상 메일의 레슨 날짜 표기는 여러 형식일 수 있으므로 예약 메일과 동일하게 모두 시도한다.
        // (기존: "레슨: M월 D일" 한 형식만 인식 → "일정: M월 D일"·"레슨 시작: M월 D일"·영문 "Lesson time: MMM DD"
        //  형식으로 온 취소 메일은 날짜 파싱 실패로 조용히 버려져 12시간 이내 취소 수입이 누락됨)
        for (Pattern p : new Pattern[] { P_LESSON_DATE, P_LESSON_DATE_ALT, P_LESSON_START_KO }) {
            Matcher m = p.matcher(cleaned);
            if (m.find()) {
                int mm = Integer.parseInt(m.group(1));
                int dd = Integer.parseInt(m.group(2));
                return LocalDate.of(today.getYear(), mm, dd);
            }
        }
        Matcher mEn = P_LESSON_START_EN.matcher(cleaned);
        if (mEn.find()) {
            int mm = parseEnglishMonth(mEn.group(1));
            int dd = Integer.parseInt(mEn.group(2));
            if (mm > 0)
                return LocalDate.of(today.getYear(), mm, dd);
        }
        return null;
    }

    /**
     * Booking 메일에서 레슨 시작 날짜를 파싱한다.
     * 한글: "레슨 시작: MM월 DD일" 또는 "일정: MM월 DD일"
     * 영문: "Lesson time: ... MMM DD"
     * 연도는 receivedDate 기준으로 추론 (과거 날짜면 다음 년도로 간주)
     */
    private LocalDate extractLessonDateFromBooking(String cleaned, LocalDate receivedDate) {
        // 한글 패턴 1: "레슨 시작: MM월 DD일"
        Matcher mkO = P_LESSON_START_KO.matcher(cleaned);
        if (mkO.find()) {
            int mm = Integer.parseInt(mkO.group(1));
            int dd = Integer.parseInt(mkO.group(2));
            return inferLessonYear(receivedDate, mm, dd);
        }

        // 한글 패턴 2: "일정: MM월 DD일" (새 형식)
        Matcher mkAlt = P_LESSON_DATE_ALT.matcher(cleaned);
        if (mkAlt.find()) {
            int mm = Integer.parseInt(mkAlt.group(1));
            int dd = Integer.parseInt(mkAlt.group(2));
            return inferLessonYear(receivedDate, mm, dd);
        }

        // 영문 패턴 시도
        Matcher mEn = P_LESSON_START_EN.matcher(cleaned);
        if (mEn.find()) {
            String monthStr = mEn.group(1);
            int dd = Integer.parseInt(mEn.group(2));
            int mm = parseEnglishMonth(monthStr);
            if (mm > 0) {
                return inferLessonYear(receivedDate, mm, dd);
            }
        }

        return null;
    }

    private static int parseEnglishMonth(String month) {
        return switch (month.toLowerCase()) {
            case "jan", "january" -> 1;
            case "feb", "february" -> 2;
            case "mar", "march" -> 3;
            case "apr", "april" -> 4;
            case "may" -> 5;
            case "jun", "june" -> 6;
            case "jul", "july" -> 7;
            case "aug", "august" -> 8;
            case "sep", "september" -> 9;
            case "oct", "october" -> 10;
            case "nov", "november" -> 11;
            case "dec", "december" -> 12;
            default -> 0;
        };
    }

    /**
     * 메일 수신 날짜를 기준으로 레슨 날짜의 연도를 추론한다.
     * 파싱된 MM-DD가 수신 날짜보다 과거면 다음 년도로 간주.
     */
    private static LocalDate inferLessonYear(LocalDate receivedDate, int month, int day) {
        int year = receivedDate.getYear();
        LocalDate candidate = LocalDate.of(year, month, day);

        // 파싱된 날짜가 수신 날짜보다 7일 이상 과거면 다음 년도로 간주
        if (candidate.plusDays(7).isBefore(receivedDate)) {
            candidate = LocalDate.of(year + 1, month, day);
        }

        return candidate;
    }

    private Optional<String> extractStudent(String cleaned) {
        return Optional.ofNullable(firstMatch(cleaned, P_STUDENT_BODY));
    }

    private Optional<String> extractStudentFromSubject(Message m) {
        try {
            String subj = Optional.ofNullable(m.getSubject()).orElse("");
            Matcher ms = P_SUBJECT_CANCEL_STUDENT.matcher(subj);
            return ms.find() ? Optional.of(ms.group(1)) : Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static record AmountCurrency(BigDecimal amount, String currencyRaw) {
    }

    private Optional<AmountCurrency> extractAmountAndCurrency(String cleaned) {
        Matcher mUsd = P_USD_INLINE.matcher(cleaned);
        if (mUsd.find()) {
            return Optional.of(new AmountCurrency(new BigDecimal(mUsd.group(1).replace(",", "")), "$"));
        }
        Matcher mCur = P_AMOUNT_CURRENCY.matcher(cleaned);
        if (mCur.find()) {
            return Optional.of(new AmountCurrency(new BigDecimal(mCur.group(1).replace(",", "")), mCur.group(2)));
        }
        return Optional.empty();
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

    /**
     * 제목에 "구독"이 있으면 신규 구독 메일 파서로, 그 외에는 기존 예약 파서로 분기한다.
     */
    private Optional<ParsedMail> parseBookingOrSubscription(Message msg) {
        try {
            String subject = Optional.ofNullable(msg.getSubject()).orElse("");
            if (subject.contains("구독")) {
                return parseSubscription(msg);
            }
        } catch (Exception e) {
            // 제목 읽기 실패 시 예약 파서로 폴백
            log.warn("제목 읽기 실패, 예약 파서로 폴백: {}", e.toString());
        }
        return parseBooking(msg);
    }

    /**
     * 신규 학생 구독 메일("◯◯ 학생이 구독했어요!")을 파싱한다.
     * - 학생명: 제목 우선, 없으면 본문에서 추출
     * - 단가: $40 USD 순수익 표시값(예약 메일과 달리 ×0.82 미적용)
     * - 레슨 날짜: 구독 메일엔 없음 → DB(lesson_date NOT NULL·PK) 저장을 위해 수신 날짜를 사용.
     * 단가 맵(loadRates/findLatestBookingRates)은 학생 단위라 날짜와 무관하므로 매칭에 영향 없음.
     */
    private Optional<ParsedMail> parseSubscription(Message msg) {
        try {
            String subject = Optional.ofNullable(msg.getSubject()).orElse("");

            // 1) 학생명: 제목 우선, 없으면 본문 폴백
            String student = extractSubscriptionStudent(subject);
            if (student == null || student.isBlank()) {
                String html = extractHtml(msg).orElseGet(() -> {
                    try {
                        return extractText(msg).orElse("");
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
                if (!html.isBlank()) {
                    String cleaned = cleanText(org.jsoup.Jsoup.parse(html).text());
                    student = extractSubscriptionStudent(cleaned);
                }
            }
            if (student == null || student.isBlank()) {
                log.warn("구독 메일 학생명 파싱 실패 (subject: {})", subject);
                return Optional.empty();
            }
            student = student.trim();

            // 2) 수신 시각(KST)
            ZonedDateTime receivedAt = Optional.ofNullable(msg.getReceivedDate())
                    .map(d -> d.toInstant().atZone(KST))
                    .orElse(ZonedDateTime.now(KST));

            // 3) 단가: $40 순수익 그대로 (×0.82 미적용)
            Money money = new Money(SUBSCRIPTION_DEFAULT_USD, "USD");

            // 4) 레슨 날짜 없음 → 수신 날짜로 대체(DB NOT NULL·PK 충족용)
            LocalDate lessonDate = receivedAt.toLocalDate();

            String msgId = messageId(msg, receivedAt, student);
            String snippet = snippet("신규 구독 학생 기본 단가 $40 · 제목: " + subject);

            return Optional.of(new ParsedMail(
                    msgId,
                    student,
                    normalize(student),
                    money,
                    receivedAt,
                    subject,
                    snippet,
                    KIND_SUBSCRIPTION,
                    lessonDate));

        } catch (FolderClosedException | StoreClosedException e) {
            log.error("IMAP 연결 끊김 감지(구독): batch 중단");
            throw new RuntimeException("Connection closed", e);
        } catch (Exception e) {
            log.warn("구독 메일 파싱 오류: {}", e.toString());
            return Optional.empty();
        }
    }

    /**
     * 구독 메일 제목/본문에서 학생명을 추출한다.
     * package-private static 순수 함수 — 단위 테스트에서 직접 검증 가능.
     */
    static String extractSubscriptionStudent(String text) {
        if (text == null)
            return null;
        Matcher m = P_SUBSCRIPTION_STUDENT.matcher(cleanText(text));
        return m.find() ? m.group(1).trim() : null;
    }

    private Optional<ParsedMail> parseBooking(Message msg) {
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

            String currency = normalizeCurrency(curRaw);

            // 4) 수신 시각(KST)
            ZonedDateTime receivedAt = Optional.ofNullable(msg.getReceivedDate())
                    .map(d -> d.toInstant().atZone(KST))
                    .orElse(ZonedDateTime.now(KST));

            // 5) 레슨 날짜 파싱
            LocalDate lessonDate = extractLessonDateFromBooking(cleaned, receivedAt.toLocalDate());
            if (lessonDate == null) {
                log.warn("날짜 파싱 실패 (subject: {}): {}",
                        Optional.ofNullable(msg.getSubject()).orElse("?"),
                        snippet(cleaned));
                return Optional.empty();
            }

            String msgId = messageId(msg, receivedAt, student);
            String snippet = snippet(cleaned);

            // 6) 결과 반환
            return Optional.of(new ParsedMail(
                    msgId,
                    student,
                    normalize(student),
                    new Money(amount, currency),
                    receivedAt,
                    Optional.ofNullable(msg.getSubject()).orElse(""),
                    snippet,
                    "booking",
                    lessonDate));

        } catch (FolderClosedException | StoreClosedException e) {
            log.error("IMAP 연결 끊김 감지: batch 중단");
            throw new RuntimeException("Connection closed", e);
        } catch (Exception e) {
            log.warn("extractRate error: {}", e.toString());
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

    private static String snippet(String cleaned) {
        if (cleaned == null)
            return "";
        String s = cleaned.trim();
        return s.length() > SNIPPET_LIMIT ? s.substring(0, SNIPPET_LIMIT) : s;
    }

    private static String messageId(Message m, ZonedDateTime receivedAt, String student) {
        try {
            String[] ids = m.getHeader("Message-ID");
            if (ids != null && ids.length > 0 && ids[0] != null && !ids[0].isBlank())
                return ids[0];
        } catch (Exception ignore) {
        }
        String subj = "";
        try {
            subj = Optional.ofNullable(m.getSubject()).orElse("");
        } catch (Exception ignore) {
        }
        String seed = subj + "|" + receivedAt.toString() + "|" + student;
        return UUID.nameUUIDFromBytes(seed.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    }
}