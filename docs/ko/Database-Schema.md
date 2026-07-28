# 데이터베이스 스키마 및 마이그레이션

OsWL 애플리케이션 데이터는 PostgreSQL(`prod`) 또는 H2 파일 모드(`local`)에 저장됩니다. `domain/entity/` 아래 JPA 엔티티가 **실제 스키마의 기준**입니다.

---

## 프로파일별 동작

| 프로파일 | `ddl-auto` | 의미 |
|----------|------------|------|
| `local` | `update` | 엔티티 변경 시 H2 스키마가 자동 반영 |
| `prod` | `validate` | PostgreSQL이 엔티티와 다르면 기동 실패 — **자동 마이그레이션 없음** |
| `test` | `create-drop` | 테스트마다 메모리 스키마 재생성 |

운영 DB를 업그레이드할 때는 새 버전으로 재기동하기 **전에** `src/main/resources/db/` SQL을 적용합니다.

### Flyway (v1.0.4, 옵트인)

`OSWL_FLYWAY_ENABLED=true`로 설정하면 스키마 관리를 Flyway에 위임합니다(`baseline-on-migrate` 활성 — 기존 DB는 거부되지 않고 베이스라인 처리). 켜기 전에 현재 스키마와 일치하는 베이스라인을 생성하세요. 기본값 `false`에서는 위의 `ddl-auto` 동작이 그대로 유지됩니다.

### v1.0.4에서 추가된 컬럼

| 테이블 | 컬럼 | 타입 |
|---|---|---|
| `libraries` | `malicious` | `boolean NOT NULL DEFAULT false` |
| `libraries` | `typosquat_risk` | `boolean NOT NULL DEFAULT false` |
| `libraries` | `description` | `text` — deps.dev에서 가져온 업스트림 프로젝트 설명, 컴포넌트 상세에 표시 |
| `libraries` | `homepage` | `varchar(500)` — 프로젝트 홈페이지 URL (nullable) |
| `libraries` | `source_repo_url` | `varchar(500)` — 소스 저장소 URL (nullable) |
| `scan_results` | `ai_locale` | `varchar(16)` — 스캔을 시작한 담당자의 로케일. AI Insight가 해당 언어로 응답 |
| `scan_results` | `ai_status` | `varchar(20)` — AI 보강 진행 상태(`NOT_APPLICABLE`/`PENDING`/`RUNNING`/`COMPLETED`/`FAILED`). 스캔 `status`와 별도로 추적해 데이터 파이프라인이 끝나면 바로 스캔 완료 처리 |
| `ai_usage_events` | `branch` | `varchar(160)` — AI 사용량의 브랜치 귀속 |
| `libraries` | `version_meta_fetched_at` | `timestamp` — deps.dev 버전 메타데이터의 마지막 갱신 시각. 캐시 적중 라이브러리가 `oswl.cache.version-meta-ttl-seconds`(기본 24시간) 이내이면 GetVersion 호출을 건너뜀 |
| `libraries` | `license_expression_raw` | `text` — deps.dev가 반환한 병합 전 라이선스 목록(JSON). 오프라인 스냅샷 납품(export) 시 다중 라이선스 패키지를 그대로 왕복하기 위해 보존 |
| `libraries` | `ai_license_context_hash` | `varchar(64)` — AI 라이선스 요약 프롬프트를 구성하는 필드의 SHA-256. 재스캔 시 해시가 같으면 AI 호출 생략 |
| `library_cves` | `ai_context_hash` | `varchar(64)` — CVE별 AI 요약에 적용되는 동일한 컨텍스트 해시 응답 캐싱 |

두 boolean에 SQL 기본값을 지정한 이유는 `ddl-auto=update`가 데이터가 있는 테이블에 컬럼을 추가할 수 있게 하기 위함입니다. 기본값이 없으면 기존 행 때문에 `NOT NULL` 컬럼 추가가 실패합니다. `description`/`homepage`/`source_repo_url`은 보강(enrichment) 과정에서 지연 채움되며(OpenSSF Scorecard를 조회할 때 이미 호출하는 deps.dev 프로젝트 API를 그대로 재사용), 컬럼 추가 이전에 스캔된 컴포넌트는 다음 스캔 전까지 `null`로 남습니다. 나머지 신규 컬럼은 모두 nullable이며 멱등(`ADD COLUMN IF NOT EXISTS`)으로 추가됩니다. 해시/상태가 `null`이면 캐시 미스 또는 `NOT_APPLICABLE`로 취급되다가 다음 스캔에서 채워집니다. Flyway 경로는 `V3__component_metadata.sql`부터 `V7__snapshot_v2_and_license_raw.sql`까지가 담당합니다.

### v1.0.4에서 추가된 테이블

| 테이블 | 용도 |
|---|---|
| `airgapped_snapshot_entries` | 에어갭 오프라인 스냅샷 저장소 — `(source, entry_key)`당 한 행에 JSON 페이로드 저장. 소스: `osv`, `depsdev-version`, `depsdev-advisory`, `epss`, `kev`, 그리고 `unresolved`(wanted-list에 있었지만 `oswl-vdb` 빌더가 업스트림에서 해소하지 못한 컴포넌트) |
| `airgapped_snapshot_meta` | 소스별 관리 정보: `record_count`, `imported_at`과 v2 번들 출처(provenance) — `bundle_id`, `built_at`, `source_as_of`(최신성 UI가 기준으로 삼는 업스트림 데이터 기준일), `origin`, `format_version`(null이면 v1 번들) |

`cve_alerts`와 `jira_settings`도 v1.0.4에서 추가됐습니다(`V2__v104_features.sql`); 컬럼 구성은 마이그레이션 스크립트를 참고하세요. `airgapped_snapshot_*` 테이블은 아래 `airgapped_snapshot.sql`로 생성하고 `V7`이 확장합니다.

---

## 수동 마이그레이션 스크립트

| 파일 | 용도 |
|------|------|
| `project_members.sql` | 프로젝트 ACL용 `project_members` 생성 |
| `instance_setup_lock.sql` | 설정 마법사 잠금 테이블 |
| `ai_enhancement.sql` | AI 설정 컬럼, `ai_daily_usage` 테이블 |
| `airgapped_snapshot.sql` | 에어갭 오프라인 스냅샷 저장소(`airgapped_snapshot_entries`, `airgapped_snapshot_meta`) |
| `schema_cleanup.sql` | **1회** 정리: 미사용 테이블/컬럼 제거 (아래 참고) |

PostgreSQL에 `psql`, DBeaver, CI 마이그레이션 등으로 실행합니다. 가능한 곳은 `IF EXISTS` / `IF NOT EXISTS`를 사용합니다.

### `schema_cleanup.sql` (업그레이드 시)

레거시 스키마를 제거한 릴리스로 올릴 때 **한 번** 실행:

| 제거 대상 | 이유 |
|-----------|------|
| `ai_feedback` 테이블 | JPA/UI 미연결 |
| `external_api_settings` 테이블 | `cache_settings`만 사용 |
| `api_keys.created_by_user_id` | 미사용; 발급은 감사 로그(`CLI_KEY.CREATE`) |
| `scan_results.raw_payload`, `submitted_by_user_id` | 미사용; 제출자는 감사 로그(`SCAN.INGEST`) |
| `project_versions.imported_at`, `last_updated_at` | 미사용 타임스탬프 |
| `projects.updated_at`, `version`, `last_scanned_at` | 비정규화; UI는 최신 `scan_results` 사용 |

[운영 배포 체크리스트](Production-Deployment-Checklist.md) §8 참고.

---

## 핵심 테이블 (개요)

```
projects
 ├── project_versions
 ├── project_members
 ├── scan_results
 │    └── scan_components → libraries (전역)
 │         └── dependency_paths
 └── api_keys

libraries (공유)
 ├── library_cves  (CVE 연결, 심각도, CWE, AI 필드)
 └── 보강을 통한 라이선스 데이터

airgapped_snapshot_entries ── airgapped_snapshot_meta  (오프라인 스냅샷 저장소)

users, role_templates, audit_logs, cache_settings, vcs_connections, …
```

- **프로젝트 카드 버전 / 마지막 스캔** — `projects.version`이 아니라 최신 `scan_results`에서 계산.
- **보강 캐시** — `cache_settings`(설정 → 캐시); OSV/deps.dev 재조회 TTL.
- **CWE** — OSV `database_specific.cwe_ids`에서 `library_cves`에 저장.
- **오프라인 스냅샷 최신성** — `imported_at`이 아니라 `airgapped_snapshot_meta.source_as_of`(업스트림 기준일) 기준.

---

## 로컬 초기화

앱 중지 후 `oswl-db.mv.db`(및 H2 관련 파일) 삭제 → 재기동 시 빈 DB 및 설정 마법사. `local`에서는 수동 SQL 불필요.

---

## 관련 문서

- [운영 배포 체크리스트](Production-Deployment-Checklist.md)
- [관리](Administration.md) — 캐시 설정
- [스캔 API 보안](Scan-Api-Security.md) — 감사 로그 기반 제출자 추적
