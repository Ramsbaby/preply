# Preply Lesson Revenue Manager

**Preply 영어 강사의 일일 수입 요약 및 메일 데이터 관리 자동화 시스템**

Spring Boot 기반으로 Gmail API와 Google Calendar API를 연동하여 Preply 플랫폼에서 발생하는 수업 예약 및 취소 보상 정보를 자동으로 수집·분석하고, 매일 수입 요약 이메일을 발송하는 애플리케이션입니다.

---

## 📋 목차

- [핵심 기능](#-핵심-기능)
- [시스템 아키텍처](#-시스템-아키텍처)
- [요구 사항](#-요구-사항)
- [환경 설정](#-환경-설정)
- [실행 방법](#-실행-방법)
- [API 엔드포인트](#-api-엔드포인트)
- [스케줄링](#-스케줄링)
- [배포 (GCP Cloud Run)](#-배포-gcp-cloud-run)
- [개발 참고사항](#-개발-참고사항)

---

## 🎯 핵심 기능

### 1. **Gmail 메일 자동 수집 및 파싱**
- **예약 메일(Booking)**: 학생이 새로운 수업을 예약한 메일을 IMAP으로 읽어 학생명, 수업료, 통화, 레슨 날짜를 파싱합니다.
- **취소 보상 메일(Cancellation Compensation)**: 수업 시작 12시간 이내 취소 시 지급되는 보상금 정보를 파싱합니다.
- 모든 금액은 플랫폼 수수료를 제외한 **순수익(82%)**으로 자동 계산됩니다.

### 2. **Google Calendar 연동**
- 당일 Google Calendar에 등록된 Preply 수업 일정을 조회합니다.
- Gmail에서 파싱한 학생별 단가 정보와 캘린더 이벤트를 자동 매칭하여 수입을 계산합니다.

### 3. **일일 수입 요약 메일 자동 발송**
- 매일 오후 11시 5분(KST)에 당일 수입 요약 메일을 자동 발송합니다. (스케줄러 활성화 필요)
- 통화별 합계, 환율 적용 KRW 총합, 수업별 상세 내역을 포함합니다.
- 매칭되지 않은 일정은 "미확인 항목"으로 별도 표시됩니다.

### 4. **Supabase(PostgreSQL) 메일 캐싱**
- Gmail IMAP 호출 최소화를 위해 Supabase에 메일 데이터를 저장합니다.
- **복합키(composite key)**: `(student_full_name, lesson_date)`로 중복 방지 및 동일 학생의 여러 레슨 관리.
- 매일 아침 9시(KST)에 최근 7일치 메일을 자동으로 Ingest하여 캐시를 갱신합니다.
- 주 1회(월요일 오후 3시 KST)에 6개월치 전체 메일을 Ingest하여 데이터 정합성을 유지합니다.

### 5. **환율 자동 조회 (페일오버)**
- `exchangerate.host` → `open.er-api.com` 순으로 자동 페일오버하여 USD, KRW 등 다중 통화를 KRW로 환산합니다.

### 6. **수동 실행 API**
- `/run`: 당일 수입 요약을 즉시 생성하여 이메일 발송.
- `/run/ingest`, `/run/ingest/daily`, `/run/ingest/weekly`: 메일 데이터를 수동으로 Supabase에 ingest.

---

## 🏗 시스템 아키텍처

```
┌─────────────────┐       ┌──────────────────┐       ┌─────────────────┐
│  Gmail (IMAP)   │◄──────┤ PreplyRate       │──────►│  Supabase       │
│  - Booking      │       │ CacheLoader      │       │  (PostgreSQL)   │
│  - Compensation │       └──────────────────┘       │  - preply_mail  │
└─────────────────┘                │                 └─────────────────┘
                                   │
                                   ▼
                         ┌──────────────────┐
                         │ MailIngestion    │
                         │ Service          │
                         └──────────────────┘
                                   │
        ┌──────────────────────────┼──────────────────────────┐
        │                          │                          │
        ▼                          ▼                          ▼
┌───────────────┐       ┌──────────────────┐       ┌─────────────────┐
│ Google        │       │ DailySummaryJob  │       │ FxRateService   │
│ Calendar API  │       │ (Daily 23:05 KST)│       │ (환율 조회)      │
└───────────────┘       └──────────────────┘       └─────────────────┘
        │                          │                          │
        └──────────────────────────┼──────────────────────────┘
                                   ▼
                         ┌──────────────────┐
                         │  SMTP (Naver)    │
                         │  메일 발송        │
                         └──────────────────┘
```

**주요 흐름**:
1. **Ingest Job (매일 09:00 / 주 1회 월요일 15:00)**: Gmail에서 메일을 읽어 Supabase에 저장
2. **Summary Job (매일 23:05)**: Supabase 캐시 또는 IMAP에서 단가 조회 → Google Calendar 일정 매칭 → 수입 계산 → 이메일 발송
3. **수동 실행**: `/run` 엔드포인트로 즉시 실행 가능

---

## 📦 요구 사항

- **Java**: 17 이상
- **Gradle**: 8 이상
- **SMTP 메일 계정**: Naver Mail 등 (앱 비밀번호 필요)
- **Google Cloud**:
  - Gmail API 활성화
  - Google Calendar API 활성화
  - 서비스 계정 JSON 키 또는 OAuth 자격증명
- **Supabase (선택)**: PostgreSQL 호환 데이터베이스 (미설정 시 IMAP 직접 조회 모드로 동작)

---

## ⚙️ 환경 설정

민감 정보는 **환경변수**로 주입합니다. `src/main/resources/application.yml`에서 환경변수를 참조하도록 구성되어 있습니다.

### 필수 환경변수

| 변수명 | 설명 | 예시 |
|--------|------|------|
| `APP_MAIL_USER` | Gmail 계정 (IMAP/SMTP) | `your@naver.com` |
| `APP_MAIL_PASS` | 메일 앱 비밀번호 | `your-app-password` |
| `APP_MAIL_FROM` | 발신자 주소 | `your@naver.com` |
| `APP_MAIL_TO` | 수신자 목록 (콤마 구분) | `you@naver.com,ms6698@naver.com` |
| `APP_GCAL_CREDENTIALS_PATH` | Google 서비스 계정 JSON 경로 | `file:/path/to/sa.json` |
| `APP_GCAL_CALENDAR_ID` | Google Calendar ID | `your_calendar@group.calendar.google.com` |

### Supabase 환경변수 (선택)

| 변수명 | 설명 | 예시 |
|--------|------|------|
| `DATABASE_URL` | PostgreSQL 연결 URL | `jdbc:postgresql://db.xxx.supabase.co:5432/postgres` |
| `DB_USERNAME` | DB 사용자명 | `postgres` |
| `DB_PASSWORD` | DB 비밀번호 | `your-db-password` |
| `APP_SUPABASE_ENABLED` | Supabase 사용 여부 | `true` |
| `APP_SUPABASE_TABLE` | 테이블명 | `preply_mail` |

**참고**: Supabase가 미설정 또는 `APP_SUPABASE_ENABLED=false`인 경우, 매번 IMAP에서 직접 메일을 읽습니다.

### 기타 선택 환경변수

| 변수명 | 설명 | 기본값 |
|--------|------|--------|
| `APP_GCAL_TZ` | 타임존 | `Asia/Seoul` |
| `APP_GCAL_LOOKBACK_DAYS` | 메일 단가 조회 일수 | `90` |
| `APP_GCAL_PREPLY_SUFFIX` | 캘린더 이벤트 접미사 | ` - Preply lesson` |
| `APP_SCHEDULER_ENABLED` | 스케줄러 활성화 여부 | `true` |
| `APP_AUTORUN` | 앱 시작 시 자동 실행 | `false` |
| `APP_RUN_TOKEN` | `/run` 보호용 토큰 | (미설정) |

---

## 🚀 실행 방법

### 로컬 실행 (Windows PowerShell)

```powershell
# 환경변수 설정
$env:APP_MAIL_USER="your@naver.com"
$env:APP_MAIL_PASS="app-password"
$env:APP_MAIL_FROM="your@naver.com"
$env:APP_MAIL_TO="your@naver.com,ms6698@naver.com"
$env:APP_GCAL_CREDENTIALS_PATH="file:C:/path/to/service-account.json"
$env:APP_GCAL_CALENDAR_ID="your_calendar_id@group.calendar.google.com"

# Supabase 설정 (선택)
$env:DATABASE_URL="jdbc:postgresql://db.xxx.supabase.co:5432/postgres"
$env:DB_USERNAME="postgres"
$env:DB_PASSWORD="your-db-password"
$env:APP_SUPABASE_ENABLED="true"
$env:APP_SUPABASE_TABLE="preply_mail"

# 실행
./gradlew bootRun
```

### Docker 실행

```bash
docker build -t preply-app .
docker run -p 8080:8080 \
  -e APP_MAIL_USER="your@naver.com" \
  -e APP_MAIL_PASS="app-password" \
  -e APP_GCAL_CREDENTIALS_PATH="file:/path/to/sa.json" \
  -e APP_GCAL_CALENDAR_ID="your_calendar@group.calendar.google.com" \
  preply-app
```

---

## 📡 API 엔드포인트

### **GET `/run`**
- **설명**: 당일 수입 요약을 즉시 생성하여 메일 발송
- **보안**: `APP_RUN_TOKEN` 설정 시 `X-Run-Token` 헤더 필요
- **응답 예시**: `200 OK`

### **GET `/run/ingest?days=180`**
- **설명**: 지정한 일수만큼 Gmail에서 메일을 읽어 Supabase에 저장
- **파라미터**: `days` (기본값: 180)
- **응답 예시**:  
  ```
  [Manual ingest(180 days)] Total scanned: 1500, Saved: 120 (Bookings: 100, Compensations: 20)
  ```

### **GET `/run/ingest/daily`**
- **설명**: 최근 7일치 메일 Ingest (Cloud Scheduler에서 매일 09:00 KST 호출용)
- **응답 예시**:  
  ```
  [Daily ingest] Total scanned: 50, Saved: 5 (Bookings: 3, Compensations: 2)
  ```

### **GET `/run/ingest/weekly`**
- **설명**: 6개월치 메일 전체 Ingest (Cloud Scheduler에서 주 1회 월요일 15:00 KST 호출용)
- **응답 예시**:  
  ```
  [Weekly ingest] Total scanned: 1200, Saved: 95 (Bookings: 80, Compensations: 15)
  ```

---

## ⏰ 스케줄링

`APP_SCHEDULER_ENABLED=true`인 경우 다음 작업이 자동으로 실행됩니다.

| 작업 | 클래스 | Cron 표현식 | 타임존 | 설명 |
|------|--------|-------------|--------|------|
| **일일 Ingest** | `ScheduledIngestionJob` | `0 0 9 * * *` | `Asia/Seoul` | 매일 09:00 KST에 7일치 메일 갱신 |
| **주간 Ingest** | `ScheduledIngestionJob` | `0 0 15 ? * MON` | `Asia/Seoul` | 매주 월요일 15:00 KST에 180일치 전체 갱신 |
| **일일 요약 발송** | `DailySummaryJob` | `0 5 23 * * *` | `Asia/Seoul` | 매일 23:05 KST에 수입 요약 메일 발송 |

**참고**: 
- 스케줄러를 비활성화하려면 `APP_SCHEDULER_ENABLED=false` 설정
- GCP Cloud Scheduler를 사용하는 경우 애플리케이션 스케줄러를 끄고 Cloud Scheduler에서 `/run/ingest/daily`, `/run/ingest/weekly` 엔드포인트를 호출하도록 설정 가능

---

## ☁️ 배포 (GCP Cloud Run)

### 1. Dockerfile 빌드 및 배포

```bash
# 이미지 빌드
gcloud builds submit --tag gcr.io/YOUR_PROJECT_ID/preply-app

# Cloud Run 배포
gcloud run deploy preply-service \
  --image gcr.io/YOUR_PROJECT_ID/preply-app \
  --platform managed \
  --region asia-northeast3 \
  --timeout 1200 \
  --set-env-vars "APP_MAIL_USER=your@naver.com,APP_MAIL_PASS=xxx,..."
```

### 2. Cloud Scheduler 설정

**매일 Ingest (09:00 KST)**:
```bash
gcloud scheduler jobs create http daily-ingest \
  --schedule="0 9 * * *" \
  --time-zone="Asia/Seoul" \
  --uri="https://YOUR_CLOUD_RUN_URL/run/ingest/daily" \
  --http-method=GET \
  --attempt-deadline=900s
```

**주간 Ingest (월요일 15:00 KST)**:
```bash
gcloud scheduler jobs create http weekly-ingest \
  --schedule="0 15 * * 1" \
  --time-zone="Asia/Seoul" \
  --uri="https://YOUR_CLOUD_RUN_URL/run/ingest/weekly" \
  --http-method=GET \
  --attempt-deadline=1200s
```

**참고**: `/weekly` 엔드포인트는 6개월치 메일을 처리하므로 10분 이상 소요될 수 있습니다. Cloud Run의 `--timeout`과 Cloud Scheduler의 `--attempt-deadline`을 충분히 늘려주세요 (최대 60분).

---

## 📚 개발 참고사항

### 디렉토리 구조

```
src/main/java/com/ramsbaby/preply/
├── api/
│   └── RunController.java             # REST 엔드포인트
├── component/
│   ├── DailySummaryJob.java           # 일일 요약 메일 발송
│   ├── MailIngestionService.java      # 메일 Ingest 로직
│   ├── PreplyRateCacheLoader.java     # Gmail 메일 파싱
│   └── SupabaseMailRepository.java    # Supabase DB 연동
├── config/
│   ├── AppProps.java                  # 환경변수 매핑
│   └── SchedulingConfig.java          # 스케줄러 설정
├── dto/
│   ├── FetchResult.java               # 메일 fetch 결과
│   ├── ParsedMail.java                # 파싱된 메일 DTO
│   └── Money.java                     # 금액+통화 DTO
└── port/
    ├── RateLoaderPort.java            # 메일 로딩 인터페이스
    └── MailCachePort.java             # DB 캐싱 인터페이스
```

### Supabase 스키마

`supabase/schema.sql` 참고:

```sql
create table if not exists preply_mail (
  message_id text,
  student_full_name text not null,
  student_normalized text,
  amount numeric not null,
  currency text,
  received_at timestamptz not null,
  subject text,
  snippet text,
  kind text check (kind in ('booking','cancellation_compensation')),
  lesson_date date not null,
  primary key (student_full_name, lesson_date)
);
```

**복합 키 설계**:
- 동일 학생이 여러 날짜에 수업을 가질 수 있으므로 `(student_full_name, lesson_date)` 조합으로 PK 설정
- 같은 날짜에 동일 학생의 중복 메일이 ingest되면 최신 데이터로 upsert

### 환율 조회 (페일오버)

`FxRateService`는 다음 순서로 환율을 조회합니다:
1. `exchangerate.host` (primary)
2. `open.er-api.com` (fallback)

실패 시 에러 메시지가 메일에 포함되며, KRW 합계는 계산되지 않습니다.

### 보안

- **자격증명 파일**: `.gitignore`에 자동 제외됨 (`.json`, `.p12`, `.pem`)
- **토큰 보호**: `APP_RUN_TOKEN` 설정 시 `/run` 엔드포인트가 `X-Run-Token` 헤더 검증
- **환경변수 관리**: GCP Secret Manager 사용 권장

---

## 📄 라이선스

MIT License (자세한 내용은 [LICENSE](LICENSE) 참고)

---

## 🙏 기여

버그 리포트 및 기능 제안은 [Issues](https://github.com/YOUR_REPO/issues)에서 환영합니다.
