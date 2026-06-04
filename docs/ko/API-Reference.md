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

`POST /api/scan`에는 JSON 본문에 `submitterEmail`과 `submitterPassword`도 필요하여 스캔을 인증하고 귀속시킵니다.

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
| `GET` | `/api/quick-import/jobs` | 사용자의 모든 작업 목록 |
| `GET` | `/api/quick-import/job/{jobId}` | 작업 상태 폴링 (`QuickImportJobStatus`) |
| `GET` | `/api/quick-import/job/{jobId}/stream` | **SSE** — `job-update` 이벤트(JSON), 폴링 폴백 가능 |

**단계:** `QUEUED` → `CLONING` → `PARSING` → `SCANNING` → `ENRICHING` → `DONE` | `FAILED`.  
동시 **2건** 실행(`oswl.quick-import.max-concurrent`), 초과는 FIFO 큐(`queuePosition`).  
`ENRICHING` 중 `percent`, `subPhase`, `detailLines`, `aiPreviews` 포함.

---

## GitHub OAuth / PAT

| 메서드 | 경로 | 설명 |
|---|---|---|
| `POST` | `/api/github/connect` | GitHub PAT 연결 |
| `DELETE` | `/api/github/disconnect` | GitHub 연결 제거 |
| `GET` | `/api/github/status` | 연결 상태 |
| `GET` | `/api/github/accounts` | 인증된 계정 목록 |
| `GET` | `/api/github/repos` | 접근 가능한 저장소 목록 |
| `GET` | `/api/github/branches` | 저장소의 브랜치 목록 |
| `GET` | `/api/github/branch-updated-at` | 브랜치의 마지막 커밋 날짜 |
| `DELETE` | `/api/github/accounts/{login}` | 특정 계정 제거 |

---

## CLI 스캔

| 메서드 | 경로 | 인증 | 설명 |
|---|---|---|---|
| `POST` | `/api/auth` | API 키 | API 키 검증 (레거시) |
| `GET` | `/api/scan/ping` | API 키 | 연결 및 키 유효성 확인 |
| `POST` | `/api/scan` | API 키 + 자격증명 | 의존성 스캔 제출 |
| `GET` | `/api/scan/{scanId}/status` | 세션 | 스캔 상태 폴링 |

---

## 보안 센터

| 메서드 | 경로 | 필요 권한 | 설명 |
|---|---|---|---|
| `GET` | `/projects/{id}/security-center` | `SECURITY_CENTER_VIEW` | 보안 센터 페이지 |
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

## AI 피드백

| 메서드 | 경로 | 인증 | 설명 |
|---|---|---|---|
| `POST` | `/api/ai/feedback` | 세션 | AI 요약에 대한 도움됨/안됨 피드백 |

---

## 라이선스

| 메서드 | 경로 | 필요 권한 | 설명 |
|---|---|---|---|
| `GET` | `/projects/{id}/license` | `LICENSE_VIEW` | 라이선스 분석 페이지 |

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

### 캐시

| 메서드 | 경로 | 필요 권한 | 설명 |
|---|---|---|---|
| `GET` | `/api/settings/cache` | `SETTINGS_CACHE_MANAGE` | 캐시 설정 조회 |
| `PUT` | `/api/settings/cache` | `SETTINGS_CACHE_MANAGE` | 설정 업데이트 |
| `POST` | `/api/settings/cache/clear` | `SETTINGS_CACHE_MANAGE` | 모든 캐시 초기화 |

### 외부 API (NVD, GitHub)

| 메서드 | 경로 | 설명 |
|---|---|---|
| `GET` | `/api/settings/external` | 모든 외부 API 설정 조회 |
| `PUT` | `/api/settings/external/nvd` | NVD API 키/속도 제한 설정 |
| `PUT` | `/api/settings/external/cache` | 보강 캐시 TTL 구성 |
| `GET` | `/api/settings/external/github` | GitHub API 설정 조회 |
| `PUT` | `/api/settings/external/github` | GitHub PAT 설정 |

---

## 로컬/테스트 (local 프로파일 전용)

| 메서드 | 경로 | 설명 |
|---|---|---|
| `GET` | `/data/test` | DB 초기화 및 풍부한 테스트 데이터 시드 |
| `GET` | `/data/test-api-key` | 사용 가능한 테스트 API 키 조회 |
