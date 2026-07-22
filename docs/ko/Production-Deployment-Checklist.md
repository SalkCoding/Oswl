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
| Actuator | **`/actuator/health`만** 노출 |
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

자세한 내용: [데이터베이스 스키마](Database-Schema.md)

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
