# API 레퍼런스

이 페이지는 OsWL이 노출하는 모든 REST 엔드포인트를 요약합니다. 인터랙티브 스키마는 **`local` 프로파일**의 Swagger UI(`http://localhost:8080/swagger-ui.html`)에서 확인합니다. **`prod`에서는 Swagger가 꺼져 있습니다.**

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

`POST /api/scan`에는 일반적으로 JSON 본문에 `submitterEmail`과 `submitterPassword`가 필요합니다. 성공한 수집은 **감사 로그**(`SCAN.INGEST`)에 제출자 이메일로 기록되며, `scan_results` 전용 컬럼에는 저장하지 않습니다.

**CI machine token** (`ApiKeyType.MACHINE`): `POST /api/projects/{id}/keys`에 `{ "machineToken": true, "boundUserEmail": "ci@company.com" }`로 발급. 바인딩된 사용자는 `SCAN_SUBMIT` 권한과 프로젝트 멤버십이 있어야 합니다. `submitterPassword` 생략 가능, `submitterEmail`은 바인딩 사용자와 같으면 생략 가능.

`/api/scan/**` 경로( `GET /api/scan/{scanId}/status` 제외)는 `ApiKeyAuthInterceptor`가 `Authorization: Bearer oswl_<api_key>`로 인증합니다. CSRF 예외: `POST /api/scan`, `POST /api/scan/parse`, `GET /api/scan/ping`, `POST /api/import/webhook`. [스캔 API 보안](Scan-Api-Security.md) 참고.

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
| `POST` | `/api/change-password` | 비밀번호 변경 (강제/자발적; 세션 + CSRF) |
| `POST` | `/api/my/change-password` | 본인 비밀번호 변경 시작 |
| `POST` | `/api/my/change-password/otp-verify` | 비밀번호 변경 OTP 검증 |
| `POST` | `/api/my/change-password/otp-resend` | 비밀번호 변경 OTP 재발송 |

---

## 공개 페이지

| 메서드 | 경로 | 설명 |
|---|---|---|
| `GET` | `/oss-notices` | 오픈소스 라이선스 고지 페이지 |

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
| `GET` | `/projects/cli-integration` | `PROJECT_VIEW` | CLI 연동 안내 페이지 |
| `GET` | `/projects/git-integration` | `PROJECT_VIEW` | Git 연동 안내 페이지 |
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
| `GET` | `/api/quick-import/jobs` | 사용자의 모든 작업 목록 |
| `GET` | `/api/quick-import/job/{jobId}` | 작업 상태 폴링 (`QuickImportJobStatus`) |
| `GET` | `/api/quick-import/job/{jobId}/stream` | **SSE** — `job-update` 이벤트(JSON), 폴링 폴백 가능 |

**단계:** `QUEUED` → `CLONING` → `PARSING` → `SCANNING` → `ENRICHING` → `DONE` | `FAILED`.  
동시 **2건** 실행(`oswl.quick-import.max-concurrent`), 초과는 FIFO 큐(`queuePosition`).  
`ENRICHING` 중 `percent`, `subPhase`, `detailLines`, `aiPreviews` 포함.

---

## VCS Push Webhook (자동 재스캔)

세션 인증 없음. 프로젝트별 시크릿 검증. CSRF 예외. 절대 URL을 위해 `OSWL_PUBLIC_BASE_URL`(또는 `oswl.public-base-url`) 설정.

| 메서드 | 경로 | 인증 | 설명 |
|---|---|---|---|
| `POST` | `/api/import/webhook` | 프로젝트 시크릿 | GitHub / GitLab / Bitbucket push 이벤트 처리 후 재임포트 큐잉 |

**시크릿 검증**

| 제공자 | 헤더 / 방식 |
|---|---|
| GitHub | `X-Hub-Signature-256` (본문 HMAC-SHA256, 프로젝트 시크릿) |
| GitLab | `X-Gitlab-Token` = 프로젝트 시크릿 |
| Bitbucket / 일반 | `X-OsWL-Webhook-Secret` = 프로젝트 시크릿 |

응답: `{ "accepted": true/false, "message": "…", "jobId": "…" }` (스캔 큐잉 시 `jobId` 포함).

### 프로젝트 webhook 설정

| 메서드 | 경로 | 필요 권한 | 설명 |
|---|---|---|---|
| `GET` | `/api/projects/{id}/webhook` | `PROJECT_VIEW` + 멤버십 | Webhook URL, 활성화 여부(시크릿 미반환) |
| `PUT` | `/api/projects/{id}/webhook` | `PROJECT_UPDATE` + 멤버십 | `{ "enabled", "rotateSecret" }` — 재발급 시 시크릿 한 번만 반환 |

---

## GitHub OAuth / PAT

| 메서드 | 경로 | 설명 |
|---|---|---|
| `POST` | `/api/github/connect` | GitHub PAT 연결 |
| `POST` | `/api/github/disconnect` | 세션의 모든 GitHub 토큰 제거 |
| `GET` | `/api/github/status` | 연결 상태 |
| `GET` | `/api/github/accounts` | 인증된 계정 목록 |
| `GET` | `/api/github/repos` | 접근 가능한 저장소 목록 |
| `GET` | `/api/github/branches` | 저장소의 브랜치 목록 |
| `GET` | `/api/github/branches/by-project` | 연결된 프로젝트의 브랜치 목록 |
| `GET` | `/api/github/branch-updated-at` | 브랜치의 마지막 커밋 날짜 |
| `POST` | `/api/github/repos/import` | GitHub 저장소를 프로젝트로 임포트 |
| `DELETE` | `/api/github/accounts/{login}` | 특정 계정 제거 |

---

## VCS 브랜치

세션 인증. 연결된 프로젝트의 GitLab·Bitbucket 브랜치(GitHub는 `/api/github/branches` 사용).

| 메서드 | 경로 | 설명 |
|---|---|---|
| `GET` | `/api/vcs/branches?projectId=` | 프로젝트 VCS 저장소의 브랜치 목록 |

---

## CLI 스캔

| 메서드 | 경로 | 인증 | 설명 |
|---|---|---|---|
| `GET` | `/api/scan/ping` | API 키 | 연결 및 키 유효성 확인 |
| `GET` | `/api/scan/manifest-rules` | API 키 | manifest 수집 규칙 (`/scripts/manifest-rules.json`과 동일) |
| `POST` | `/api/scan/parse` | API 키 | manifest zip 파싱 (CLI 1단계) |
| `POST` | `/api/scan` | API 키 + 제출자 자격증명 | 의존성 스캔 제출 (CLI 2단계) |
| `GET` | `/api/scan/{scanId}/status` | 세션 | 스캔 상태 폴링 (UI; 프로젝트 멤버십 필요) |

---

## 보안 센터

| 메서드 | 경로 | 필요 권한 | 설명 |
|---|---|---|---|
| `GET` | `/projects/{id}/security-center` | `SECURITY_CENTER_VIEW` | 보안 센터 페이지 |
| `GET` | `/projects/{id}/security-center/print` | `SECURITY_CENTER_EXPORT` | 인쇄용 보안 센터 뷰 |
| `GET` | `/projects/{id}/security-center/export?format=csv` | `SECURITY_CENTER_EXPORT` | CSV로 findings보내기 |
| `PATCH` | `/projects/{id}/security-center/bulk-status` | `SECURITY_CENTER_UPDATE_STATUS` | CVE 상태 일괄 업데이트 |

---

## 컴포넌트 상세

| 메서드 | 경로 | 필요 권한 | 설명 |
|---|---|---|---|
| `GET` | `/projects/{id}/components/{compId}` | `COMPONENT_DETAIL_VIEW` | 컴포넌트 상세 (전체 페이지 또는 HTMX fragment) |
| `POST` | `/projects/{id}/components/{compId}/cves/{cveDbId}/ai-summarize` | `SECURITY_CENTER_UPDATE_STATUS` | CVE AI 트리아지 재생성 |
| `POST` | `/projects/{id}/components/{compId}/defer` | `SECURITY_CENTER_UPDATE_STATUS` | 조치 연기 기록 |
| `POST` | `/projects/{id}/components/{compId}/create-pr` | `SECURITY_CENTER_UPDATE_STATUS` | 의존성 수정 PR 생성 |

---

## 라이선스

| 메서드 | 경로 | 필요 권한 | 설명 |
|---|---|---|---|
| `GET` | `/projects/{id}/license` | `LICENSE_VIEW` | 라이선스 분석 페이지 |
| `GET` | `/projects/{id}/license/export/notice` | `LICENSE_EXPORT` | NOTICE.txt 다운로드 |
| `GET` | `/projects/{id}/license/export/spdx` | `LICENSE_EXPORT` | SPDX SBOM 다운로드 (tag-value) |
| `GET` | `/projects/{id}/license/export/spdx-json` | `LICENSE_EXPORT` | SPDX SBOM 다운로드 (JSON) |
| `GET` | `/projects/{id}/license/export/cyclonedx` | `LICENSE_EXPORT` | CycloneDX SBOM 다운로드 (JSON) |

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

## 프로젝트 멤버

| 메서드 | 경로 | 필요 권한 | 설명 |
|---|---|---|---|
| `GET` | `/api/projects/{id}/members` | `PROJECT_VIEW` + 멤버십 | 멤버 목록 |
| `POST` | `/api/projects/{id}/members` | `PROJECT_MEMBER_MANAGE` + 멤버십 | 이메일로 멤버 추가 (`{ "email", "role": "ADMIN" \| "MEMBER" }`) |
| `DELETE` | `/api/projects/{id}/members/{userId}` | `PROJECT_MEMBER_MANAGE` + 멤버십 | 멤버 제거(마지막 ADMIN은 제거 불가) |

---

## 프로젝트 API 키

| 메서드 | 경로 | 필요 권한 | 설명 |
|---|---|---|---|
| `GET` | `/api/projects/{id}/keys` | `SETTINGS_CLI_KEY_MANAGE` + 프로젝트 멤버십 | 프로젝트 키 목록 |
| `POST` | `/api/projects/{id}/keys` | `SETTINGS_CLI_KEY_MANAGE` + 프로젝트 멤버십 | 키 생성. 본문: CI용 `{ "machineToken": true, "boundUserEmail": "…" }` 선택 |
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

### 관리자 CLI 키

| 메서드 | 경로 | 설명 |
|---|---|---|
| `GET` | `/api/admin/cli-keys` | 전역 CLI 키 목록 |
| `POST` | `/api/admin/cli-keys` | 전역 키 생성 |
| `PATCH` | `/api/admin/cli-keys/{keyId}/toggle` | 키 활성화/비활성화 |

---

## 설정

### 보안 (SMTP, 2FA, 비밀번호 정책)

| 메서드 | 경로 | 필요 권한 | 설명 |
|---|---|---|---|
| `GET` | `/api/settings/security` | `SETTINGS_SECURITY_MANAGE` | 보안 설정 조회 |
| `PUT` | `/api/settings/security` | `SETTINGS_SECURITY_MANAGE` | 설정 업데이트 |
| `POST` | `/api/settings/security/mail/test` | `SETTINGS_SECURITY_MANAGE` | 테스트 이메일 발송 |

### 알림

스캔에서 **Critical** CVE 또는 **RESTRICTED** 라이선스가 발견되면 인스턴스 전역 채널로 알림.

| 메서드 | 경로 | 필요 권한 | 설명 |
|---|---|---|---|
| `GET` | `/api/settings/notifications` | `SETTINGS_NOTIFICATION_MANAGE` | Slack/Teams webhook 상태, 이메일 digest·트리거 플래그 |
| `PUT` | `/api/settings/notifications` | `SETTINGS_NOTIFICATION_MANAGE` | 채널 업데이트 (`slackWebhookUrl`, `teamsWebhookUrl`, `clearSlackWebhook`, `clearTeamsWebhook`, `emailDigestEnabled`, `notifyCriticalCve`, `notifyLicenseViolation`) |

Webhook URL은 암호화 저장. 이메일 digest는 보안 설정의 SMTP 필요.

### AI

| 메서드 | 경로 | 필요 권한 | 설명 |
|---|---|---|---|
| `GET` | `/api/settings/ai` | `SETTINGS_AI_MANAGE` | 활성 제공업체 + 보강 기본값(온도, 한도, 배포 프로필 등) |
| `PUT` | `/api/settings/ai` | `SETTINGS_AI_MANAGE` | 제공업체 자격증명 및/또는 기본값 저장 |
| `PUT` | `/api/settings/ai/deactivate` | `SETTINGS_AI_MANAGE` | 활성 제공업체 비활성화 |
| `PUT` | `/api/settings/ai/activate/{provider}` | `SETTINGS_AI_MANAGE` | 제공업체 전환 |
| `POST` | `/api/settings/ai/test-connection` | `SETTINGS_AI_MANAGE` | 연결 테스트(저장 안 함) |
| `GET` | `/api/settings/ai/prompts` | `SETTINGS_AI_MANAGE` | 편집 가능 프롬프트 + 오버라이드 |
| `POST` | `/api/settings/ai/golden-test` | `SETTINGS_AI_MANAGE` | 골든 프롬프트 회귀 테스트 실행 |

제공업체: `OPENAI`, `ANTHROPIC`, `GEMINI`, `LOCAL`.

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

## 로컬/테스트 (local 프로파일 전용)

| 메서드 | 경로 | 설명 |
|---|---|---|
| `GET` | `/data/test` | DB 초기화 및 풍부한 테스트 데이터 시드 |
| `GET` | `/data/test-api-key` | 사용 가능한 테스트 API 키 조회 |
