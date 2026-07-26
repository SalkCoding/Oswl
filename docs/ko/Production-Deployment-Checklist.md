# 운영 배포 체크리스트

OsWL을 인터넷에 공개하기 전에 확인할 한 페이지 목록입니다. **`prod`를 `local` 기본값**(H2, Swagger, `/data/**`, 커밋된 암호화 키)으로 실행하지 마세요.

## 1. 프로파일 및 빌드

| 확인 | 조치 |
|------|------|
| 프로파일 | `SPRING_PROFILES_ACTIVE=prod` 설정 |
| JAR | `./gradlew bootJar verifyProdJar`로 빌드 — JAR에 `TestDataController`가 **없어야** 함 |
| 로컬 전용 코드 | `src/local/java`는 `bootRun`/개발용이며 `bootJar`에 포함되지 않음 |

## 2. 필수 환경 변수

| 변수 | 용도 |
|------|------|
| `DB_URL` | JDBC URL (예: `jdbc:postgresql://db:5432/oswl`) |
| `DB_USERNAME` | DB 사용자 |
| `DB_PASSWORD` | DB 비밀번호 |
| `OSWL_ENCRYPTION_KEY` | 인스턴스 암호화 키 (`openssl rand -base64 32`) |

`.env.prod.example` → `.env.prod` 복사 후 모든 값 입력. `application-prod.yaml`에는 DB·암호화 **기본값 없음**.

기동 시 누락 변수 등은 로그의 **`OSWL STARTUP WARNINGS`** 블록에 한 번에 출력됩니다. **`prod`에서 `OSWL_ENCRYPTION_KEY`가 없으면 기동 실패** — 출시 전 안정적인 키를 설정하세요.

## 3. 네트워크 바인딩

| 확인 | 조치 |
|------|------|
| 기본 바인딩 | `SERVER_ADDRESS=127.0.0.1` (`application-prod.yaml`) |
| 공개 접근 | **nginx / Caddy / Traefik**(또는 클라우드 LB) 앞단, TLS 종료 |
| 직접 `0.0.0.0` | JVM HTTP 스택 노출을 감수할 때만; 방화벽·위험 문서화 |

`docker-compose.prod.yml`은 기본 **`127.0.0.1:8080:8080`** 매핑.

프록시가 `X-Forwarded-Proto`를 내면 HSTS·보안 쿠키를 위해 `server.forward-headers-strategy=framework` 사용 (`application.yaml` 기본).

## 4. Docker Compose (운영)

```bash
cp .env.prod.example .env.prod
# DB_*, OSWL_ENCRYPTION_KEY, SMTP_* 편집
docker compose -f docker-compose.prod.yml up -d --build
```

로그 확인: 누락 env 배너 없음, PostgreSQL 연결, H2/Swagger URL 없음.

## 5. 로깅 및 관측

| 확인 | 조치 |
|------|------|
| 로그 레벨 | `prod`: `com.salkcoding.oswl` **INFO**만; AI/클라이언트 DEBUG 없음 |
| AI 발췌 | `oswl.ai.debug.log-prompt-excerpt` / `log-response-excerpt` 운영 기본 **false** |
| Actuator | **`health`, `info`, `prometheus`** 노출 (v1.0.4), 그 외 비활성 (`enabled-by-default: false`) |
| 메트릭 스크랩 | Prometheus를 `/actuator/prometheus`로 지정 — 스크래퍼도 관리자 인증 필요 |
| Actuator 인증 | **SYSTEM_ADMIN** 세션 필요 (공개 아님) |

## 6. 운영에서 활성화되는 보안

- Springdoc / Swagger UI: **비활성**
- H2 콘솔, `/data/**`: **prod JAR에 없음** (`local` + `src/local/java`만)
- 보안 헤더 + HSTS (HTTPS 뒤): `application-prod.yaml` `oswl.security.headers`
- 신뢰 기기 쿠키: 운영에서 `Secure`

## 7. 선택 시크릿

| 변수 | 용도 |
|------|------|
| `OSWL_TRUSTED_DEVICE_HMAC_KEY` | `OSWL_TD` 쿠키 전용 HMAC (`OSWL_ENCRYPTION_KEY`와 분리 권장) |
| `OSWL_OIDC_CLIENT_ID` / `OSWL_OIDC_CLIENT_SECRET` / `OSWL_OIDC_ISSUER_URI` | **v1.0.4** — OIDC 싱글 사인온. `application-prod.yaml`의 `spring.security.oauth2.client` 블록 주석도 해제해야 하며, 프로바이더가 등록된 경우에만 로그인 화면에 SSO 버튼이 표시됩니다. |

### v1.0.4 옵트인 기능

모두 기본 **비활성**입니다 — 필요할 때 명시적으로 켜세요.

| 변수 | 기본값 | 활성화 시 동작 |
|---|---|---|
| `OSWL_FLYWAY_ENABLED` | `false` | `baseline-on-migrate` 기반 버전 마이그레이션. 먼저 전체 베이스라인을 생성해야 합니다 |
| `OSWL_AIRGAPPED_ENABLED` | `false` | 취약점·위협 인텔 조회를 반입된 오프라인 스냅샷에서 처리, 외부 HTTP 없음 |
| `OSWL_GATE_*` | [v1.0.4 새로운 기능](Whats-New-v1.0.4.md) 참고 | `POST /api/scan/gate`의 기본 임계값 |

예외는 연속 모니터링입니다. `OSWL_MONITORING_ENABLED`가 기본 **`true`**(매일 03:00 OSV 재조회, `OSWL_MONITORING_CRON`)이며 프로젝트 멤버에게 메일을 발송하므로, 최초 기동 전에 SMTP 설정을 확인하거나 `false`로 끄세요.

## 8. 내장 AI 모델 (선택, 온프레미스)

**내장 AI**(설정 → AI → 로컬)를 클라우드 프로바이더 대신 또는 함께 쓸 계획일 때만 해당됩니다.

| 확인 | 조치 |
|------|------|
| 서버 바이너리 | [llama.cpp releases](https://github.com/ggml-org/llama.cpp/releases)에서 플랫폼에 맞는 `llama-server(.exe)`를 받아 `embedded-ai/`(또는 그 하위 `bin/`, 혹은 `PATH`)에 배치 — 유일한 수동 단계입니다 |
| 모델 | 별도 조치 불필요 — 신규 설치에서 **시작**을 누르면 Apache 2.0 라이선스인 Qwen3-1.7B 모델(~1.2GB)이 자동으로 다운로드됩니다(SHA256 검증, UI에 진행률 표시) |
| 폐쇄망 환경 | 자동 다운로드는 최초 1회 아웃바운드 인터넷 접근이 필요합니다. 인터넷이 없다면 시작을 누르기 전 `.gguf` 파일(예: 직접 받은 Gemma — 아래 참고)을 `embedded-ai/`에 미리 넣어두세요 |
| Gemma 폴백 | 자동으로 받아지지 않음 — [Gemma Terms of Use](https://ai.google.dev/gemma/terms)라는 비표준 라이선스라 OsWL이 대신 재배포하지 않기 때문. 저사양용 폴백이 필요하면 직접 다운로드 — [내장 AI](Embedded-AI.md) 참고 |
| 디렉터리 | 기본값은 JVM이 시작되는 작업 디렉터리 기준 `./embedded-ai` — 다른 경로를 쓰려면 `OSWL_EMBEDDED_AI_DIR` 설정 |

Gradle 태스크나 별도 스크립트가 필요 없습니다 — 다운로드는 시작 버튼을 처음 누를 때 앱
자체에서 실행되므로, 단순히 `java -jar app.jar`로 배포해도 동작합니다.

## 9. 데이터베이스 스키마 (업그레이드)

OsWL **`prod`는 Hibernate `ddl-auto=validate`** — 기동 시 PostgreSQL을 자동 변경하지 않습니다.

| 프로파일 | 스키마 관리 |
|----------|-------------|
| `local` | `ddl-auto: update` — H2가 JPA 엔티티를 따름 |
| `prod` | `ddl-auto: validate` — 업그레이드 시 SQL 스크립트 수동 실행 |

수동 스크립트: `src/main/resources/db/`

| 파일 | 실행 시점 |
|------|-----------|
| `project_members.sql` | 프로젝트 ACL 최초 배포(테이블 없을 때) |
| `instance_setup_lock.sql` | 설정 잠금 기능 이후 최초 배포 |
| `ai_enhancement.sql` | AI 설정 컬럼/`ai_daily_usage` 이전 레거시 |
| `schema_cleanup.sql` | 미사용 테이블·컬럼 제거 릴리스로 업그레이드 시 **1회** (`ai_feedback`, `external_api_settings`, `projects.version` 등) |

마이그레이션 후 앱 재시작, `validate` 통과 확인.

### Flyway (v1.0.4, 옵트인)

`OSWL_FLYWAY_ENABLED=true`로 설정하면 수동 스크립트 대신 Flyway가 스키마를 관리합니다. `baseline-on-migrate`가 켜져 있어 데이터가 있는 기존 DB도 거부되지 않고 베이스라인 처리되지만, **켜기 전에** 현재 스키마와 일치하는 전체 베이스라인 마이그레이션을 만들어 두어야 합니다. 기본값 `false`에서는 아무것도 달라지지 않습니다.

### v1.0.4 신규 컬럼

`libraries.malicious`, `libraries.typosquat_risk`가 추가되며 둘 다 `NOT NULL DEFAULT false`입니다. 기본값 덕분에 데이터가 있는 테이블에도 추가할 수 있지만, `prod`(`ddl-auto: validate`)에서는 직접 적용해야 합니다. 또한 컴포넌트 상세에 표시되는 업스트림 프로젝트 메타데이터용으로 nullable한 `libraries` 컬럼 3개(`description`, `homepage`, `source_repo_url`)도 추가됩니다 — `validate`는 nullable 여부와 무관하게 매핑된 모든 컬럼의 존재를 확인하므로, 이 컬럼들도 동일하게 수동으로 추가해야 합니다.

```sql
ALTER TABLE libraries ADD COLUMN IF NOT EXISTS malicious        boolean NOT NULL DEFAULT false;
ALTER TABLE libraries ADD COLUMN IF NOT EXISTS typosquat_risk   boolean NOT NULL DEFAULT false;
ALTER TABLE libraries ADD COLUMN IF NOT EXISTS description      text;
ALTER TABLE libraries ADD COLUMN IF NOT EXISTS homepage         varchar(500);
ALTER TABLE libraries ADD COLUMN IF NOT EXISTS source_repo_url  varchar(500);
ALTER TABLE scan_results ADD COLUMN IF NOT EXISTS ai_locale varchar(16);
```

（Flyway 사용자: 새로운 `libraries` 컬럼 3개는 `V3__component_metadata.sql`에서 처리됩니다. [데이터베이스 스키마](Database-Schema.md) 참고.）

## 10. 배포 후 스모크 테스트

1. HTTPS 리버스 프록시로만 UI 접근.
2. 설정/로그인 및 2FA(활성 시) 완료.
3. 프로젝트·VCS 연결 생성 후 재기동 — 토큰 복호화 확인(`OSWL_ENCRYPTION_KEY` 안정성).
4. 프로젝트 API 키로 `POST /api/scan` ([스캔 API 보안](Scan-Api-Security.md)).
5. 멤버 프로젝트 접근, 타 사용자 프로젝트 ID는 forbidden 확인.
6. 감사 로그에서 인증 실패 검토.

## 11. 운영

- PostgreSQL 백업, `OSWL_ENCRYPTION_KEY`는 시크릿 매니저에 보관(분실 시 VCS 토큰 복호 불가).
- 유출 시 API 키·SMTP 자격 증명 교체.
- `local`로 돌리면 안 되는 이미지에 `SPRING_PROFILES_ACTIVE=local` 넣지 않기.

---

**로컬 개발:** `SPRING_PROFILES_ACTIVE=local`, `.env.example` → `.env`, `OSWL_ENCRYPTION_KEY` 설정, `./gradlew bootRun`. H2, H2 콘솔, Swagger, `GET /data/test`는 이 프로파일에서만.
