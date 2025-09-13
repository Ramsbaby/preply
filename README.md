# Preply Daily Summary

Preply 수업 예약 메일과 Google Calendar를 읽어, 하루 수입 요약을 메일로 발송하는 Spring Boot 애플리케이션입니다.

## 요구 사항

- Java 17+
- Gradle 8+
- SMTP 사용 가능한 메일 계정(Naver Mail 등)
- Google Calendar API 접근(서비스 계정 또는 사용자 자격증명)

## 설정

민감정보는 환경변수 또는 `application.yml`에서 주입합니다. 배포 전 예시 파일을 참고하세요: `src/main/resources/application-example.yml`.

주요 환경변수:

- APP_MAIL_USER: SMTP 사용자(예: `your@naver.com`)
- APP_MAIL_PASS: SMTP 앱 비밀번호
- APP_MAIL_IMAP_HOST, APP_MAIL_IMAP_PORT: IMAP 설정(기본 `imap.naver.com:993`)
- APP_MAIL_SMTP_HOST, APP_MAIL_SMTP_PORT: SMTP 설정(기본 `smtp.naver.com:465`)
- APP_MAIL_FROM: 발신자 이메일
- APP_MAIL_TO: 콤마로 구분된 수신자 목록 예: `you@naver.com,ms6698@naver.com`
- APP_WEBHOOK_SLACK_URL: 선택. Slack Webhook URL
- APP_GCAL_CREDENTIALS_PATH: Google 자격증명 파일 경로(`file:/abs/path.json` 등)
- APP_GCAL_CALENDAR_ID: Google Calendar ID
- APP_GCAL_TZ: 타임존(기본 `Asia/Seoul`)
- APP_GCAL_PREPLY_SUFFIX: 캘린더 이벤트 접미사(기본 ` - Preply lesson`)
- APP_GCAL_LOOKBACK_DAYS: 메일에서 단가 캐시 조회 기간(일, 기본 90)
- APP_AUTORUN: 애플리케이션 기동 시 자동 1회 실행 여부(true/false)

참고: `src/main/resources/application.yml`은 위 환경변수 값을 참조하도록 구성되어 있습니다.

## 실행

```bash
# Windows PowerShell 예시
$env:APP_MAIL_USER="your@naver.com"
$env:APP_MAIL_PASS="app-password"
$env:APP_MAIL_FROM="your@naver.com"
$env:APP_MAIL_TO="your@naver.com,ms6698@naver.com"
$env:APP_GCAL_CREDENTIALS_PATH="file:C:/path/to/your-sa.json"
$env:APP_GCAL_CALENDAR_ID="your_calendar_id@group.calendar.google.com"
$env:APP_AUTORUN="true"

./gradlew bootRun
```

수동 실행 엔드포인트:

- GET `/run` → 당일 요약 생성 및 메일 발송

## 스케줄링

`com.ramsbaby.preply.config.SchedulingConfig`에서 `@EnableScheduling`이 활성화되어 있습니다. 필요 시 `DailySummaryJob.run()`의 `@Scheduled` 주석을 해제해 사용하세요.

## 개발 메모

- 환율은 `FxRateService`에서 두 제공자(exchangerate.host → open.er-api.com)로 페일오버합니다.
- 수신자 설정은 `app.mail.smtp.to` 배열로 관리되며, 항상 `from`도 수신 목록에 포함됩니다.

## 보안/비공개 파일

- 자격증명(JSON/P12/PEM) 및 실제 `application.yml`은 커밋하지 마세요.
- `.gitignore`에 기본 제외 규칙이 포함되어 있습니다.

## 라이선스

MIT (LICENSE 참고)
