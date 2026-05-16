---
agent: agent
description: OsWL 풀 BVT(Build Verification Test) 자동 실행 — DB 초기화부터 모든 기능 체크까지 브라우저 자동화로 검증.
---

# OsWL Full BVT — QA Runner

사용자가 **"QA 진행"**, **"BVT 돌려"**, **"풀 테스트"** 또는 `/qa` 슬래시 커맨드로 이 프롬프트를 호출하면, 아래 절차를 **그대로** 자동 수행한다. 사용자에게 추가 확인을 묻지 않고 바로 시작한다.

> 호출되는 도구: `run_in_terminal`, `open_browser_page`, `navigate_page`, `click_element`, `type_in_page`, `screenshot_page`, `get_terminal_output`, `read_page`, `run_playwright_code`. 필요 시 `tool_search`로 미리 로드한다.

---

## 0. 사전 준비 (Setup)

1. **서버 상태 확인**
   - `Invoke-WebRequest http://localhost:8080/login -UseBasicParsing -TimeoutSec 3` 으로 핑 → 200/302면 **이미 켜진 상태**.
   - 켜져 있으면 → **기존 서버 종료 필요**. 사용자에게 1회 확인 후 종료 (`Stop-Process` 로 `bootRun` 프로세스 kill, 또는 `gradlew --stop`).
2. **DB 완전 초기화** (워크스페이스 루트에서)
   ```powershell
   if (Test-Path "oswl-db.mv.db")    { Remove-Item "oswl-db.mv.db" -Force }
   if (Test-Path "oswl-db.trace.db") { Remove-Item "oswl-db.trace.db" -Force }
   if (Test-Path "oswl-db.lock.db")  { Remove-Item "oswl-db.lock.db" -Force }
   ```
3. **서버 기동** (async 모드, 시작 로그 대기)
   ```powershell
   ./gradlew bootRun
   ```
   `Started OswlApplication` 로그가 보일 때까지 `get_terminal_output`으로 폴링 (최대 3분).
4. **브라우저 오픈**: `http://localhost:8080` → 자동으로 `/setup`로 리다이렉트 되어야 함 (DB 비어 있으니 최초 어드민 설정 화면).

### QA 자격 증명 (테스트 전용 — 매 QA마다 동일)

| 항목 | 값 |
|------|---|
| Email | `qa@oswl.local` |
| Password | `Qa!Test1234` |
| Display Name | `QA Tester` |

### OTP 코드 추출 방법
- 로컬 SMTP는 GreenMail. 메일은 발송되지 않고 **서버 로그에 `*** OTP CODE: NNNNNN ***` 형식으로 출력**됨.
- `get_terminal_output`(서버 터미널 ID)에서 가장 최근 OTP 라인을 정규식 `\*\*\* OTP CODE: (\d{6})` 로 파싱한다.

### 테스트 데이터 시드
- 로그인 후 `GET http://localhost:8080/data/test` 호출 → 모든 데이터 리셋 후 풍부한 테스트 셋 생성. **반드시 P-01 진입 직전에 1회 실행**.
- CLI 토큰이 필요하면 `GET /data/test-api-key` 로 발급받은 `oswl_xxx` 토큰을 그대로 사용.

---

## 1. 실행 규칙

- **순차 실행**: 각 케이스를 표 순서대로 진행. 케이스 사이에 페이지 이동 후 DOM 안정 대기.
- **결과 기록**: 케이스마다 `PASS / FAIL / SKIP(사유)` 표시. FAIL시 스크린샷 1장 + 콘솔/네트워크 에러 짧게 캡처.
- **스킵 허용 케이스 (외부 의존):**
  - **P-03 Quick Import 실제 임포트** — GitHub PAT 필요, **모달 UI까지만** 검증하고 PAT 입력 단계는 SKIP(REASON: PAT 미주입).
  - **SS-03 메일 테스트** — GreenMail로 송신 자체는 가능. 응답 200만 확인.
  - **AI-06 Test Connection** — 실제 API 키 없으니 버튼 활성/비활성 토글만 확인, 호출은 SKIP.
- **DB 검증**이 필요한 케이스(예: A-07 단일 세션)는 새 incognito context를 열어 동일 계정으로 로그인 후, 첫 세션 페이지 새로고침 시 `/login?displaced=true` 로 튕기는지 확인.
- 테스트 종료 후 **최종 리포트**를 마크다운 표로 출력. 합계: `PASS X / FAIL Y / SKIP Z (총 N)`.

---

## 2. BVT 체크리스트 (총 ~95)

### 🔐 인증 (Auth)
| # | 케이스 | 절차 / 확인 |
|---|--------|-----|
| A-00 | Setup 화면 | DB 빈 상태에서 `/` → `/setup` 리다이렉트, 폼 표시 |
| A-01 | 최초 어드민 생성 | QA 자격증명으로 폼 제출 → `/login?setup` |
| A-02 | 로그인 폼 | `/login` GET — 이메일/비밀번호 입력 필드 |
| A-03 | 로그인 실패 | 잘못된 비번 → 에러 메시지, 폼 재표시 |
| A-04 | 로그인 성공 (1차) | 올바른 자격증명 → `/login/otp-verify` 리다이렉트 |
| A-05 | OTP 페이지 | 6자리 입력 폼, 마스킹된 이메일(`qa@…local`), 만료 카운트다운 표시 |
| A-06 | OTP 타이머 확인 | 타이머가 정상작동하는지 시작 시, 그리고 재전송 이후도 5분으로 초기화 되는지 확인 |
| A-07 | OTP 실패 | 잘못된 코드 → 에러 메시지, 재입력 가능 |
| A-08 | OTP 재발송 | "Resend" 클릭 → 200, 새 OTP 로그 출력 |
| A-09 | OTP 성공 | 서버 로그에서 코드 추출 → 입력 → `/projects` |
| A-10 | 단일 세션 | 새 브라우저 컨텍스트로 동일 계정 로그인 → 첫 세션이 `/login?displaced=true&from=…` 로 튕기는지, 앰버 알림 표시 |
| A-11 | 로그아웃 | 우상단 메뉴 → Logout → `/login?logout` |

### 📁 Projects (시드 후 진행)
| # | 케이스 | 확인 |
|---|--------|------|
| P-00 | 테스트 데이터 시드 | `/data/test` 호출 → 200, projects 페이지에 4개 프로젝트 (`backend-api`, `frontend-dashboard`, `ml-pipeline`, `new-service`) |
| P-01 | 목록 렌더링 | Active/Trash 탭 표시. 카드: 이름/마지막 스캔/Security Risk(C/H/M/L/U)/License Risk(R/C/P/U) |
| P-02 | CLI Integration 모달 | "CLI Integration" 버튼 → 모달 오픈, install/auth/ping/scan 명령 표시, `--username/--password/--project` 인자 표시 |
| P-03 | Quick Import 모달 (UI) | "Quick Import" → `/projects/quick-import` 또는 모달 진입, GitHub PAT 입력 화면까지 도달 (PAT 입력은 SKIP) |
| P-04 | 프로젝트 액션 메뉴 | 카드 우측 ⋯ 버튼 → Rename / Move to Trash / Delete |
| P-05 | Move to Trash | 한 프로젝트 → Trash → Active에서 사라지고 Trash 탭에 등장 |
| P-06 | Restore | Trash 탭에서 Restore → 다시 Active |
| P-07 | Permanent Delete | Trash → Permanently Delete → DB에서 완전 제거 (목록에서 사라짐) |
| P-08 | Bulk Restore | Trash에 여러 개 두고 다중 선택 → restore-selected |
| P-09 | Bulk Permanent Delete | trash/selected 다중 영구삭제 |
| P-10 | Trash 비우기 | trash/all → 모든 휴지통 비움 |
| P-11 | JSON list 엔드포인트 | `GET /projects/list` (Accept: json) → 200, 배열 응답 |

### 🛡️ Security Center
| # | 케이스 | 확인 |
|---|--------|------|
| SC-01 | 페이지 로드 | `/projects/{id}/security-center` — 상단 요약(C/H/M/L/U), 컴포넌트 테이블 |
| SC-02 | 버전 드롭다운 | Topbar 버전 버튼 → 드롭다운, 5개↑ 시 스크롤, 선택 시 해당 scan 전환 |
| SC-03 | 검색 | 컴포넌트명 부분 일치 검색 (필터 미적용 상태에서도 동작) |
| SC-04 | 리스크 필터 | C/H/M/L/U 체크박스 토글 → 행 수 변동 |
| SC-05 | Status 필터 | Reviewed / Not Reviewed / Ignored 체크박스 |
| SC-06 | 컴포넌트 클릭 | 행 클릭 → `/components/{compId}` 이동 |
| SC-07 | Bulk Actions | 다중 체크 → 버튼 활성 → Mark Reviewed / Unreviewed / Ignore / Unignore (PATCH bulk-status) |
| SC-08 | Export PDF/CSV | 드롭다운 → 두 옵션 표시 (다운로드 트리거) |
| SC-09 | Defer (Exception) | Defer 버튼 → 모달: 사유/만료일/검토자/PR 설명 |
| SC-10 | Review 토글 | 단일 행 체크박스 → Reviewed 토글 |
| SC-11 | Version Diff 링크 | 버전 드롭다운 하단 "Version Diff..." 링크 동작 |

### 📦 Component Detail
| # | 케이스 | 확인 |
|---|--------|------|
| CD-01 | 페이지 로드 | 라이브러리명/버전/생태계 표시 |
| CD-02 | CVE 목록 | CVE ID, 심각도, CVSS, 설명, 패치 버전 |
| CD-03 | 라이선스 정보 | 라이선스명/리스크/의무사항 |
| CD-04 | AI 요약 영역 | AI 미설정 시 "Configure AI" 안내 또는 영역 비활성 |
| CD-05 | 사용 프로젝트 수 | "N Projects" 라벨 |
| CD-06 | HTMX fragment | `curl -H "HX-Request: true"` → fragment HTML만 반환 |

### 📄 License
| # | 케이스 | 확인 |
|---|--------|------|
| L-01 | 페이지 로드 | `/projects/{id}/license` — 라이선스 그룹별 카드, R/C/P/U 표시 |
| L-02 | 버전 선택 | scanId 쿼리로 다른 스캔 라이선스 조회 |
| L-03 | 의무사항 | 라이선스별 의무사항 카운트 |

### 📈 Risk Trend
| # | 케이스 | 확인 |
|---|--------|------|
| RT-01 | 페이지 로드 | `/projects/{id}/risk-trend` — 최근 N개 스캔(`oswl.risk-trend.limit=10`) |
| RT-02 | Security 차트 | Chart.js 라인 차트 렌더 |
| RT-03 | License 차트 | 동일 |
| RT-04 | 델타 표시 | 직전 스캔 대비 증감 뱃지 |

### 🔀 Version Diff
| # | 케이스 | 확인 |
|---|--------|------|
| VD-01 | From/To 선택 | `/projects/{id}/version-diff` 드롭다운 |
| VD-02 | 요약 카드 | Added / Removed / Updated / New Threats 카운트 |
| VD-03 | New Threats(추가) | 새 컴포넌트 + Risk 있음 → New Threat |
| VD-04 | New Threats(격상) | 기존 컴포넌트 Risk 격상 → New Threat |
| VD-05 | New Threats 탭 | 해당 컴포넌트만 표시 |
| VD-06 | Updated 탭 | 버전 변경 컴포넌트 |
| VD-07 | Added 탭 | 새 추가(Risk 없음) |
| VD-08 | Removed 탭 | 제거된 컴포넌트 |

### 🕐 Scan History
| # | 케이스 | 확인 |
|---|--------|------|
| SH-01 | 목록 | 버전/날짜/상태(SUCCESS/FAILED/PENDING)/컴포넌트 수 |
| SH-02 | 개별 스캔 삭제 | DELETE → 204 |

### 📖 Glossary
| # | 케이스 | 확인 |
|---|--------|------|
| G-01 | 페이지 로드 | `/glossary` 용어 목록 |

### ⚙️ Settings — Administration
| # | 케이스 | 확인 |
|---|--------|------|
| SA-01 | 사용자 목록 | 이름/이메일/역할/상태/마지막 로그인 |
| SA-02 | Invite User | 모달 → 신규 사용자 생성 |
| SA-03 | Activate / Deactivate | 상태 토글 |
| SA-04 | Edit (display name / roles) | PUT 호출 성공 |
| SA-05 | Delete User | DELETE 후 목록에서 제거 |
| SA-06 | Permission Templates 탭 | 역할 템플릿/권한 목록 |
| SA-07 | Template CRUD | 생성/수정/삭제 |
| SA-08 | Audit Log 탭 | 시간/Actor/IP/Action/Target/Detail 컬럼 |
| SA-09 | Audit Log 필터 | Last 7/30/90/All, All Users, All Actions |
| SA-10 | Audit Log Export CSV | `/api/admin/audit-logs/export.csv` 다운로드 |

### ⚙️ Settings — Security
| # | 케이스 | 확인 |
|---|--------|------|
| SS-01 | 탭 로드 | OTP/세션 정책/비밀번호 정책 표시 |
| SS-02 | 저장 | PUT → 200, Audit Log에 `SECURITY_SETTING.UPDATE` |
| SS-03 | Test Mail | "Test Mail" 클릭 → 200, GreenMail 로그에 메일 도착 |

### ⚙️ Settings — AI
| # | 케이스 | 확인 |
|---|--------|------|
| AI-01 | 탭 로드 | 5옵션: Do not use / OpenAI / Anthropic / Google / Local |
| AI-02 | OpenAI 입력 | API Key / Endpoint(opt) / Model 드롭다운 |
| AI-03 | Anthropic 입력 | API Key / Model |
| AI-04 | Google 입력 | API Key / Model |
| AI-05 | Local/Ollama | Base URL / Model (API Key 없음) |
| AI-06 | Test Connection | 버튼 활성 토글 (실제 호출 SKIP) |
| AI-07 | Save (PUT /api/settings/ai) | 200 |
| AI-08 | Activate Provider | `PUT /api/settings/ai/activate/{provider}` |
| AI-09 | Deactivate | `PUT /api/settings/ai/deactivate` |

### ⚙️ Settings — VCS
| # | 케이스 | 확인 |
|---|--------|------|
| VC-01 | 탭 로드 | 연결 목록 |
| VC-02 | 연결 추가 | POST 200 (잘못된 토큰은 PAT 검증 단계에서 실패 허용 — UI 흐름만) |
| VC-03 | 연결 삭제 | DELETE 204 |

### ⚙️ Settings — CLI Keys
| # | 케이스 | 확인 |
|---|--------|------|
| CK-01 | 글로벌 키 목록 | 모든 프로젝트의 키 노출 |
| CK-02 | 키 발급 | POST → 응답 1회만 `oswl_xxx` 평문 표시 |
| CK-03 | 키 토글 | PATCH /toggle → 활성/비활성 변경 |

### ⚙️ Settings — Cache
| # | 케이스 | 확인 |
|---|--------|------|
| CC-01 | 탭 로드 | Custom TTL / Always Refresh / Permanent 옵션 |
| CC-02 | Custom TTL 입력 | 숫자 + hour(s)/day(s) 단위 |
| CC-03 | Save | PUT 200 |
| CC-04 | Cache Clear | POST `/api/settings/cache/clear` → 200 |

### ⚙️ Settings — External (NVD / GitHub Enterprise)
| # | 케이스 | 확인 |
|---|--------|------|
| EX-01 | NVD 설정 GET | `/api/settings/external` 200 |
| EX-02 | NVD 설정 PUT | `/api/settings/external/nvd` 저장 |
| EX-03 | NVD Cache PUT | `/api/settings/external/cache` 저장 |
| EX-04 | GitHub Enterprise GET/PUT | `/api/settings/external/github` |

### 🔌 CLI 공개 API (oswl_xxx 토큰 사용)
> `GET /data/test-api-key` 로 발급받은 토큰을 `Authorization: Bearer oswl_xxx` 헤더로 호출.

| # | 케이스 | 확인 |
|---|--------|------|
| CLI-01 | Ping | `GET /api/scan/ping` → `{ status, projectId }` |
| CLI-02 | Scan 제출 | `POST /api/scan` (의존성 페이로드) → `{ scanId, version, status }` |
| CLI-03 | 스캔 상태 | `GET /api/scan/{scanId}/status` → `{ scanId, status, componentCount }` |
| CLI-04 | CLI 인증 | `POST /api/auth` (이메일/비번) → `{ token }` |

### ❌ 에러 페이지
| # | 케이스 | 확인 |
|---|--------|------|
| E-01 | 404 | 존재하지 않는 URL → 부엉이 일러스트 + "Go to Home" |
| E-02 | 403 | 비권한 사용자로 admin 페이지 접근 (또는 `/error/403`) |
| E-03 | 401 | 비로그인 상태 보호 페이지 접근 |
| E-04 | 500 | `/error/500` 직접 → 페이지 렌더 |
| E-05 | 503 | `/error/503` 직접 → 페이지 렌더 |

---

## 3. 최종 리포트 형식

```
## OsWL BVT Report — <ISO timestamp>

| Section | PASS | FAIL | SKIP |
|---------|------|------|------|
| Auth (A) | x/11 | … | … |
| Projects (P) | … | … | … |
| Security Center (SC) | … | … | … |
| ... 이하 동일 ... |

### FAILURES
- **<케이스 ID>**: 한 줄 원인 요약. 스크린샷: <경로>

### SKIPPED
- **<케이스 ID>**: 사유

**TOTAL: PASS X / FAIL Y / SKIP Z (총 N)**
```

리포트 출력 후 서버는 **켜진 상태로 유지** (다음 디버깅을 위해). 사용자가 종료를 요청하면 `gradlew --stop` 으로 정리.
