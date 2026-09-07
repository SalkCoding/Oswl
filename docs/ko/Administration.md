# 관리

[1.0.5.1 변경 사항](Whats-New-v1.0.5.1.md)

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

### 본인 계정 탈퇴 (self-service)

**시스템 관리자**를 제외한 로그인 사용자는 사용자 메뉴의 **계정 탈퇴**에서 현재 비밀번호 확인 후 본인 계정을 삭제할 수 있습니다.

| 항목 | 동작 |
|---|---|
| 엔드포인트 | `POST /api/my/delete-account` — 사용자 ID는 **세션 principal에서만** 취득 (경로/본문으로 타인 ID 지정 불가) |
| 시스템 관리자 | 본인 탈퇴 불가 |
| 삭제 대상 | `users` 행, `project_members`, 저장된 VCS 토큰 |
| 보존 | 기존 **감사 로그** 전체( actor 이메일/이름/id 스냅샷), 프로젝트·스캔·import 이력 |
| 감사 액션 | `USER.SELF_DELETE` — 사용자 행 삭제 **전**에 기록하여 `actor_user_id`·표시 이름 보존 |

관리자가 삭제하는 경우는 기존과 같이 `USER.DELETE` (`DELETE /api/admin/users/{id}`)입니다.

---

## 역할 템플릿

**설정 → 관리자 → 역할 템플릿**

역할 템플릿은 여러 사용자에게 지정할 수 있는 이름이 있는 권한 묶음입니다.

### 내장 역할 템플릿

**빈 DB로 최초 기동** 시 다음 세 템플릿이 생성됩니다(이후 편집 가능).

| 템플릿 | 용도 |
|--------|------|
| **Admin** | 권한 전체 |
| **Developer** | 스캔·분석·조치, 라이선스 조회/내보내기, VCS·CLI 키 |
| **Viewer** | 읽기 전용·내보내기 가능 |

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
| `ORG_DASHBOARD_VIEW` | **v1.0.4** — 조직 대시보드(`/org-dashboard`) 조회 |
| `AUDIT_LOG_VIEW` | **v1.0.4** — 감사 로그 조회 |
| `AUDIT_LOG_EXPORT` | **v1.0.4** — SIEM 적재용 감사 로그 내보내기 |
| `SETTINGS_JIRA_MANAGE` | **v1.0.4** — Jira 연동 관리 |
| `SETTINGS_SNAPSHOT_MANAGE` | **v1.0.4** — 오프라인 스냅샷 번들 관리 |

> v1.0.4의 5개 권한은 기존 역할 템플릿에 자동 추가되지 **않으므로** 미부여 상태로 시작합니다. 필요한 역할에만 명시적으로 부여하세요 — 특히 `AUDIT_LOG_EXPORT`는 감사 기록을 플랫폼 외부로 내보내는 권한입니다.

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

## 서버 프로퍼티 (application.yaml)

`application.yaml` 또는 환경 변수(Spring relaxed binding)로 설정하는 인스턴스 수준 보안 플래그입니다. 설정 UI에서 변경할 수 없으며, 적용하려면 재시작이 필요합니다.

| 설정 키 | 환경 변수 | 기본값 | 설명 |
|---|---|---|---|
| `oswl.quick-import.allow-build-exec` | `OSWL_QUICK_IMPORT_ALLOW_BUILD_EXEC` | `false` | `false`이면 Quick Import가 manifest를 **정적으로만** 파싱하며, 클론된 저장소 안의 빌드 도구(`mvnw`, `gradlew`, `dotnet`)를 실행하지 않습니다. `true`이면 빌드 기반 버전 해결이 가능하지만, 저장소의 빌드 스크립트가 OsWL 호스트에서 실행되므로 **신뢰하는 저장소만** import하는 환경에서만 켜야 합니다. |
| `oswl.security.trusted-proxies` | `OSWL_SECURITY_TRUSTED_PROXIES` | *(비어 있음)* | 신뢰하는 리버스 프록시 IP 목록(쉼표 구분). 직접 연결된 peer가 이 목록에 있을 때만 클라이언트 IP 판별(감사 로그, 속도 제한)에 `X-Forwarded-For` 헤더를 사용하고, 비어 있으면(기본값) 해당 헤더를 무시합니다. OsWL이 직접 제어하는 프록시 뒤에서 동작할 때만 설정하세요. |
| `oswl.timezone` | `OSWL_TIMEZONE` | `Asia/Seoul` | 시간에 민감한 기능(현재는 AI 사용량 추적 — 호출이 집계되는 "하루" 기준)에 사용하는 타임존입니다. 배포 환경의 영업일 기준이 기본값과 다른 경우에만 변경하세요. |

---

## 감사 로그

**설정 → 관리자 → 감사 로그**

감사 로그는 모든 중요한 사용자 및 시스템 작업을 기록합니다.

| 컬럼 | 설명 |
|---|---|
| **타임스탬프** | 이벤트 발생 시각 |
| **행위자** | 사용자 이메일 또는 `SYSTEM` |
| **작업** | 이벤트 코드 (예: `SCAN.INGEST`, `AUTH.LOGIN_SUCCESS`, `LICENSE.EXPORT`) |
| **리소스 유형** | 영향받은 엔티티 (PROJECT, SCAN, USER, …) |
| **리소스 ID** | 영향받은 엔티티의 ID |
| **상세** | 추가 컨텍스트 (새 값, 버전 문자열 등) |

### 필터링

행위자, 작업(UI에서 auth·사용자·프로젝트·스캔·CLI 키·컴포넌트·설정 등으로 그룹화), 날짜 범위로 필터링합니다.

**사용자 작업 코드**에는 `USER.SELF_DELETE`(본인 탈퇴)와 `USER.DELETE`(관리자 삭제)가 포함됩니다.

### 내보내기

**CSV 내보내기**를 클릭하면 현재 필터링된 뷰를 CSV 파일로 다운로드합니다.

**SIEM 내보내기 (v1.0.4)** — `GET /api/admin/audit-logs/export?format=jsonl|cef`는 동일한 필터 결과를 SIEM에 바로 적재 가능한 포맷(기본 JSON Lines, 또는 ArcSight CEF)으로 스트리밍합니다. `AUDIT_LOG_EXPORT` 권한이 필요하며, 내보내기 행위 자체가 `AUDIT_LOG.EXPORT`로 기록됩니다.

v1.0.4의 작업 코드는 필터 UI에서 **모니터링**(`MONITOR.*`), **연동**(`JIRA.SETTINGS_UPDATE`), **관리**(`ORG_DASHBOARD.VIEW`, `AUDIT_LOG.EXPORT`, `SNAPSHOT.IMPORT` / `SNAPSHOT.EXPORT` / `SNAPSHOT.WANTED_LIST_EXPORT`), **캐시**(`CACHE.UPDATE_TTL`, `CACHE.CLEAR`)로 그룹화되며, 신규 내보내기·게이트 코드(`SBOM.EXPORT`, `VEX.EXPORT`, `SARIF.EXPORT`, `COMPLIANCE_REPORT.VIEW`, `GATE.EVALUATE`, `GATE.GITHUB_PUBLISH`, `PROJECT.BATCH_PR`, `SBOM.IMPORT`, `COMPONENT.JIRA_TICKET`)도 함께 제공됩니다.

### 보존 기간

설정된 보존 기간보다 오래된 감사 레코드는 예약 작업에 의해 자동 삭제됩니다.

| 설정 키 | 기본값 | 설명 |
|---|---|---|
| `OSWL_AUDIT_RETENTION_MONTHS` | `6` | 이 기간(월)보다 오래된 레코드 자동 삭제 |
| `OSWL_AUDIT_MAX_PAGE_SIZE` | `200` | API 페이지당 최대 레코드 수 |

---

## 조직 대시보드 (v1.0.4)

`/org-dashboard`는 모든 프로젝트를 하나의 포트폴리오 화면으로 롤업합니다 — 심각도 총계, 최악 프로젝트 랭킹, KEV 등재 CVE 수, 라이선스 경고.

`ORG_DASHBOARD_VIEW`(또는 `SYSTEM_ADMIN`)가 필요합니다. 권한이 부여되면 프로젝트 목록·프로젝트 상세·버전 비교 화면 상단 바에 진입점이 표시됩니다.

---

## 모니터링 엔드포인트 (v1.0.4)

| 엔드포인트 | 용도 |
|---|---|
| `/actuator/health` | 헬스 체크 |
| `/actuator/info` | 빌드·버전 정보 |
| `/actuator/prometheus` | Prometheus 스크랩용 micrometer 메트릭 |

세 엔드포인트 모두 관리자 권한이 필요합니다. Prometheus 스크랩 설정은 `application-prod.yaml`의 `management` 블록에 있습니다.

### 비즈니스 메트릭 & Grafana

기본 JVM/HTTP 미터 외에도 OsWL은 다음 비즈니스 메트릭을 기록합니다 (모두 `/actuator/prometheus`로 노출되며, Prometheus 이름 기준 — 점(.)은 밑줄(_)로 변환됩니다):

| 메트릭 | 타입 | 태그 | 설명 |
|---|---|---|---|
| `oswl_scan_duration_seconds` | Timer | `outcome` (`completed`\|`failed`) | 스캔 파이프라인 전체 소요 시간 |
| `oswl_quickimport_queue_depth` | Gauge | — | 워커 슬롯을 기다리는 Quick Import 작업 수 |
| `oswl_quickimport_running` | Gauge | — | 현재 실행 중인 Quick Import 작업 수 |
| `oswl_components_ingested_total` | Counter | `ecosystem` | 스캔 인제스트로 저장된 컴포넌트 수 |
| `oswl_ai_calls_total` | Counter | `provider` | 기록된 AI 호출 수 |
| `oswl_ai_tokens_total` | Counter | `provider`, `direction` (`in`\|`out`) | AI 프롬프트/완성 토큰 수 |
| `oswl_ai_cost_usd_total` | Counter | `provider` | 추정 AI 비용 (USD) |
| `oswl_gate_evaluations_total` | Counter | `outcome` (`pass`\|`fail`) | 시큐리티 게이트 평가 수 |
| `oswl_external_api_calls_total` | Counter | `source` (`depsdev`, `osv`, `epss`, `kev`, `github-advisory`, `nvd`), `outcome` (`success`\|`failure`\|`ratelimited`) | 외부 데이터 소스 호출 수 |

이 메트릭들을 다루는 Grafana 대시보드가 [`deploy/observability/grafana/oswl-dashboard.json`](../../deploy/observability/grafana/oswl-dashboard.json)에 포함되어 있습니다. **Dashboards → New → Import**로 임포트하면 Prometheus 데이터소스를 선택하라는 prompt가 표시되므로 JSON을 직접 수정할 필요가 없습니다.

---

## 오프라인 스냅샷 번들 (v1.0.4)

**설정 → 관리자 → 오프라인 스냅샷**

폐쇄망(에어갭) 배포(`OSWL_AIRGAPPED_ENABLED=true`)에서는 취약점·위협 인텔 데이터(OSV, deps.dev, FIRST.org EPSS, CISA KEV)가 라이브 API 대신 반입된 스냅샷에서 제공됩니다 — 외부로 나가는 HTTP 요청은 전혀 시도되지 않으며, 스냅샷에 없는 컴포넌트는 "데이터 없음"으로 표시됩니다.

`SYSTEM_ADMIN` 또는 `SETTINGS_SNAPSHOT_MANAGE` 권한이 필요합니다. [v1.0.4 새로운 기능](Whats-New-v1.0.4.md) 참고.

번들은 v2 포맷입니다: 각 JSONL 파일의 체크섬이 `meta.json`에 기록되며, 소스별 출처 정보(`bundleId`, `builtAt`, `asOf`, `origin`)도 함께 저장됩니다. 체크섬이 일치하지 않으면 어떤 데이터도 기록하지 않고 반입을 거부합니다.

| 작업 | 엔드포인트 |
|---|---|
| 번들 상태 | `GET /api/admin/snapshot` |
| 반입 (파일 업로드) | `POST /api/admin/snapshot/import` (multipart, 선택적으로 `?mode=replace\|merge`) |
| 반입 (서버 경로) | `POST /api/admin/snapshot/import-from-path` |
| 내보내기 | `GET /api/admin/snapshot/export` |
| 원하는 목록(wanted-list) | `GET /api/admin/snapshot/wanted-list` |

반입은 스트리밍 방식으로 처리되며(업로드 전체를 메모리에 버퍼링하지 않음) `SNAPSHOT.IMPORT`로 감사 기록됩니다. 에어갭 인스턴스에서는 반입 직후 메모리 내 KEV 카탈로그가 다시 로드되어 새 스냅샷이 즉시 적용됩니다.

### 정의 상태 및 신선도(staleness)

**정의 상태(Definition Status)** 카드는 소스별로 한 행씩 나열합니다 — `OSV`, `deps.dev (versions)`, `deps.dev (advisories)`, `FIRST.org EPSS`, `CISA KEV` — 레코드 수, 업스트림 **기준일(as-of)**, origin, 반입 시각과 함께. 제목 옆의 신선도 배지는 (번들 빌드 시각이나 반입 시각이 아니라) **가장 오래된** 소스의 기준일을 기준으로 계산됩니다:

| 배지 | 의미 |
|---|---|
| 최신 (녹색) | 가장 오래된 정의가 `staleness-warn-days` 이내 |
| 업데이트 권장 (황색) | `staleness-warn-days`보다 오래됨 |
| 오래됨 (적색) | `staleness-critical-days`보다 오래됨 |

| 설정 키 | 환경 변수 | 기본값 | 설명 |
|---|---|---|---|
| `oswl.airgapped.staleness-warn-days` | `OSWL_AIRGAPPED_STALENESS_WARN_DAYS` | `7` | 가장 오래된 정의 기준 이 일수를 넘으면 배지가 황색으로 전환 |
| `oswl.airgapped.staleness-critical-days` | `OSWL_AIRGAPPED_STALENESS_CRITICAL_DAYS` | `30` | 이 일수를 넘으면 배지가 적색으로 전환 |

빌더가 원하는 목록(wanted-list)을 통해 요청받았지만 업스트림에서 찾지 못했거나 확실하게 평가하지 못한 컴포넌트가 있으면 **미해결 (업스트림 데이터 없음)** 행이 표시됩니다 — 이런 컴포넌트는 "확인된 안전"이 아니라 "데이터 없음"으로 취급하세요.

### 반입 모드

- **번들 기본값** — 델타로 빌드된 v2 번들은 merge로 반입되고, 그 외에는 replace로 반입됩니다.
- **Replace** — 번들에 포함된 각 소스를 먼저 비운 후 기록합니다.
- **Merge** — 키 기준으로 upsert하며 삭제 마커(tombstone)를 존중하므로, 델타 번들로 폐기된 항목을 제거할 수도 있습니다.

파일 업로드 외에도 **경로에서 반입(Import from Path)**은 서버 디스크에 이미 있는 번들을 읽습니다. `oswl.airgapped.import-dir`(`OSWL_AIRGAPPED_IMPORT_DIR`)로 지정한 디렉터리 아래의 파일만 허용하며 realpath 검증으로 강제됩니다. 값이 비어 있으면 이 엔드포인트 자체가 비활성화됩니다.

### 이 인스턴스를 위한 정의 빌드하기 (원하는 목록)

**원하는 목록 다운로드**를 클릭하면 이 인스턴스가 스캔한 모든 고유 (ecosystem, name, version) 조합을 JSONL로 내보내므로, 인터넷에 연결된 머신에서 `oswl-vdb` 빌더가 전체 업스트림을 미러링하는 대신 정확히 그 컴포넌트들의 정의만 가져올 수 있습니다. 파일에는 ecosystem/name/version만 포함되며 — 프로젝트 이름, 저장소 URL, 파일 경로는 포함되지 않습니다. 인터넷에 연결된 머신에서 다음을 실행하세요:

```bash
scripts/oswl-vdb/oswl-vdb.sh build --wanted wanted-list.jsonl --sources osv,epss,kev,depsdev --out bundle.zip
```

(Windows에서는 `oswl-vdb.ps1`), 이후 생성된 `bundle.zip`을 반입 카드에서 업로드하세요. 같은 스크립트는 반입 전 번들을 확인하기 위한 `verify`, `inspect` 하위 명령도 제공합니다.

---

## AI 설정

**설정 → AI**

CVE/라이선스 요약에 사용할 LLM 제공업체와 보강 동작을 구성합니다.

| 제공업체 | 참고사항 |
|---|---|
| **비활성화** | AI 인사이트 생성 안 함 |
| **OpenAI** | API 키 + 모델 (예: `gpt-5.6-terra`) |
| **Anthropic** | API 키 + 모델 (예: `claude-opus-5`). `temperature` 오버라이드는 적용되지 않습니다 — 최신 Claude 모델은 샘플링 파라미터를 거부하므로, 응답 스타일은 프롬프트로 조정됩니다 |
| **Gemini** | API 키 (+ 필요 시 OpenAI 호환 base URL, 예: `gemini-3.1-pro`) |
| **로컬** | OpenAI 호환 엔드포인트 (예: Ollama — 드롭다운에 `qwen3`, `gemma3`, `deepseek-r1` 등 인기 모델 태그가 제안됨) — 또는 아래 내장 AI 사이드카 |

각 프로바이더의 모델 입력란은 자유 입력 콤보박스입니다: 드롭다운에는 현재 모델이 제안으로 표시되지만, 계정에서 접근 가능한 어떤 모델 ID든 직접 입력할 수 있습니다.

내장 AI는 별도로 설치한 llama.cpp 실행 파일을 사용하며, 기본 다운로드 모델은 **Qwen3.5-2B Q4_K_M**입니다. **Gemma 4 E2B**는 선택적으로 직접 설치합니다. 실행 파일은 `embedded-ai/llama/`, 모델은 `embedded-ai/model/<계열>/`에 둡니다. 부팅 시 미리 받기는 다운로드만 수행하며 서버 실행이나 LOCAL 활성화는 하지 않습니다. 기본 다운로드는 Hugging Face의 고정 리비전을 사용하고 SHA-256 및 크기를 검증합니다. 기본 대체 미러는 없으며, 에어갭 모드에서는 다운로드하지 않습니다. 모델을 바꾸려면 설정에서 중지한 뒤 모델을 선택·저장하고 다시 시작하세요. 최신 요구 사항과 설정은 [내장 AI](Embedded-AI.md)를 참고하세요.

활성 제공업체는 **하나**만 둘 수 있습니다. 탭에서 추가로 설정할 수 있는 항목:

| 설정 | 용도 |
|---|---|
| 프롬프트 로케일 (`en` / `ko` / `ja`) | `prompts.properties` vs 한국어·일본어 오버레이 |
| CVE/라이선스 배치 한도·심각도 | 스캔당 AI 호출 상한 |
| 온도 / max tokens / 일일 호출 상한 | LLM 동작 및 비용 제한 (Anthropic에는 온도가 적용되지 않음 — 위 참고) |
| 기본 배포 프로필 | 프로젝트에 프로필이 없을 때 CVE 트리아지 **및** 라이선스 위험 평가에 사용되는 맥락 — 사내 도구, 네트워크 서비스, 배포되는 소프트웨어는 의무가 크게 다릅니다 |
| 프롬프트 오버라이드 | 키별 템플릿 수정 (`GET /api/settings/ai/prompts`) |

**연결 테스트**는 토큰을 전혀 소비하지 않습니다: 완료 요청을 보내는 대신 프로바이더가 제공하는 모델 목록을 조회하므로(OpenAI/Gemini/Ollama는 `GET {base}/models`, Anthropic은 `GET /v1/models`), 인증 정보와 연결 가능 여부 확인이 무료이며 일일 호출 상한에도 반영되지 않습니다. 설정된 모델 ID가 계정이 접근 가능한 모델 목록에 없으면 테스트는 여전히 성공하지만 경고가 표시되어, 오타나 아직 받지 않은 로컬 모델을 다음 스캔이 아니라 즉시 알 수 있습니다.

**API:** `GET|PUT /api/settings/ai`, `POST /api/settings/ai/test-connection`, `POST /api/settings/ai/golden-test`.  
**내장 AI:** `GET /api/settings/ai/embedded`, `POST .../embedded/start?model=`, `POST .../embedded/stop`, `PUT .../embedded/config` — [API 레퍼런스 — AI](API-Reference.md#ai) 참고.  
**프로젝트별:** `PATCH /api/projects/{id}/deployment-profile`.  
**컴포넌트 상세:** `POST .../cves/{cveDbId}/ai-summarize`로 CVE AI 요약 새로고침 (`COMPONENT.CVE_AI_REGENERATE` 감사 로그).

### AI 응답 캐싱 (v1.0.4)

CVE/라이선스 배치 보강은 각 `Cve`와 `Library` 행에 SHA-256 컨텍스트 해시를 저장합니다. 다음 스캔 때 답변을 결정하는 입력(severity, CVSS 점수/벡터, 수정 버전, EPSS 버킷, KEV 여부, 의존 유형, patchability, 라이선스 이름/상태, 배포 프로필 등)이 변하지 않으면 기존 AI 요약을 재사용하고 프로바이더를 다시 호출하지 않습니다. 이 동작은 자동이며 별도의 관리 UI나 클리어 API는 없습니다. 컴포넌트 상세 화면의 **재생성** 버튼은 캐시를 우회합니다.

### 사용량 및 비용 추적

AI 카드는 오늘의 호출 수, 토큰 합계, 예상 비용을 보여주고(`GET /api/settings/ai/usage` — 이력이 쌓여도 조회 비용이 늘지 않도록 일별 집계 테이블에서 조회), 10건씩 페이지네이션되는 **최근 호출** 표를 함께 보여줍니다(`GET /api/settings/ai/usage/events`). 원본 호출 이벤트는 최근 **100건**만 보존되며 — 새 호출이 기록될 때마다 오래된 것부터 삭제(FIFO)됩니다 — 일별 합계와 7일 추이는 원본 이벤트 로그가 아닌 집계 테이블에서 나오므로 영향받지 않습니다.

예상 비용은 실제 청구서가 **아니며**, **모델별**로 각 모델의 공식 100만 토큰당 입력/출력 정가를 사용해 계산됩니다(예: `claude-opus-5`, `gpt-5.6-terra`, `gemini-3.1-pro`는 각각 다른 단가를 가지며, 같은 프로바이더 안에서 10배 비싼 모델을 더 이상 하나의 평균 단가로 뭉뚱그리지 않습니다). 캐시 입력·배치·롱컨텍스트 할인은 반영되지 않으므로 참고용으로만 사용하세요. 공식 정가가 없는 모델(커스텀 배포, 신규 출시 모델, 또는 **로컬** 프로바이더에서 실행되는 모든 모델)은 다음과 같은 프로바이더별 기본 단가로 대체됩니다:

| 설정 키 | 기본값 (USD / 100만 토큰) |
|---|---|
| `oswl.ai.pricing.openai-input-per-1m` / `openai-output-per-1m` | `2.50` / `10.00` |
| `oswl.ai.pricing.anthropic-input-per-1m` / `anthropic-output-per-1m` | `3.00` / `15.00` |
| `oswl.ai.pricing.gemini-input-per-1m` / `gemini-output-per-1m` | `1.25` / `5.00` |
| `oswl.ai.pricing.local-input-per-1m` / `local-output-per-1m` | `0` / `0` |

실제 계약 단가가 위 기본값과 다르면 이 값들을 갱신하세요. **로컬** 프로바이더는 어떤 모델 이름을 보고하든 항상 설정된 단가(기본 `0`)로 추정됩니다 — 자체 호스팅 모델은 조회할 토큰당 비용 자체가 없기 때문입니다.

---

## 캐시 설정

**설정 → 캐시**

라이브러리 **보강 캐시**(deps.dev + OSV)의 유일한 제어 화면입니다. 별도의 “외부 API 설정” 메뉴/API는 없습니다.

| 캐시 키 | 기본 TTL | 용도 |
|---|---|---|
| `DEPS_DEV` | 7일 | 보강 재조회 정책의 기준 (버전 정보, 어드바이저리, fetch 여부) |
| `OSV_VULN` | 7일 | deps.dev와 함께 관리; 클리어 시각이 재조회 판단에 반영 |

| 작업 | API | 설명 |
|---|---|---|
| **조회** | `GET /api/settings/cache` | 키별 TTL, 마지막 클리어 사용자·시각 |
| **TTL 변경** | `PUT /api/settings/cache` | `cacheKey` + `ttlSeconds` (UI: 항상 새로고침 / 사용자 지정 / 영구) |
| **클리어** | `POST /api/settings/cache/clear?cacheKey=…` | 클리어 시각 이전에 fetch된 라이브러리는 다음 스캔에서 stale 처리. `cacheKey=ALL`로 모든 등록된 캐시를 한 번에 클리어할 수 있습니다. |

변경 사항은 `CACHE.UPDATE_TTL`, `CACHE.CLEAR`로 감사 로그에 기록됩니다.


---

## SAML 2.0 SSO 및 SCIM 2.0 프로비저닝

OsWL은 Okta, Entra ID, 온프레미스 AD FS를 사용하는 기업용 SAML 2.0 단일 로그인을 지원합니다. SAML IdP가 설정되면 `/login`에 **SSO로 로그인** 옵션이 표시됩니다.

### SAML 설정

1. SP 서명 키 쌍을 생성합니다(선택 사항이지만 권장):
   ```bash
   openssl req -x509 -newkey rsa:2048 -keyout oswl-saml-sp.key -out oswl-saml-sp.crt -nodes -days 3650 -subj "/CN=oswl"
   ```
2. `application-prod.yaml`의 SAML 블록의 주석을 해제하고 환경 변수를 설정합니다:
   | 환경 변수 | 용도 |
   |---|---|
   | `OSWL_SAML_IDP_METADATA_URL` | IdP 메타데이터 URL(예: Okta/Entra 앱 메타데이터) |
   | `OSWL_SAML_IDP_CERTIFICATE` | IdP 서명 인증서 파일 경로 |
   | `OSWL_SAML_SP_PRIVATE_KEY` | SP 개인 키 파일 경로 |
   | `OSWL_SAML_SP_CERTIFICATE` | SP 인증서 파일 경로 |
3. IdP에 SP 메타데이터를 등록합니다. 메타데이터 엔드포인트는 다음과 같습니다:
   ```
   https://<your-oswl-host>/saml2/service-provider-metadata/oswl
   ```
4. IdP가 email 클레임(NameID 또는 `email`/`mail` 속성)을 전송하는지 확인합니다.

> SAML 로그인은 IdP가 이미 사용자를 인증했으므로 이메일 OTP 단계를 건너뜁니다. 기존 OsWL 계정과 일치하지 않는 이메일은 SCIM이 활성화하고 역할을 할당할 수 있도록 비활성화된 로컬 계정으로 자동 생성됩니다.

### SCIM 2.0 프로비저닝

SCIM을 사용하면 IdP의 사용자 생명주기를 OsWL과 동기화할 수 있습니다.

| 리소스 | 엔드포인트 | 참고 |
|---|---|---|
| Users | `/scim/v2/Users` | GET/POST/PUT/PATCH/DELETE |
| Groups | `/scim/v2/Groups` | GET/POST/PUT/PATCH/DELETE |

**인증:** 모든 SCIM 요청에 `Authorization: Bearer <scim_token>`을 포함해야 합니다. 전용 SCIM 토큰은 `ApiKeyService#issueScimToken`을 통해 프로그래밍 방식으로 발급합니다. SCIM 토큰은 `api_keys` 테이블에 저장되지만 범위가 `SCIM`이며, 일반 CLI 스캔 API에서는 거부됩니다.

**그룹 매핑:** `oswl.scim.group-mapping`(환경 변수: `OSWL_SCIM_GROUP_MAPPING`)으로 SCIM 그룹의 표현 방식을 선택합니다:
- `TEAM`(기본값) — 각 SCIM 그룹은 Team이 되고, 멤버는 TeamMember 행이 됩니다.
- `ROLE_TEMPLATE` — 각 SCIM 그룹은 RoleTemplate이 되고, 멤버는 해당 역할 템플릿이 할당됩니다.

**사용자 비활성화:** `DELETE /scim/v2/Users/{id}`는 OsWL에서 `active=false`로 설정합니다. SCIM을 통해 사용자를 물리적으로 삭제하지는 않으므로 감사 귀속 정보가 보존됩니다.

**감사 액션:** SCIM 작업은 `SCIM.USER_CREATE`, `SCIM.USER_UPDATE`, `SCIM.USER_DEACTIVATE`, `SCIM.GROUP_CREATE`, `SCIM.GROUP_UPDATE`, `SCIM.GROUP_DELETE`, `SCIM.GROUP_MEMBER_ADD`, `SCIM.GROUP_MEMBER_REMOVE`, `SCIM.AUTH_FAILURE`, `SCIM_KEY.CREATE`로 기록됩니다. SAML 로그인 이벤트는 `SAML.LOGIN_SUCCESS` 및 `SAML.LOGIN_FAILURE`로 기록됩니다.
