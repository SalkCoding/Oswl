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

기동 시 누락 변수 등은 로그의 **`OSWL STARTUP WARNINGS`** 블록에 한 번에 출력됩니다. **`prod`에서 `OSWL_ENCRYPTION_KEY`가 없으면 기동 실패** — 출시 전 안정적인 키를 설정하세요. (`local` 프로파일은 개발 전용으로 임시 키를 사용할 수 있습니다.)

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

### 성능 튜닝 (v1.0.4)

기본값은 운영 환경에서 안전합니다. 이유가 있을 때만 오버라이드하세요.

| 변수 | 기본값 | 용도 |
|---|---|---|
| `OSWL_DEPSDEV_CONNECT_TIMEOUT_MS` / `OSWL_DEPSDEV_READ_TIMEOUT_MS` | `5000` / `10000` | deps.dev HTTP 타임아웃(이전에는 멈춘 호출이 전체 스캔을 정지시킬 수 있었음) |
| `OSWL_DEPSDEV_MAX_CONCURRENT` | `24` | deps.dev 동시 요청 상한; HTTP 429 시 백오프 후 1회 재시도 |
| `OSWL_OSV_CONNECT_TIMEOUT_MS` / `OSWL_OSV_READ_TIMEOUT_MS` | `5000` / `30000` | OSV HTTP 타임아웃(느린 연결에서 1,000개 항목 batch query는 정당히 시간이 걸림) |
| `OSWL_VERSION_META_TTL_SEC` | `86400` | 캐시 히트 라이브러리의 deps.dev 버전 메타데이터 TTL |
| `OSWL_CLONE_SPARSE_ENABLED` | `true` | Quick Import 클론은 blobless + sparse-checked-out; partial-clone 미지원 git 서버는 자동으로 전체 shallow 클론으로 폴백 |
| `OSWL_AI_STREAMING_ENABLED` | `true` | 보안 태세/트렌드/버전 차이 등 자유 형식 AI 호출을 SSE로 스트리밍해 라이브 프리뷰 제공; 스트리밍을 거부하는 엔드포인트는 자동 폴백 |
| `OSWL_AI_MAX_PARALLEL_CALLS` | `3` | 동시 AI 보강 호출 상한; 로컬 llama-server는 `--parallel`이 이 값까지 유리 |
| `OSWL_ANTHROPIC_PROMPT_CACHING_ENABLED` | `true` | Anthropic 시스템 프롬프트를 단기 캐시 브레이크포인트로 표시 |

### 7.1 폐쇄망 / 오프라인 스냅샷 (v1.0.4)

`OSWL_AIRGAPPED_ENABLED=true`로 설정하면 취약점·위협 인텔 조회(OSV, deps.dev, EPSS, CISA KEV)를 실시간 외부 API 대신 반입된 오프라인 스냅샷에서 처리합니다. 보강을 위한 아웃바운드 HTTP는 시도되지 않습니다.

| 단계 | 조치 |
|------|------|
| 1. 번들 제작 | 인터넷에 연결된 머신에서 `oswl-vdb` 빌더를 실행합니다. 래퍼 스크립트: `scripts/oswl-vdb/oswl-vdb.sh`(Linux/macOS) 또는 `scripts/oswl-vdb/oswl-vdb.ps1`(Windows). 둘 다 `./gradlew vdbBuild --args="..."`를 호출합니다. |
| 2. 번들 대상 선정 | 타깃 인스턴스가 실제로 스캔한 컴포넌트를 `GET /api/admin/snapshot/wanted-list`(SYSTEM_ADMIN)로 낸 뒤 `build --wanted wanted-list.jsonl`에 넘기세요. 빌더는 전체 업스트림 미러 대신 실제 사용 컴포넌트만 가져옵니다. |
| 3. 번들 반입 | `POST /api/admin/snapshot/import?mode=replace|merge`(multipart `.zip`). 큰 번들은 `OSWL_AIRGAPPED_IMPORT_DIR` 화이트리스트 디렉터리를 설정한 뒤 `POST /api/admin/snapshot/import-from-path`에 `{"path":"bundle.zip","mode":"merge"}`로 요청하세요. |
| 4. 모델 배치 (내장 AI 사용 시) | 폐쇄망 호스트는 자동 다운로드를 비활성화합니다. `.gguf` 파일을 `embedded-ai/`에 직접 넣거나 내부 미러를 운영하세요(§8 참고). |

`oswl-vdb build` 옵션(`VdbBuilderCli` 참고):
- `--sources osv,epss,kev,depsdev`(기본값 전체).
- `--mode delta --since previous.zip`은 추가/변경 키만 쓰고 `"_deleted":true` 마커를 함께 씁니다.
- `--offline-sources <dir>`은 이전 온라인 실행으로 채워진 캐시 디렉터리에서 네트워크 없이 빌드합니다(`osv`/`epss`/`kev`만 해당; deps.dev는 벌크 덤프가 없어 걸립니다).
- `verify <bundle.zip>`과 `inspect <bundle.zip>`은 체크섬과 메타데이터를 확인합니다.

반입 의미:
- `replace`(기본값)은 해당 소스의 기존 데이터를 지우고 번들을 씁니다.
- `merge`는 `(source, entry_key)` 기준으로 upsert하고 `"_deleted":true` 라인은 삭제로 처리합니다.
- v2 번들은 `meta.json`에 기록된 파일별 SHA-256 체크섬을 검증하며, 불일치 시 전체 번들을 거부하고 기존 스토어를 변경하지 않습니다.

정의 최신성(E7): `OSWL_AIRGAPPED_STALENESS_WARN_DAYS`(기본값 `7`)와 `OSWL_AIRGAPPED_STALENESS_CRITICAL_DAYS`(기본값 `30`)는 반입된 스냅샷의 소스별 `sourceAsOf` 날짜 중 가장 오래된 값을 기준으로 관리 UI 배지를 결정합니다.

번들이 50MB를 초과하면 `OSWL_MULTIPART_MAX_FILE_SIZE` / `OSWL_MULTIPART_MAX_REQUEST_SIZE`(기본값 각 `50MB`)를 조정해야 할 수 있습니다.

## 8. 내장 AI 모델 (선택, 온프레미스)

**내장 AI**(설정 → AI → 로컬)를 클라우드 프로바이더 대신 또는 함께 쓸 계획일 때만 해당됩니다.

| 확인 | 조치 |
|------|------|
| 서버 바이너리 | [llama.cpp releases](https://github.com/ggml-org/llama.cpp/releases)에서 플랫폼에 맞는 `llama-server(.exe)`를 받아 `embedded-ai/`(또는 그 하위 `bin/`, 혹은 `PATH`)에 배치 — 유일한 수동 단계입니다 |
| 모델 | 별도 조치 불필요 — 신규 설치에서 **시작**을 누르면 Apache 2.0 라이선스인 Qwen3-1.7B 모델(~1.2GB)이 자동으로 다운로드됩니다(SHA256 검증, UI에 진행률 표시) |
| 폐쇄망 환경 | 자동 다운로드는 최초 1회 아웃바운드 인터넷 접근이 필요합니다. 인터넷이 없다면 시작을 누르기 전 직접 받은 `.gguf` 파일을 `embedded-ai/`에 미리 넣어두세요 |
| 커스텀 모델 | OsWL이 번들/자동 다운로드하는 것은 Qwen3-1.7B뿐입니다. 다른 `.gguf`(다른 크기나 라이선스)를 쓰고 싶다면 해당 모델의 라이선스를 직접 확인한 뒤 `embedded-ai/`에 넣으세요 — [내장 AI](Embedded-AI.md) 참고 |
| 디렉터리 | 기본값은 JVM이 시작되는 작업 디렉터리 기준 `./embedded-ai` — 다른 경로를 쓰려면 `OSWL_EMBEDDED_AI_DIR` 설정 |

Gradle 태스크나 별도 스크립트가 필요 없습니다 — 다운로드는 시작 버튼을 처음 누를 때 앱
자체에서 실행되므로, 단순히 `java -jar app.jar`로 배포해도 동작합니다.

### 내장 AI 튜닝 (B1 / v1.0.4)

기본값은 운영 환경에서 안전합니다. 측정된 이유가 있을 때만 오버라이드하세요.

| 변수 | 기본값 | 용도 |
|---|---|---|
| `OSWL_EMBEDDED_AI_CONTEXT` | `8192` | 전체 컨텍스트 크기(`-c`). `--parallel` 사용 시 슬롯 간에 나뉘며, 슬롯 컨텍스트가 2048 아래로 떨어지면 경고 로그가 출력됩니다. |
| `OSWL_EMBEDDED_AI_GPU_LAYERS` | `-1` | `-ngl`: `-1`은 빌드가 지원하는 만큼 오프로드(`999` 전달), `0`은 CPU 전용, 양수는 명시적 레이어 수 |
| `OSWL_EMBEDDED_AI_THREADS` | `0` | `-t`: `0`은 llama.cpp 자동 감지, 양수는 스레드 수 고정 |
| `OSWL_EMBEDDED_AI_PARALLEL` | `4` | `--parallel N --cont-batching` 활성화; 1보다 크면 동시 AI 호출이 직렬화되지 않습니다. |
| `OSWL_EMBEDDED_AI_FLASH_ATTN` | `true` | `-fa`(flash attention) 추가 |
| `OSWL_EMBEDDED_AI_CACHE_REUSE` | `256` | `--cache-reuse` 토큰 수; `0` 이하면 비활성화 |
| `OSWL_EMBEDDED_AI_EXTRA_ARGS` | (비어 있음) | `llama-server` CLI 인자를 공백으로 구분해 그대로 덧붙임(관리자 전용 설정, 요청 입력 아님) |
| `OSWL_EMBEDDED_AI_STARTUP_TIMEOUT_SEC` | `120` | `/health` 응답을 기다리는 시간(초). 시간 내 실패 시 CPU 전용 재시도 또는 다음 모델 후보로 넘어갑니다. |
| `OSWL_EMBEDDED_DEFAULT_MODEL_URL` / `SHA256` / `SIZE_BYTES` | 업스트림 Hugging Face `ggml-org/Qwen3-1.7B-GGUF` | 기본 Qwen3-1.7B 다운로드용 매칭 세트; 자체 호스팅 미러 사용 시 셋 모두 오버라이드(바이트 단위로 동일한 재호스팅이면 URL만 변경) |
| `OSWL_EMBEDDED_FALLBACK_MODEL_URL` | Hugging Face | 기본 URL 실패 시 1회 재시도; primary와 같거나 비워두면 재시도 비활성화 |
| `OSWL_EMBEDDED_AUTO_DOWNLOAD` | `true` | 부팅 시 기본 모델을 백그라운드로 미리 다운로드(다운로드만, 사이드카는 시작 안 함). **`OSWL_AIRGAPPED_ENABLED=true`이면 무시됩니다**. |

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

### v1.0.5: Spring Session / ShedLock 테이블 (옵트인, 로드맵 S1)

**다중 인스턴스** 배포로 전환할 때만 필요합니다(§12 참고). `spring_session`, `spring_session_attributes`, `shedlock`이 추가됩니다. Flyway 사용자는 `db/migration/V10__spring_session_and_shedlock.sql`에서, 수동 스크립트 사용자는 `db/spring_session_and_shedlock.sql`을 실행하면 됩니다. 단일 인스턴스 배포라면 완전히 건너뛰어도 됩니다 — `OSWL_SESSION_STORE_TYPE=jdbc` 및/또는 `OSWL_SCHEDULER_LOCK_ENABLED=true`를 설정하기 전까지는 이 테이블을 아무도 참조하지 않습니다.

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

## 12. 다중 인스턴스 배포 (수평 확장 / HA, 로드맵 S1)

OsWL은 기본적으로 **단일 인스턴스**로 동작합니다 — 인메모리 HTTP 세션과 인스턴스별 `@Scheduled` 작업이죠. 컨테이너/프로세스 1개일 때는 이걸로 충분하지만, 로드밸런서 뒤에 두 번째 인스턴스를 두면 문제가 생깁니다: 사용자 세션이 로그인했던 인스턴스에 고정되고, 야간 모니터링/유예 만료/휴지통 정리 작업이 클러스터당 1회가 아니라 **인스턴스마다** 실행됩니다. 이 절은 동일한 PostgreSQL DB를 바라보는 **인스턴스 2대 이상**을 배포할 때만 해당됩니다.

**1. 스키마부터 적용하세요.** 아래 기능을 켠 인스턴스를 기동하기 전에 `spring_session`, `spring_session_attributes`, `shedlock`이 존재하는지 먼저 확인하세요(§9, "v1.0.5: Spring Session / ShedLock 테이블"). 테이블이 없는 상태에서 아래 환경변수부터 배포하면 첫 요청/스케줄 시점에 모든 인스턴스가 죽습니다.

**2. 환경 변수:**

| 변수 | 용도 |
|------|------|
| `OSWL_SESSION_STORE_TYPE=jdbc` | HTTP 세션을 Tomcat 인메모리 저장에서 PostgreSQL(`spring_session`)로 옮깁니다. 로그인 상태와 단일세션 강제(`maximumSessions(1)`)가 인스턴스별이 아니라 클러스터 전체에서 동작합니다. |
| `OSWL_SCHEDULER_LOCK_ENABLED=true` | 스케줄 작업 3종(`ContinuousMonitoringScheduler`, `DeferExpiryScheduler`, `TrashCleanupScheduler`)을 클러스터 전역 락(ShedLock, `shedlock` 테이블 기반)으로 감싸서 사이클당 한 인스턴스에서만 실행되도록 합니다. |

실제 다중 인스턴스 배포에서는 둘 다 함께 설정하세요 — 하나만 켜면 나머지 한쪽 공백이 그대로 남습니다.

**3. 로드밸런서:** nginx, ALB 등 일반적인 L7 LB면 됩니다 — `OSWL_SESSION_STORE_TYPE=jdbc`를 설정하면 세션 상태가 인스턴스 메모리가 아니라 PostgreSQL에 중앙화되므로 **스티키 세션이 필요 없습니다.**

**4. 단, 스캔 진행률 폴링은 예외입니다.** Quick Import/스캔 보강 중 표시되는 실시간 진행률(`EnrichmentProgressHolder`, `ScanStatusEmitterRegistry`)은 여전히 인스턴스별 인메모리 상태이며 DB에 저장되지 않습니다. 권장 방법: 로드밸런서 라우팅을 **활성 스캔이 진행되는 동안만** 스티키하게(예: 세션 기준 쿠키 어피니티) 설정해, 진행률 폴링 요청이 실제로 스캔을 실행 중인 인스턴스로 되돌아가도록 하세요. 스캔 진행률을 DB로 옮기고 UI를 순수 폴링 방식으로 바꾸는 대안은 더 큰 변경이라 별도로 추적하며, 지금은 스티키 라우팅이 실용적인 기본값입니다.

**5. 롤링 배포 순서:**
   1. 대기 중인 DB 마이그레이션을 먼저 적용하세요(구버전 앱 코드가 새 스키마를 견뎌야 하므로 — `db/migration`은 이 원칙대로 추가 전용으로 작성됩니다).
   2. 인스턴스를 한 번에 하나씩 순차적으로 교체하고(전체 동시 교체 금지), 다음으로 넘어가기 전에 새 인스턴스가 준비성 검사를 통과할 때까지 기다리세요.
   3. `OSWL_SESSION_STORE_TYPE=jdbc`를 설정하면 세션이 인스턴스 메모리가 아니라 PostgreSQL에 있으므로, 롤링 재시작으로 사용자가 로그아웃되지 않습니다.

**6. 정상 동작 확인:**
   - 인스턴스 A에 로그인한 뒤, LB가 인스턴스 B로 라우팅하는 후속 요청을 보내도 인증이 유지되는지(`/login`으로 리다이렉트되지 않는지) 확인합니다.
   - 인스턴스 A를 내려도 세션(및 단일세션 강제)이 인스턴스 B에서 계속 동작하는지 확인합니다.
   - 야간 작업 실행 후 두 인스턴스의 로그를 확인해, 해당 작업의 로그 라인이 두 곳이 아니라 정확히 한 인스턴스에서만 나타나는지 확인합니다.

---

**로컬 개발:** `SPRING_PROFILES_ACTIVE=local`, `.env.example` → `.env`, `OSWL_ENCRYPTION_KEY` 설정, `./gradlew bootRun`. H2, H2 콘솔, Swagger, `GET /data/test`는 이 프로파일에서만.
