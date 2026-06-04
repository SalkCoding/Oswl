# 관리

이 페이지는 모든 관리자 전용 기능을 다룹니다: 사용자 관리, 역할 템플릿, 감사 로그, 보안 설정, SMTP 구성.

> 별도로 명시되지 않는 한 이 페이지의 모든 작업에는 **시스템 관리자** 권한이 필요합니다.

여기서 다루는 역할 템플릿은 **인스턴스 전역 권한**이며, 프로젝트 접근(멤버십)과는 별개입니다. [권한 레이어](Authorization-Layers.md) 참고.

---

## 사용자 관리

**설정 → 관리자 → 사용자**

### 사용자 초대

1. **사용자 초대**를 클릭합니다.
2. 사용자의 **이메일**과 **표시 이름**을 입력합니다.
3. 하나 이상의 **역할 템플릿**을 지정합니다.
4. **초대 발송** (또는 이메일이 비활성화된 경우 **생성** — 임시 비밀번호가 생성됨)을 클릭합니다.

사용자는 임시 비밀번호가 포함된 이메일을 받고 첫 로그인 시 비밀번호를 변경해야 합니다.

### 사용자 편집

| 작업 | 엔드포인트 |
|---|---|
| 표시 이름 변경 | `PUT /api/admin/users/{id}/display-name` |
| 역할 업데이트 | `PUT /api/admin/users/{id}/roles` |
| 계정 활성화 | `PUT /api/admin/users/{id}/activate` |
| 계정 비활성화 | `PUT /api/admin/users/{id}/deactivate` |
| 사용자 삭제 | `DELETE /api/admin/users/{id}` |

> 비활성화된 사용자는 로그인할 수 없지만 데이터(감사 로그, 스캔 귀속)는 보존됩니다.

---

## 역할 템플릿

**설정 → 관리자 → 역할 템플릿**

역할 템플릿은 여러 사용자에게 지정할 수 있는 이름이 있는 권한 묶음입니다.

### 내장 역할 템플릿

**빈 DB로 최초 기동** 시 다음 세 템플릿이 생성됩니다(이후 편집 가능).

| 템플릿 | 용도 |
|--------|------|
| **Admin** | 권한 전체 |
| **Developer** | 스캔·분석·조치, 라이선스 보기/보내기, VCS·CLI 키 |
| **Viewer** | 읽기 전용·보내기 |

역할 템플릿(레이어 A)과 프로젝트 멤버십(레이어 B)은 별개입니다. [권한 레이어](Authorization-Layers.md) 참고.

### 권한 레퍼런스

| 권한 | 설명 |
|---|---|
| `PROJECT_VIEW` | 프로젝트 목록 및 프로젝트 상세 보기 |
| `PROJECT_CREATE` | 새 프로젝트 등록 (Quick Import 또는 CLI) |
| `PROJECT_DELETE` | 프로젝트 휴지통으로 이동 |
| `PROJECT_RESTORE` | 삭제된 프로젝트 복원 |
| `PROJECT_PERMANENT_DELETE` | 휴지통에서 프로젝트 영구 삭제 |
| `SCAN_SUBMIT` | CLI를 통해 스캔 제출 (`POST /api/scan`) |
| `SCAN_VIEW` | 스캔 결과 보기 |
| `SCAN_HISTORY_VIEW` | 스캔 히스토리 목록 보기 |
| `SECURITY_CENTER_VIEW` | 보안 센터 CVE 목록 보기 |
| `SECURITY_CENTER_UPDATE_STATUS` | CVE 분류 상태 업데이트 |
| `SECURITY_CENTER_EXPORT` | 보안 센터 결과 내보내기 |
| `LICENSE_VIEW` | 라이선스 분석 페이지 보기 |
| `LICENSE_EXPORT` | NOTICE·SPDX SBOM 파일 다운로드 |
| `LICENSE_POLICY_MANAGE` | 라이선스 정책 항목 추가/편집/제거 |
| `SCAN_HISTORY_DELETE` | 스캔 기록 삭제 |
| `COMPONENT_DETAIL_VIEW` | 컴포넌트 상세 패널 보기 |
| `VERSION_DIFF_VIEW` | 버전 비교 보기 |
| `RISK_TREND_VIEW` | 리스크 트렌드 차트 보기 |
| `SETTINGS_AI_MANAGE` | AI 제공업체 설정 구성 |
| `SETTINGS_VCS_MANAGE` | VCS 연결 추가/제거 |
| `SETTINGS_CLI_KEY_MANAGE` | 프로젝트 CLI API 키 관리 |
| `SETTINGS_CACHE_MANAGE` | 캐시 설정 관리 |
| `SETTINGS_SECURITY_MANAGE` | SMTP 및 2FA 설정 구성 |

### 템플릿 생성

1. **새 역할 템플릿**을 클릭합니다.
2. 이름을 입력합니다 (예: "개발자", "보안 분석가", "읽기 전용").
3. 원하는 권한을 체크합니다.
4. **저장**을 클릭합니다.

---

## 보안 설정 (SMTP 및 2FA)

**설정 → 보안**

### SMTP (메일 서버)

OsWL은 이중 인증 OTP 이메일 및 사용자 초대 발송에 SMTP를 사용합니다.

| 필드 | 설명 |
|---|---|
| **메일 모드** | `DISABLED` (메일 없음), `SMTP` (표준 릴레이), `STARTTLS` / `SSL_TLS` |
| **호스트** | SMTP 서버 호스트명 |
| **포트** | SMTP 포트 (일반적으로 25, 465 또는 587) |
| **사용자명 / 비밀번호** | SMTP 자격증명 (비밀번호는 저장 시 암호화) |
| **발신자 이름 / 주소** | "보낸 사람" 표시 이름 및 주소 |

저장 전에 **테스트 이메일 발송**을 클릭하여 설정을 검증하세요.

### 이중 인증 (2FA)

| 모드 | 동작 |
|---|---|
| `DISABLED` | OTP 단계 없음 — 사용자가 이메일 + 비밀번호만으로 로그인 |
| `OPTIONAL` | OTP 사용 가능하지만 사용자가 건너뛸 수 있음 |
| `REQUIRED` | 모든 사용자가 매 로그인 시 OTP 단계를 완료해야 함 |

#### 신뢰 기기

2FA가 활성화된 경우, OTP 검증 성공 후 사용자가 브라우저를 **신뢰**로 표시할 수 있습니다. 신뢰된 기기는 설정 가능한 기간(기본값: 30일) 동안 OTP 단계를 건너뜁니다.

### 비밀번호 정책

| 설정 | 기본값 | 설명 |
|---|---|---|
| 최소 비밀번호 길이 | `8` | 초대 생성 및 비밀번호 변경 시 적용 |

---

## 감사 로그

**설정 → 관리자 → 감사 로그**

감사 로그는 모든 중요한 사용자 및 시스템 작업을 기록합니다.

| 컬럼 | 설명 |
|---|---|
| **타임스탬프** | 이벤트 발생 시각 |
| **행위자** | 사용자 이메일 또는 `SYSTEM` |
| **작업** | 이벤트 코드 (예: `SCAN.INGEST`, `USER.LOGIN`, `CVE.STATUS_UPDATE`) |
| **리소스 유형** | 영향받은 엔티티 (PROJECT, SCAN, USER, …) |
| **리소스 ID** | 영향받은 엔티티의 ID |
| **상세** | 추가 컨텍스트 (새 값, 버전 문자열 등) |

### 필터링

행위자, 작업, 리소스 유형, 날짜 범위로 필터링합니다.

### 내보내기

**CSV 내보내기**를 클릭하면 현재 필터링된 뷰를 CSV 파일로 다운로드합니다.

### 보존 기간

설정된 보존 기간보다 오래된 감사 레코드는 예약 작업에 의해 자동 삭제됩니다.

| 설정 키 | 기본값 | 설명 |
|---|---|---|
| `OSWL_AUDIT_RETENTION_MONTHS` | `6` | 이 기간(월)보다 오래된 레코드 자동 삭제 |
| `OSWL_AUDIT_MAX_PAGE_SIZE` | `200` | API 페이지당 최대 레코드 수 |

---

## AI 설정

**설정 → AI**

CVE/라이선스 요약에 사용할 LLM 제공업체와 보강 동작을 구성합니다.

| 제공업체 | 참고사항 |
|---|---|
| **비활성화** | AI 인사이트 생성 안 함 |
| **OpenAI** | API 키 + 모델 (예: `gpt-4o-mini`) |
| **Anthropic** | API 키 + 모델 |
| **Gemini** | API 키 (+ 필요 시 OpenAI 호환 base URL) |
| **로컬** | OpenAI 호환 엔드포인트 (예: Ollama) |

활성 제공업체는 **하나**만 둘 수 있습니다. 탭에서 추가로 설정할 수 있는 항목:

| 설정 | 용도 |
|---|---|
| 프롬프트 로케일 (`en` / `ko`) | `prompts.properties` vs 한국어 오버레이 |
| CVE/라이선스 배치 한도·심각도 | 스캔당 AI 호출 상한 |
| 온도 / max tokens / 일일 호출 상한 | LLM 동작 및 비용 제한 |
| 기본 배포 프로필 | 프로젝트 프로필이 없을 때 CVE 트리아지 맥락 |
| 프롬프트 오버라이드 | 키별 템플릿 수정 (`GET /api/settings/ai/prompts`) |

**API:** `GET|PUT /api/settings/ai`, `POST /api/settings/ai/test-connection`, `POST /api/settings/ai/golden-test`.  
**프로젝트별:** `PATCH /api/projects/{id}/deployment-profile`.  
**피드백:** 컴포넌트 상세 등에서 `POST /api/ai/feedback`.

---

## 캐시 설정

**설정 → 캐시**

CVE 및 라이선스 보강 데이터의 인메모리 및 영구 캐시를 제어합니다.

| 작업 | 설명 |
|---|---|
| **설정 보기** | 현재 TTL 및 캐시 크기 구성 확인 |
| **설정 업데이트** | TTL 값 변경 |
| **캐시 지우기** | 모든 캐시된 보강 데이터 초기화 — 다음 스캔 시 NVD / OSV / deps.dev에서 새로 가져옴 |

---

## 외부 API 설정

**설정 → 외부 API**

외부 데이터 소스의 속도 제한 및 API 키를 구성합니다:

* **NVD** — National Vulnerability Database API 키 (속도 제한을 30초당 5→50 요청으로 증가)
* **GitHub API** — GitHub API 호출용 PAT (속도 제한을 시간당 60→5000 요청으로 증가)
