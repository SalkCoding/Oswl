# API 레퍼런스

이 페이지는 OsWL이 노출하는 모든 REST 엔드포인트를 요약합니다. 인터랙티브 스키마는 **`local` 프로필**의 Swagger UI(`http://localhost:8080/swagger-ui.html`)에서 확인합니다. **`prod`에서는 Swagger가 꺼져 있습니다.**

OpenAPI 스펙 (JSON): `http://<host>:8080/v3/api-docs`

---

## 인증

### 세션 (웹 UI)

브라우저 기반 요청은 Spring Security 쿠키 세션을 사용합니다. `POST /login`으로 로그인합니다.

### API 키 (CLI)

CLI 엔드포인트에는 다음이 필요합니다:

```
Authorization: Bearer oswl_<your_api_key>
```

### CLI 스캔 제출자 자격증명

`POST /api/scan`에는 JSON 본문에 `submitterEmail`과 `submitterPassword`도 필요하여 스캔을 인증하고 귀속시킵니다. 성공한 수집은 **감사 로그**(`SCAN.INGEST`)에 제출자 이메일로 기록되며, `scan_results` 전용 컬럼에는 저장하지 않습니다.

---

## 인증 엔드포인트

| 메서드 | 경로 | 설명 |
|---|---|---|
| `GET` | `/login` | 로그인 페이지 |
| `POST` | `/login` | 자격증명 제출 |
| `GET` | `/login/otp-verify` | OTP 검증 페이지 |
| `POST` | `/login/otp-verify` | OTP 코드 제출 |
| `POST` | `/login/otp-resend` | OTP 이메일 재발송 |
| `GET` | `/setup` | 설정 마법사 페이지 (최초 실행 시만) |
| `POST` | `/setup` | 설정 마법사 폼 제출 |

---

## 프로젝트

| 메서드 | 경로 | 필요 권한 | 설명 |
|---|---|---|---|
| `GET` | `/projects` | `PROJECT_VIEW` | 프로젝트 대시보드 |
| `GET` | `/projects/list` | `PROJECT_VIEW` | 프로젝트 목록 (JSON) |
| `DELETE` | `/projects/{id}` | `PROJECT_DELETE` | 프로젝트 소프트 삭제 |
| `POST` | `/projects/{id}/restore` | `PROJECT_RESTORE` | 휴지통에서 복원 |
| `DELETE` | `/projects/{id}/permanent` | `PROJECT_PERMANENT_DELETE` | 영구 삭제 |
| `DELETE` | `/projects/trash/all` | `PROJECT_PERMANENT_DELETE` | 휴지통 비우기 |
| `DELETE` | `/projects/trash/selected` | `PROJECT_PERMANENT_DELETE` | 선택 항목 삭제 |
| `POST` | `/projects/trash/restore-selected` | `PROJECT_RESTORE` | 일괄 복원 |
| `GET` | `/projects/cards` | `PROJECT_VIEW` | 프로젝트 카드 HTML 조각 (대시보드) |
| `GET` | `/projects/scan-status/stream?ids=` | `PROJECT_VIEW` | **SSE** — 나열된 프로젝트 스캔 완료 시 `scan-update` |
| `POST` | `/projects` | `PROJECT_CREATE` | 프로젝트 생성 (JSON) |
| `PATCH` | `/api/projects/{id}/deployment-profile` | `PROJECT_UPDATE` | AI CVE 트리아지용 배포 프로필 설정 |

---

## Quick Import

`PROJECT_CREATE`(또는 시스템 관리자) 및 세션 인증 필요.

| 메서드 | 경로 | 설명 |
|---|---|---|
| `GET` | `/projects/quick-import` | Quick Import 페이지 |
| `GET` | `/api/quick-import/connections` | 현재 사용자 VCS 연결 목록 |
| `GET` | `/api/quick-import/repos?provider=` | 제공업체별 저장소 목록 (`GITHUB`, `GITLAB`, `BITBUCKET`) |
| `POST` | `/api/quick-import/start` | 새 임포트 작업 큐 등록 (`{ "repoUrl", "branch" }` → `{ "jobId" }`) |
| `POST` | `/api/quick-import/job/{jobId}/cancel` | 대기 중이거나 실행 중인 작업 취소 (알 수 없거나 이미 완료된 작업이면 `404`) |
| `GET` | `/api/quick-import/jobs` | 사용자의 모든 작업 목록 |
| `GET` | `/api/quick-import/job/{jobId}` | 작업 상태 폴링 (`QuickImportJobStatus`) |
| `GET` | `/api/quick-import/job/{jobId}/stream` | **SSE** — `job-update` 이벤트(JSON), 폴링 폴백 가능 |

**단계:** `QUEUED` → `CLONING` → `PARSING` → `SCANNING` → `ENRICHING` → `DONE` | `FAILED`.  
동시 **3건** 실행(`oswl.quick-import.max-concurrent`), 초과는 FIFO 큐(`queuePosition`).  
`ENRICHING` 중 `percent`, `subPhase`(`CVE`, `LICENSE`, `INSIGHTS`), `detailLines`, `aiPreviews`와 함께 deps.dev 캐시 판정 통계(`cacheTotal`, `cacheHit`, `cacheToFetch`)가 포함됩니다.  
별도의 `aiStatus` 필드는 백그라운드 AI 보강 상태(`NOT_APPLICABLE`, `PENDING`, `RUNNING`, `COMPLETED`, `FAILED`)를 추적합니다. 스캔이 `DONE`이 되어도 `aiStatus`는 여전히 `PENDING`/`RUNNING`일 수 있습니다.

---

## GitHub OAuth / PAT

| 메서드 | 경로 | 설명 |
|---|---|---|
| `POST` | `/api/github/connect` | GitHub PAT 연결 |
| `POST` | `/api/github/disconnect` | GitHub 연결 제거 |
| `GET` | `/api/github/status` | 연결 상태 |
| `GET` | `/api/github/accounts` | 인증된 계정 목록 |
| `GET` | `/api/github/repos` | 접근 가능한 저장소 목록 |
| `GET` | `/api/github/branches` | 저장소의 브랜치 목록 |
| `GET` | `/api/github/branches/by-project` | 프로젝트에 연결된 저장소의 브랜치 목록 (`?projectId=` — Apply Patch 모달에서 사용) |
| `GET` | `/api/github/branch-updated-at` | 브랜치의 마지막 커밋 날짜 |
| `DELETE` | `/api/github/accounts/{login}` | 특정 계정 제거 |

---

## CLI 스캔

| 메서드 | 경로 | 인증 | 설명 |
|---|---|---|---|
| `GET` | `/api/scan/ping` | API 키 | 연결 및 키 유효성 확인 |
| `GET` | `/api/scan/manifest-rules` | API 키 | manifest 수집 규칙 (`/scripts/manifest-rules.json`과 동일) |
| `POST` | `/api/scan/parse` | API 키 | manifest zip 파싱 (CLI 1단계) |
| `POST` | `/api/scan` | API 키 + 자격증명 | 의존성 스캔 제출 (CLI 2단계) |
| `GET` | `/api/scan/{scanId}/status` | 세션 | 스캔 상태 폴링 — `status`, `componentCount`와 별도의 AI 보강 상태 `aiStatus`(스캔 완료와 독립), `securityPostureInsight`를 반환 |
| `POST` | `/api/scan/gate` | API 키 | **v1.0.4** — PR / CI 보안 게이트, `exitCode` 포함 판정 |

---

## 보안 센터

| 메서드 | 경로 | 필요 권한 | 설명 |
|---|---|---|---|
| `GET` | `/projects/{id}/security-center` | `SECURITY_CENTER_VIEW` | 보안 센터 페이지 |
| `PATCH` | `/projects/{id}/security-center/bulk-status` | `SECURITY_CENTER_UPDATE_STATUS` | CVE 상태 일괄 업데이트 |
| `GET` | `/projects/{id}/security-center/export` | `SECURITY_CENTER_EXPORT` | CVE 목록 CSV 다운로드 (`?scanId=`, `?format=csv`) |
| `POST` | `/projects/{id}/security-center/batch-pr` | `SECURITY_CENTER_UPDATE_STATUS` | **v1.0.4** — 선택 컴포넌트 일괄 업그레이드 PR 생성 |
| `GET` | `/projects/{projectId}/security-center/compliance-report` | `SECURITY_CENTER_EXPORT` | **v1.0.4** — 인쇄용 컴플라이언스 리포트 |

### SBOM / VEX / SARIF (v1.0.4)

| 메서드 | 경로 | 필요 권한 | 설명 |
|---|---|---|---|
| `GET` | `/api/projects/{projectId}/sbom` | `SECURITY_CENTER_EXPORT` | CycloneDX 1.6 SBOM (`application/vnd.cyclonedx+json`) |
| `GET` | `/api/projects/{projectId}/vex` | `SECURITY_CENTER_EXPORT` | 트리아지 판단 기반 CycloneDX VEX |
| `GET` | `/api/projects/{projectId}/sarif` | `SECURITY_CENTER_EXPORT` | SARIF 2.1.0 (`application/sarif+json`) |
| `POST` | `/api/sbom/import` | `PROJECT_CREATE` | 외부 CycloneDX 파일 가져오기 (multipart) |

---

## 컴포넌트 상세

| 메서드 | 경로 | 필요 권한 | 설명 |
|---|---|---|---|
| `GET` | `/projects/{id}/components/{compId}` | `COMPONENT_DETAIL_VIEW` | 컴포넌트 상세 (전체 페이지 또는 HTMX fragment) |
| `POST` | `/projects/{id}/components/{compId}/cves/{cveDbId}/ai-summarize` | `SECURITY_CENTER_UPDATE_STATUS` | CVE AI 트리아지 재생성 |
| `POST` | `/projects/{id}/components/{compId}/defer` | `SECURITY_CENTER_UPDATE_STATUS` | 조치 연기 기록 |
| `POST` | `/projects/{id}/components/{compId}/create-pr` | `SECURITY_CENTER_UPDATE_STATUS` | 의존성 수정 PR 생성 |
| `POST` | `/projects/{id}/components/{compId}/jira-ticket` | `SECURITY_CENTER_UPDATE_STATUS` | **v1.0.4** — 해당 항목으로 Jira 이슈 생성 |

---

## 라이선스

| 메서드 | 경로 | 필요 권한 | 설명 |
|---|---|---|---|
| `GET` | `/projects/{id}/license` | `LICENSE_VIEW` | 라이선스 분석 페이지 |
| `POST` | `/projects/{id}/license/refresh-insights?scanId=` | `LICENSE_VIEW` | 스캔 한 건의 AI 인사이트 재생성 |

---

## 리스크 트렌드

| 메서드 | 경로 | 필요 권한 | 설명 |
|---|---|---|---|
| `GET` | `/projects/{id}/risk-trend` | `RISK_TREND_VIEW` | 리스크 트렌드 페이지 |

---

## 버전 비교

| 메서드 | 경로 | 필요 권한 | 설명 |
|---|---|---|---|
| `GET` | `/projects/{id}/version-diff` | `VERSION_DIFF_VIEW` | 버전 비교 페이지 |

---

## 스캔 히스토리

| 메서드 | 경로 | 필요 권한 | 설명 |
|---|---|---|---|
| `GET` | `/projects/{id}/scan-history` | `SCAN_HISTORY_VIEW` | 스캔 히스토리 페이지 |
| `DELETE` | `/projects/{id}/scan-history/{scanId}` | `PROJECT_DELETE` | 스캔 레코드 삭제 |

---

## 프로젝트 API 키

| 메서드 | 경로 | 필요 권한 | 설명 |
|---|---|---|---|
| `GET` | `/api/projects/{id}/keys` | `SETTINGS_CLI_KEY_MANAGE` + 프로젝트 멤버십 | 프로젝트 키 목록 |
| `POST` | `/api/projects/{id}/keys` | `SETTINGS_CLI_KEY_MANAGE` + 프로젝트 멤버십 | 키 생성 |
| `DELETE` | `/api/projects/{id}/keys/{keyId}` | `SETTINGS_CLI_KEY_MANAGE` + 프로젝트 멤버십 | 키 취소 |

---

## 관리자

### 사용자

| 메서드 | 경로 | 설명 |
|---|---|---|
| `GET` | `/api/admin/users` | 모든 사용자 목록 |
| `POST` | `/api/admin/users` | 사용자 생성/초대 |
| `PUT` | `/api/admin/users/{id}/roles` | 사용자 역할 업데이트 |
| `PUT` | `/api/admin/users/{id}/display-name` | 표시 이름 변경 |
| `PUT` | `/api/admin/users/{id}/activate` | 계정 활성화 |
| `PUT` | `/api/admin/users/{id}/deactivate` | 계정 비활성화 |
| `DELETE` | `/api/admin/users/{id}` | 사용자 삭제 |

### 역할 템플릿

| 메서드 | 경로 | 설명 |
|---|---|---|
| `GET` | `/api/admin/role-templates` | 템플릿 목록 |
| `POST` | `/api/admin/role-templates` | 템플릿 생성 |
| `GET` | `/api/admin/role-templates/permissions` | 사용 가능한 모든 권한 목록 |
| `PUT` | `/api/admin/role-templates/{id}` | 템플릿 업데이트 |
| `DELETE` | `/api/admin/role-templates/{id}` | 템플릿 삭제 |

### 감사 로그

| 메서드 | 경로 | 설명 |
|---|---|---|
| `GET` | `/api/admin/audit-logs` | 페이지네이션 감사 로그 |
| `GET` | `/api/admin/audit-logs/export.csv` | CSV로 내보내기 |
| `GET` | `/api/admin/audit-logs/export?format=jsonl\|cef` | **v1.0.4** — SIEM 내보내기 (`AUDIT_LOG_EXPORT`) |

### 조직 대시보드 (v1.0.4)

| 메서드 | 경로 | 필요 권한 | 설명 |
|---|---|---|---|
| `GET` | `/org-dashboard` | `ORG_DASHBOARD_VIEW` | 전사 포스처·랭킹·KEV·라이선스 롤업 |

### 오프라인 스냅샷 (v1.0.4)

모든 엔드포인트는 `SYSTEM_ADMIN` 역할 또는 `SETTINGS_SNAPSHOT_MANAGE` 권한이 필요합니다.

| 메서드 | 경로 | 설명 |
|---|---|---|
| `GET` | `/api/admin/snapshot` | 저장소 상태 — air-gapped 플래그와, 소스(`osv`, `depsdev-version`, `depsdev-advisory`, `epss`, `kev`)별 레코드 수, `importedAt`, v2 출처(`bundleId`, `builtAt`, `sourceAsOf`, `origin`; v1/메타 없는 번들에서 가져온 소스는 null)를 반환합니다. 또한 모든 소스 중 가장 오래된 `sourceAsOf`인 `oldestSourceAsOf`와 정의 최신성 배지에 사용되는 설정값 `stalenessWarnDays`, `stalenessCriticalDays`를 포함합니다 |
| `POST` | `/api/admin/snapshot/import` | 스냅샷 번들 반입 (JSONL 파일들의 zip + `meta.json`). `?mode=replace\|merge`로 번들 자체 `meta.json` 모드를 오버라이드합니다: `replace`(기본)는 각 소스를 쓰기 전에 비우고, `merge`는 키 기준으로 upsert하며 `"_deleted":true` 묘비석을 처리합니다. v2 체크섬은 저장소 변경 전에 검증되며 불일치 시 전체 번들이 거부됩니다 |
| `POST` | `/api/admin/snapshot/import-from-path` | 서버 디스크에 이미 있는 번들을 반입합니다 (`{ "path", "mode" }`) — 브라우저 업로드가 비실용적인 큰 번들용입니다. `oswl.airgapped.import-dir`이 설정되지 않으면 400; `path`는 해당 디렉터리 아래로 해석되어야 합니다 |
| `GET` | `/api/admin/snapshot/wanted-list` | 이 인스턴스의 wanted-list를 NDJSON(`application/x-ndjson`)으로 스트리밍 — 스캔된 컴포넌트당 `{"ecosystem","name","version"}` 한 줄씩, 온라인 머신의 `oswl-vdb build --wanted`용입니다. 프로젝트명이나 저장소 URL은 인스턴스를 떠나지 않습니다 |
| `GET` | `/api/admin/snapshot/export` | 이 인스턴스가 이미 가져온 데이터로부터 v2 스냅샷 번들(`application/zip`)을 만들어 납니다; `meta.json`의 `origin`은 `derived-from-scan`입니다. 온라인 인스턴스에서 실행한 뒤 air-gapped 인스턴스에서 반입하세요 |

### 모니터링 (v1.0.4)

| 메서드 | 경로 | 설명 |
|---|---|---|
| `GET` | `/actuator/health` | 헬스 체크 (관리자 권한) |
| `GET` | `/actuator/info` | 빌드·버전 정보 |
| `GET` | `/actuator/prometheus` | Prometheus용 micrometer 메트릭 |
| `POST` | `/projects/{projectId}/cve-alerts/acknowledge` | 신규 CVE 알림 확인 처리 (`SECURITY_CENTER_VIEW`) |

### 관리자 CLI 키

| 메서드 | 경로 | 설명 |
|---|---|---|
| `GET` | `/api/admin/cli-keys` | 프로젝트별 CLI 키 통합 조회 |
| `POST` | `/api/admin/cli-keys` | 프로젝트별 키 발급 (`projectId` 필수) |
| `PATCH` | `/api/admin/cli-keys/{keyId}/toggle` | 키 활성화/비활성화 |

---

## 설정

### 보안 (SMTP, 2FA, 비밀번호 정책)

| 메서드 | 경로 | 필요 권한 | 설명 |
|---|---|---|---|
| `GET` | `/api/settings/security` | `SETTINGS_SECURITY_MANAGE` | 보안 설정 조회 |
| `PUT` | `/api/settings/security` | `SETTINGS_SECURITY_MANAGE` | 설정 업데이트 |
| `POST` | `/api/settings/security/mail/test` | `SETTINGS_SECURITY_MANAGE` | 테스트 이메일 발송 |

### AI

| 메서드 | 경로 | 필요 권한 | 설명 |
|---|---|---|---|
| `GET` | `/api/settings/ai` | `SETTINGS_AI_MANAGE` | 활성 제공업체 + 보강 기본값(온도, 한도, 배포 프로필 등) |
| `PUT` | `/api/settings/ai` | `SETTINGS_AI_MANAGE` | 제공업체 자격증명 및/또는 기본값 저장 |
| `PUT` | `/api/settings/ai/deactivate` | `SETTINGS_AI_MANAGE` | 활성 제공업체 비활성화 |
| `PUT` | `/api/settings/ai/activate/{provider}` | `SETTINGS_AI_MANAGE` | 제공업체 전환 |
| `POST` | `/api/settings/ai/test-connection` | `SETTINGS_AI_MANAGE` | 연결 테스트(저장 안 함). 완료 요청 대신 사용 가능한 모델 목록을 조회하므로 토큰이 소비되지 않고 일일 호출 상한에도 반영되지 않습니다. 설정된 모델 ID가 조회된 목록에 없으면 응답에 경고 `hint`가 포함됩니다 |
| `GET` | `/api/settings/ai/prompts` | `SETTINGS_AI_MANAGE` | 편집 가능 프롬프트 + 오버라이드 |
| `POST` | `/api/settings/ai/golden-test` | `SETTINGS_AI_MANAGE` | 골든 프롬프트 회귀 테스트 실행 |
| `GET` | `/api/settings/ai/usage` | `SETTINGS_AI_MANAGE` | AI 사용량 통계 — 오늘 호출 수/토큰/예상 비용, 일일 상한, 최근 7일 집계(일별 집계 테이블에서 조회) |
| `GET` | `/api/settings/ai/usage/events` | `SETTINGS_AI_MANAGE` | 최근 AI 호출 이벤트, 최신순 (`?page=`, `?size=`, 기본 크기 `10`). 최근 **100건**만 보존되며(FIFO), 최대 10페이지까지 존재 |
| `GET` | `/api/settings/ai/embedded` | `SETTINGS_AI_MANAGE` | 내장 AI 상태 (`running`, `external`, `binaryFound`, `activeModel`, `fallbackUsed`, `lastError`, `availableModels`, `modelsDir`, `baseUrl`, 기본 모델 다운로드 진행 중이면 `downloading`, `downloadedBytes`, `downloadTotalBytes`도 포함) |
| `POST` | `/api/settings/ai/embedded/start?model=` | `SETTINGS_AI_MANAGE` | llama.cpp 사이드카 시작 (모델 파일명 선택 지정; 후보 자동 폴백, 실패 시 400과 사유). `.gguf`가 하나도 없는 신규 설치에서는 대신 Apache 2.0 Qwen3.5-2B Q4_K_M 모델을 백그라운드로 다운로드하고 즉시 응답(`downloading: true`) — 진행률은 `GET .../embedded`로 폴링 |
| `POST` | `/api/settings/ai/embedded/stop` | `SETTINGS_AI_MANAGE` | 사이드카 중지 및 LOCAL 프로바이더 비활성화 |
| `PUT` | `/api/settings/ai/embedded/config` | `SETTINGS_AI_MANAGE` | 폴더/모델 오버라이드 저장 `{ "dir", "model" }` (null은 유지, 공백은 해제; dir이 없으면 400) |

제공업체: `OPENAI`, `ANTHROPIC`, `GEMINI`, `LOCAL`. embedded 엔드포인트는 내장 llama.cpp LOCAL 프로바이더를 관리합니다 — [내장 AI](Embedded-AI.md) 참고.

### 라이선스 정책

| 메서드 | 경로 | 필요 권한 | 설명 |
|---|---|---|---|
| `GET` | `/api/settings/license-policy` | `LICENSE_POLICY_MANAGE` | SPDX 정책 항목 목록 |
| `PUT` | `/api/settings/license-policy/{spdxId}` | `LICENSE_POLICY_MANAGE` | 라이선스 상태 변경 |

### VCS 연결

| 메서드 | 경로 | 필요 권한 | 설명 |
|---|---|---|---|
| `GET` | `/api/settings/vcs` | `SETTINGS_VCS_MANAGE` | 연결 목록 |
| `POST` | `/api/settings/vcs` | `SETTINGS_VCS_MANAGE` | 연결 추가 |
| `DELETE` | `/api/settings/vcs/{id}` | `SETTINGS_VCS_MANAGE` | 연결 제거 |

### 캐시 (보강 정책)

**deps.dev**·**OSV**에서 가져온 라이브러리 CVE/라이선스 데이터를 스캔 간 얼마나 재사용할지 제어합니다.

| 메서드 | 경로 | 필요 권한 | 설명 |
|---|---|---|---|
| `GET` | `/api/settings/cache` | `SETTINGS_CACHE_MANAGE` | 캐시 키(`DEPS_DEV`, `OSV_VULN`) 목록, TTL, 마지막 클리어 시각 |
| `PUT` | `/api/settings/cache` | `SETTINGS_CACHE_MANAGE` | 키별 TTL 변경 (`cacheKey`, `ttlSeconds`) |
| `POST` | `/api/settings/cache/clear?cacheKey=…` | `SETTINGS_CACHE_MANAGE` | 캐시 클리어 — 해당 시각 이전에 fetch된 라이브러리는 다음 보강 시 재조회 |

**TTL 의미** (설정 → 캐시 UI는 보강 파이프라인의 `DEPS_DEV` TTL에 매핑):

| UI 모드 | `ttlSeconds` | 동작 |
|---|---|---|
| 항상 새로고침 | `1` | 매 스캔마다 전체 재조회 |
| 사용자 지정 TTL | `N` (초) | `libraries.fetched_at`이 N초보다 오래되면 재조회 |
| 영구 캐시 | 매우 큰 값 (예: 50년) | 라이브러리당 최초 1회만 조회 |

> **참고:** 예전 `/api/settings/external` API와 `external_api_settings` 테이블은 제거되었습니다. 캐시는 `/api/settings/cache`만 사용합니다.

---

## 로컬/테스트 (local 프로필 전용)

| 메서드 | 경로 | 설명 |
|---|---|---|
| `GET` | `/data/test` | DB 초기화 및 풍부한 테스트 데이터 시드 |
| `GET` | `/data/test-api-key` | 사용 가능한 테스트 API 키 조회 |
