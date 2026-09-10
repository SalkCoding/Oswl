# 운영 배포 체크리스트

OsWL을 인터넷에 공개하기 전에 확인할 한 페이지 목록입니다. **`prod`를 `local` 기본값**(H2, Swagger, `/data/**`, 커밋된 암호화 키)으로 실행하지 마세요.

## 1. 프로필 및 빌드

| 확인 | 조치 |
|------|------|
| 프로필 | `SPRING_PROFILES_ACTIVE=prod` 설정 |
| JAR | `./gradlew bootJar verifyProdJar`로 빌드 — JAR에 `TestDataController`가 **없어야** 함 |
| 로컬 전용 코드 | `src/local/java`는 `bootRun`/개발용이며 `bootJar`에 포함되지 않음 |

## 2. 필수 환경 변수

| 변수 | 용도 |
|------|------|
| `DB_URL` | JDBC URL (예: `jdbc:postgresql://db:5432/oswl`) |
| `DB_USERNAME` | DB 사용자 |
| `DB_PASSWORD` | DB 비밀번호 |
| `OSWL_ENCRYPTION_KEY` | 인스턴스 암호화 키 (`openssl rand -base64 32`) |

`deploy/docker/.env.prod.example` → `.env.prod` 복사 후 모든 값 입력. `application-prod.yaml`에는 DB·암호화 **기본값 없음**.

시작 후 설정 경고는 `OSWL STARTUP WARNINGS` 로그 블록에 모아서 출력됩니다. 운영 암호화 키 누락이나 잘못된 DB 설정 등은 이 블록이 출력되기 전에 시작 실패를 일으킬 수 있습니다. 운영에서는 고정된 `OSWL_ENCRYPTION_KEY`를 유지하세요. `local` YAML의 고정 대체 키는 개발 전용이며 운영에서 사용하면 안 됩니다.

## 3. 네트워크 바인딩

호스트에서 JVM을 직접 실행하면 `application-prod.yaml`의 기본값인 `SERVER_ADDRESS=127.0.0.1`로 바인딩하며, 같은 호스트의 리버스 프록시가 연결할 수 있습니다. **Docker Compose에서는 컨테이너 내부의 `SERVER_ADDRESS=0.0.0.0`**을 사용해야 Docker가 애플리케이션으로 트래픽을 전달할 수 있습니다. 호스트 공개 범위는 별도입니다. `deploy/docker/compose.prod.yml`은 호스트 포트를 **`127.0.0.1:8080:8080`**에만 연결합니다. 운영 예제는 이 컨테이너 설정을 사용하며, 기존 `.env.prod`도 업그레이드 시 확인해야 합니다.

리버스 프록시에서 TLS를 종료하세요. 제공된 호스트 루프백 포트 매핑을 사용할 때는 Docker 호스트에서 프록시를 실행합니다. 프록시도 컨테이너라면 공유 Docker 네트워크의 서비스 주소로 연결하세요. 전달 헤더는 신뢰하는 프록시에서 온 것만 수용해야 합니다.

## 4. Docker Compose (운영)

저장소 루트에서 실행합니다. 기존 `.env.prod` 값은 유지하고 새 설치에서만 템플릿을 복사합니다. 두 Compose 파일의 기본 프로젝트명은 `oswl`입니다. 기존 설치가 다른 프로젝트명을 사용했다면 `-p YOUR_EXISTING_PROJECT` 또는 `COMPOSE_PROJECT_NAME`으로 그 이름을 유지해야 기존 볼륨에 연결됩니다. [배포 파일 안내](../../deploy/README.md)를 참고하세요.

```bash
cp deploy/docker/.env.prod.example .env.prod
# DB_*, OSWL_ENCRYPTION_KEY, SMTP_* 편집
docker compose --env-file .env.prod -f deploy/docker/compose.prod.yml up -d --build
```

Compose는 `--env-file`로 `.env.prod`를 읽습니다. `java -jar`나 `bootRun`으로 직접 실행할 때는 이 파일을 자동으로 읽지 않으므로, 환경 변수를 내보내거나 서비스 관리 도구에 등록하세요. 운영 환경의 첫 시작 전에 DB 스키마를 준비해야 합니다(§9 참고).

로그 확인: 누락 env 배너 없음, PostgreSQL 연결, H2/Swagger URL 없음.

`deploy/docker/compose.prod.yml`은 컨테이너 자체의 stdout/stderr(docker `json-file` 드라이버, 100MB × 10개)와 앱의 자체 회전 파일 로그(`oswl-logs-prod` 볼륨에 마운트) 둘 다 상한을 둡니다 — 후자는 §5 참고.

## 5. 로깅 및 관측

| 확인 | 조치 |
|------|------|
| 로그 레벨 | `prod`: `com.salkcoding.oswl` **INFO**만; AI/클라이언트 DEBUG 없음 |
| AI 발췌 | `oswl.ai.debug.log-prompt-excerpt` / `log-response-excerpt` 운영 기본 **false** |
| Actuator | **`health`, `info`, `prometheus`** 노출 (v1.0.4), 그 외 비활성 (`enabled-by-default: false`) |
| 메트릭 스크랩 | Prometheus를 `/actuator/prometheus`로 지정 — 스크래퍼도 관리자 인증 필요 |
| Actuator 인증 | **SYSTEM_ADMIN** 세션 필요 (공개 아님) |

### 로그 로테이션 및 요청 상관관계

`local`/`test`는  콘솔 전용입니다. `prod`에서는 `logback-spring.xml`이 회전 파일 로그를 추가로 기록합니다:

| 변수 | 기본값 | 용도 |
|------|--------|------|
| `OSWL_LOG_DIR` | `./logs` (도커: `/var/log/oswl`, §4 참고) | `oswl.log` 저장 디렉터리. 100MB 또는 하루 단위로 회전, 최대 30개 파일 보관, 전체 5GB 상한. |
| `OSWL_LOG_JSON` | `false` | `true`로 설정하면 파일(콘솔 아님)이 한 줄당 JSON 오브젝트 하나로 바뀝니다 — 로그 수집기를 여기에 연결해 SIEM으로 보내세요. |

모든 요청에는 `requestId`가 찍히고(응답 헤더 `X-Request-Id`로도 반환), 인증된 요청이라면 `userId`도 함께 찍힙니다 — 둘 다 MDC를 통해 해당 요청의 모든 로그 라인에 나타나므로(평문 모드는 `[req=...] [user=...]`, JSON 모드는 최상위 필드), 특정 요청을 언급하는 지원 티켓을 타임스탬프로 grep하지 않고도 로그 전체에서 추적할 수 있습니다.

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
| `OSWL_FLYWAY_ENABLED` | `false` | 제공되는 V1 및 이후 마이그레이션 실행. 기존 스키마는 먼저 검토 |
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

이 모드는 지원되는 취약점·위협 정보 피드를 스냅샷으로 전환하는 기능이며 네트워크 방화벽은 아닙니다. 폐쇄망에서는 VCS, SMTP, 웹훅, 외부 AI 프로바이더도 별도로 설정하세요.

`OSWL_AIRGAPPED_ENABLED=true`로 설정하면 취약점·위협 인텔 조회(OSV, deps.dev, EPSS, CISA KEV)를 실시간 외부 API 대신 반입된 오프라인 스냅샷에서 처리합니다. 보강을 위한 아웃바운드 HTTP는 시도되지 않습니다.

| 단계 | 조치 |
|------|------|
| 1. 번들 제작 | 인터넷에 연결된 머신에서 `oswl-vdb` 빌더를 실행합니다. 래퍼 스크립트: `scripts/oswl-vdb/oswl-vdb.sh`(Linux/macOS) 또는 `scripts/oswl-vdb/oswl-vdb.ps1`(Windows). 둘 다 `./gradlew vdbBuild --args="..."`를 호출합니다. |
| 2. 번들 대상 선정 | 타깃 인스턴스가 실제로 스캔한 컴포넌트를 `GET /api/admin/snapshot/wanted-list`(SYSTEM_ADMIN)로 낸 뒤 `build --wanted wanted-list.jsonl`에 넘기세요. 빌더는 전체 업스트림 미러 대신 실제 사용 컴포넌트만 가져옵니다. |
| 3. 번들 반입 | `POST /api/admin/snapshot/import?mode=replace|merge`(multipart `.zip`). 큰 번들은 `OSWL_AIRGAPPED_IMPORT_DIR` 화이트리스트 디렉터리를 설정한 뒤 `POST /api/admin/snapshot/import-from-path`에 `{"path":"bundle.zip","mode":"merge"}`로 요청하세요. |
| 4. 모델 배치(내장 AI 사용 시) | 오프라인 서버를 시작하기 전에 `embedded-ai/llama/`에 실행 파일, `embedded-ai/model/<계열>/`에 검증한 모델을 반입하세요. 내부 미러를 지정해도 에어갭 모드의 다운로드는 비활성화됩니다. §8 참고. |

`oswl-vdb build` 옵션(`VdbBuilderCli` 참고):
- `--sources osv,epss,kev,depsdev`(기본값 전체).
- `--mode delta --since previous.zip`은 추가/변경 키만 쓰고 `"_deleted":true` 마커를 함께 씁니다.
- `--offline-sources <dir>`은 이전 온라인 실행으로 채워진 캐시 디렉터리에서 네트워크 없이 빌드합니다(`osv`/`epss`/`kev`만 해당; deps.dev는 벌크 덤프가 없어 걸립니다).
- `verify <bundle.zip>`과 `inspect <bundle.zip>`은 체크섬과 메타데이터를 확인합니다.

반입 의미:
- `replace`(기본값)은 해당 소스의 기존 데이터를 지우고 번들을 씁니다.
- `merge`는 `(source, entry_key)` 기준으로 upsert하고 `"_deleted":true` 라인은 삭제로 처리합니다.
- v2 번들은 `meta.json`에 기록된 파일별 SHA-256 체크섬을 검증하며, 불일치 시 전체 번들을 거부하고 기존 스토어를 변경하지 않습니다.

정의 최신성: `OSWL_AIRGAPPED_STALENESS_WARN_DAYS`(기본값 `7`)와 `OSWL_AIRGAPPED_STALENESS_CRITICAL_DAYS`(기본값 `30`)는 반입된 스냅샷의 소스별 `sourceAsOf` 날짜 중 가장 오래된 값을 기준으로 관리 UI 배지를 결정합니다.

번들이 50MB를 초과하면 `OSWL_MULTIPART_MAX_FILE_SIZE` / `OSWL_MULTIPART_MAX_REQUEST_SIZE`(기본값 각 `50MB`)를 조정해야 할 수 있습니다.

## 8. 내장 AI (선택, CPU 전용)

기본은 **Qwen3.5-2B Q4_K_M**, 선택 모델은 **Gemma 4 E2B Q4_K_M**입니다. OsWL·PostgreSQL 동시 실행과 간헐적 사용 기준으로 Qwen 예상 최소 2 vCPU / RAM 8 GB, Gemma 권장 4 vCPU / RAM 16 GB를 잡습니다. 성능 보증이 아닌 추정치이며 CPU 크레딧과 스캔 중첩을 확인해야 합니다.

실행 파일은 `embedded-ai/llama/`, 모델은 `model/Qwen/`, `model/Gemma/`에 둡니다. Qwen만 자동 다운로드합니다. 기본 GPU 레이어 0, 스레드 1, 생성 슬롯 1, 컨텍스트 8192입니다. 고정 URL·해시·크기를 함께 유지하세요. 기존 `models-v1`은 새 모델이 아닙니다. Docker는 폴더 마운트와 Linux 실행 파일이 필요합니다.

전체 사양·구조·체크섬·미러 및 폐쇄망 설치·CPU 조정·모델과 언어 선택·재배포 고지는 [내장 AI](Embedded-AI.md)를 참고하세요.

## 9. 데이터베이스 스키마 (업그레이드)

OsWL **`prod`는 Hibernate `ddl-auto=validate`** — 기동 시 PostgreSQL을 자동 변경하지 않습니다.

| 프로필 | 스키마 관리 |
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

`OSWL_FLYWAY_ENABLED=true`로 `src/main/resources/db/migration/`의 버전별 마이그레이션을 활성화합니다. 기본값은 `false`입니다. 저장소에는 이미 `V1__baseline.sql`과 이후 마이그레이션이 있습니다. 빈 PostgreSQL DB에서는 V1부터 순서대로 실행한 후 Hibernate가 스키마를 검증합니다. Flyway 이력이 없는 기존 DB에서는 `baseline-on-migrate`가 V1 실행 없이 버전 1을 기록하고 V2부터 실행합니다. 활성화 전에 백업하고 기존 스키마와 마이그레이션을 비교하세요. 이미 수동 적용한 변경과 후속 마이그레이션이 충돌할 수 있습니다. 공유 DB에 적용한 마이그레이션 파일은 재생성하거나 수정하지 마세요. SQL을 수동 관리한다면 대상 버전에 필요한 변경을 순서대로 모두 적용해야 합니다. 아래의 일부 레거시 스크립트만으로 신규 설치 스키마를 구성할 수는 없습니다.

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

### v1.0.5: Spring Session / ShedLock 테이블 (옵트인)

**다중 인스턴스** 배포로 전환할 때만 필요합니다(§12 참고). `spring_session`, `spring_session_attributes`, `shedlock`이 추가됩니다. Flyway 사용자는 `db/migration/V10__spring_session_and_shedlock.sql`에서, 수동 스크립트 사용자는 `db/spring_session_and_shedlock.sql`을 실행하면 됩니다. 단일 인스턴스 배포라면 완전히 건너뛰어도 됩니다 — `OSWL_SESSION_STORE_TYPE=jdbc` 및/또는 `OSWL_SCHEDULER_LOCK_ENABLED=true`를 설정하기 전까지는 이 테이블을 아무도 참조하지 않습니다.

## 10. 배포 후 스모크 테스트

1. HTTPS 리버스 프록시로만 UI 접근.
2. 설정/로그인 및 2FA(활성 시) 완료.
3. 프로젝트·VCS 연결 생성 후 재기동 — 토큰 복호화 확인(`OSWL_ENCRYPTION_KEY` 안정성).
4. 프로젝트 API 키로 `POST /api/scan` ([스캔 API 보안](Scan-Api-Security.md)).
5. 멤버 프로젝트 접근, 타 사용자 프로젝트 ID는 forbidden 확인.
6. 감사 로그에서 인증 실패 검토.

## 11. 운영

- PostgreSQL 백업, `OSWL_ENCRYPTION_KEY`는 시크릿 매니저에 보관(분실 시 VCS 토큰 복호 불가) — 전체 절차와 복구 리허설 스크립트는 [백업 및 복구](Backup-And-Restore.md) 참고.
- 유출 시 API 키·SMTP 자격 증명 교체.
- `local`로 돌리면 안 되는 이미지에 `SPRING_PROFILES_ACTIVE=local` 넣지 않기.

## 12. 다중 인스턴스 배포 (수평 확장 / HA)

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

**로컬 개발:** `./gradlew bootRun`(PowerShell: `.\gradlew.bat bootRun`)으로 `local` 프로필과 H2를 사용합니다. 로컬 YAML에는 개발 전용 암호화 키가 있으며 필요하면 프로세스 환경 변수로 변경하세요. `.env`는 실행 도구가 명시적으로 읽을 때만 적용됩니다.
