# 데이터베이스 스키마 및 마이그레이션

OsWL 애플리케이션 데이터는 PostgreSQL(`prod`) 또는 H2 파일 모드(`local`)에 저장됩니다. `domain/entity/` 아래 JPA 엔티티가 **실제 스키마의 기준**입니다.

---

## 프로파일별 동작

| 프로파일 | `ddl-auto` | 의미 |
|----------|------------|------|
| `local` | `update` | 엔티티 변경 시 H2 스키마가 자동 반영 |
| `prod` | `validate` | PostgreSQL이 엔티티와 다르면 기동 실패 — **자동 마이그레이션 없음** |
| `test` | `create-drop` | 테스트마다 메모리 스키마 재생성 |

운영 DB를 업그레이드할 때는 새 버전으로 재기동하기 **전에** `oswl-app/src/main/resources/db/migration/`의 **Flyway 마이그레이션**을 적용합니다.

Flyway 이전에 만들어진 DB는 `oswl-app/src/main/resources/db/`의 레거시 스크립트가 추가로 필요할 수 있습니다(아래 표 참고).

---

## Flyway (운영)

| 설정 | 값 (`application-prod.yaml`) |
|------|------------------------------|
| 활성화 | `true` (prod만) |
| 위치 | `classpath:db/migration` |
| 베이스라인 | `baseline-on-migrate: true`, `baseline-version: 6` |

| 마이그레이션 | 용도 |
|-------------|------|
| `V7__api_key_machine.sql` | CI machine token용 `api_keys.type`, `bound_user_id` |

`local` 프로파일은 `flyway.enabled: false`, H2에 `ddl-auto: update`.

---

## 레거시 수동 마이그레이션 스크립트

Flyway 도입 전 DB이거나 해당 엔티티가 반영되기 전에 만든 PostgreSQL을 올릴 때만 사용합니다. 신규 운영 설치는 Flyway + `validate`로 충분합니다.

스크립트 위치: `oswl-app/src/main/resources/db/`

| 파일 | 용도 |
|------|------|
| `api_key_machine.sql` | Flyway V7과 동일 (Flyway 미적용 시) |
| `notification_settings.sql` | 인스턴스 알림 설정 테이블 |
| `import_webhook.sql` | 프로젝트별 VCS webhook 컬럼 |
| `project_members.sql` | 프로젝트 ACL용 `project_members` 생성 |
| `instance_setup_lock.sql` | 설정 마법사 잠금 테이블 |
| `ai_enhancement.sql` | AI 설정 컬럼, `ai_daily_usage` 테이블 |
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

[운영 배포 체크리스트](../Production-Deployment-Checklist.md) §8 참고.

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

users, role_templates, audit_logs, cache_settings, vcs_connections, …
```

- **프로젝트 카드 버전 / 마지막 스캔** — `projects.version`이 아니라 최신 `scan_results`에서 계산.
- **보강 캐시** — `cache_settings`(설정 → 캐시); OSV/deps.dev 재조회 TTL.
- **CWE** — OSV `database_specific.cwe_ids`에서 `library_cves`에 저장.

---

## 로컬 초기화

앱 중지 후 `oswl-db.mv.db`(및 H2 관련 파일) 삭제 → 재기동 시 빈 DB 및 설정 마법사. `local`에서는 수동 SQL 불필요.

---

## 관련 문서

- [운영 배포 체크리스트](../Production-Deployment-Checklist.md)
- [관리](Administration.md) — 캐시 설정
- [스캔 API 보안](Scan-Api-Security.md) — 감사 로그 기반 제출자 추적
