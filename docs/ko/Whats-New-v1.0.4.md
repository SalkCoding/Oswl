# v1.0.4 새로운 기능

v1.0.4는 컴플라이언스·워크플로 릴리스입니다. 표준 기반 내보내기(CycloneDX SBOM, VEX, SARIF), CI/CD 보안 게이트, 연속 모니터링, 조직 전체 대시보드, 공급망 휴리스틱, 폐쇄망 모드, 일본어 지원이 추가되었습니다.

아래 기능은 모두 자체 호스팅 빌드에 포함되어 있습니다 — 별도 에디션이나 라이선스 키가 필요하지 않습니다.

---

## 컴플라이언스 내보내기

### CycloneDX SBOM (1.6)

| | |
|---|---|
| **엔드포인트** | `GET /api/projects/{projectId}/sbom` |
| **UI** | 보안 센터 → **내보내기** 드롭다운 → *SBOM (CycloneDX)* |
| **포맷** | CycloneDX 1.6 JSON, `application/vnd.cyclonedx+json` |

프로젝트의 가장 최근 완료 스캔을 기준으로 생성됩니다. 모든 컴포넌트에 `purl`, 판정된 라이선스(SPDX ID 또는 표현식), scope가 포함되고, 메타데이터에는 도구 정보·스캔 시각·루트 컴포넌트(프로젝트/버전)가 기록됩니다.

### VEX

| | |
|---|---|
| **엔드포인트** | `GET /api/projects/{projectId}/vex` |
| **UI** | 보안 센터 → **내보내기** 드롭다운 → *VEX* |

VEX는 원시 탐지 결과가 아니라 **트리아지 판단**을 담습니다. 각 취약점이 CycloneDX 분석 상태로 매핑됩니다.

| OsWL 상태 | VEX 상태 | 근거 |
|---|---|---|
| 미조치 / 검토 중 | `exploitable` | — |
| 무시 | `not_affected` | 무시 사유를 그대로 기록 |
| 유예 | `in_triage` | 유예 기한 포함 |
| 조치 완료 | `resolved` | — |

감사인과 후속 소비자가 원하는 답, 즉 "취약한 라이브러리를 포함해 배포했는데 실제로 영향을 받는가?"에 대한 기계 판독 가능한 답변입니다.

### SARIF (2.1.0)

| | |
|---|---|
| **엔드포인트** | `GET /api/projects/{projectId}/sarif` |
| **UI** | 보안 센터 → **내보내기** 드롭다운 → *SARIF* |
| **포맷** | SARIF 2.1.0, `application/sarif+json` |

`github/codeql-action/upload-sarif`와 스키마 호환이므로 결과가 GitHub **Security** 탭에 표시됩니다. CVE별로 `security-severity`가 붙은 rule이 생성되고, 영향받는 컴포넌트마다 매니페스트 위치와 `partialFingerprints`가 담긴 result가 생성됩니다. 유예·무시 처리된 항목은 `suppressed`로 내보내 재알림되지 않습니다.

```yaml
- name: Upload OsWL SARIF
  uses: github/codeql-action/upload-sarif@v3
  with:
    sarif_file: oswl.sarif
```

### SBOM 가져오기

| | |
|---|---|
| **엔드포인트** | `POST /api/sbom/import` (multipart) |
| **UI** | Quick Import → **SBOM 가져오기** |

직접 빌드하지 않는 대상 — 협력사 납품물, 컨테이너 베이스 이미지, 다른 도구가 만든 SBOM — 을 외부 CycloneDX 파일로 올려 스캔합니다. 가져온 컴포넌트도 일반 스캔과 동일하게 보강됩니다.

### 컴플라이언스 리포트 팩

`GET /security-center/compliance-report`는 인쇄용 리포트를 렌더링합니다. 컴포넌트 인벤토리, 라이선스 의무, NOTICE 문구, 심각도별 미조치 항목이 포함됩니다. 브라우저의 *인쇄 → PDF로 저장*을 사용하세요. 미리보기 진입 시 인쇄 창이 자동으로 열리지 않으므로 내보내기 전에 내용을 확인할 수 있습니다.

---

## CI/CD 보안 게이트

`POST /api/scan/gate` — 스캔 전송과 동일한 CLI API 키로 인증하며, 프로젝트는 키에서 결정됩니다.

응답은 기계 판독 가능한 판정 결과입니다. `exitCode`를 CI 잡의 종료 코드로 그대로 사용하세요(`0` 통과, `1` 실패).

기본값(요청별 또는 환경 변수로 재정의 가능):

| 설정 | 환경 변수 | 기본값 |
|---|---|---|
| 이 심각도 이상이면 실패 | `OSWL_GATE_FAIL_ON_SEVERITY` | `HIGH` |
| CISA KEV 등재 CVE가 있으면 실패 | `OSWL_GATE_FAIL_ON_KEV` | `true` |
| EPSS ≥ *n* 이면 실패 (음수면 비활성) | `OSWL_GATE_FAIL_ON_EPSS` | `0.5` |
| RESTRICTED 라이선스 컴포넌트가 있으면 실패 | `OSWL_GATE_FAIL_ON_LICENSE_VIOLATION` | `true` |
| 직전 스캔에 없던 신규 항목만 판정 | `OSWL_GATE_ONLY_NEW` | `true` |

기존 코드베이스에 게이트를 도입할 수 있게 만드는 핵심은 `only-new`입니다. 직전 완료 스캔이 베이스라인이 되므로 기존 부채는 머지를 막지 않고, 새로 유입된 리스크만 실패시킵니다.

요청에 GitHub 대상이 포함되면 판정 결과가 **Check Run**과 PR 코멘트로도 게시됩니다.

전체 요청 형식과 파이프라인 예시는 [CLI 연동](CLI-Integration.md)을 참고하세요.

---

## 연속 모니터링 & CVE 알림

야간 배치가 모든 프로젝트의 최신 완료 스캔을 OSV에 재조회합니다. 마지막 스캔 **이후에** 공개된 CVE도 놓치지 않습니다.

| 설정 | 환경 변수 | 기본값 |
|---|---|---|
| 활성화 | `OSWL_MONITORING_ENABLED` | `true` |
| 주기 (Spring cron) | `OSWL_MONITORING_CRON` | `0 0 3 * * *` (매일 03:00) |

신규 취약점은 프로젝트 카드에 알림으로 표시되고 프로젝트 멤버에게 메일이 발송됩니다. 확인 처리는 `POST /projects/{projectId}/cve-alerts/acknowledge`.

---

## 조직 대시보드

`/org-dashboard`는 모든 프로젝트를 CISO 관점의 단일 화면으로 롤업합니다. 심각도 총계, 최악 프로젝트 랭킹, KEV 등재 CVE 수, 라이선스 경고를 함께 보여줍니다.

접근에는 신규 `ORG_DASHBOARD_VIEW` 권한(또는 `SYSTEM_ADMIN`)이 필요합니다. 권한이 부여되면 프로젝트 목록·프로젝트 상세·버전 비교 화면의 상단 바에 진입점이 표시됩니다.

---

## 우선순위 판단 신호

* **CISA KEV** — *Known Exploited Vulnerabilities* 목록에 등재된 취약점을 최우선으로 표시합니다. KEV 등재는 실제 공격에 사용되고 있다는 확인이므로, 트리아지 순서에서는 CVSS 점수보다 우선합니다.
* **EPSS** — FIRST.org의 악용 예측 점수로 나머지 항목을 향후 30일 내 악용 확률 순으로 정렬합니다.
* **의존성 scope** — test/dev 전용 의존성에 태그를 붙이고, 목록에서 숨겨 운영 리스크만 남길 수 있습니다. 제거가 아니라 태그이므로 SBOM에서는 아무것도 사라지지 않습니다.

---

## 공급망 휴리스틱

`SupplyChainHeuristicsService`가 컴포넌트 단위로 두 가지 리스크를 표시하며, 컴포넌트 상세에 배지로 노출됩니다.

* **알려진 악성 패키지** — 권고 피드와 대조합니다.
* **Typosquatting 위험** — 유명 패키지 목록과의 Levenshtein 거리로 `expres`, `lodahs` 류를 탐지합니다.

컴포넌트 상세에는 deps.dev의 **OpenSSF Scorecard** 점수도 표시됩니다. 아직 취약점은 없지만 관리가 중단된 의존성을 사고가 되기 전에 확인할 수 있습니다.

---

## 일괄 업그레이드 PR

보안 센터 → 일괄 작업 → **업그레이드 PR 생성**을 누르면 선택한 컴포넌트를 모두 수정 버전으로 올리는 단일 Pull Request가 생성됩니다. 매니페스트를 직접 패치하는 방식으로, 보안 수정에 한정된 Renovate-lite입니다.

---

## Jira 연동

설정 → 연동에서 한 번 구성한 뒤(`GET /api/settings/jira`), 탐지 항목에서 바로 이슈를 생성합니다: `POST /projects/{projectId}/components/{componentId}/jira-ticket`. 이슈 본문은 Atlassian Document Format으로 CVE·심각도·영향 버전·수정 버전을 담고, 생성된 티켓은 컴포넌트 상세에서 링크로 연결됩니다.

---

## 폐쇄망(air-gapped) 모드

외부 인터넷이 없는 망을 위한 모드입니다. `OSWL_AIRGAPPED_ENABLED=true`이면 취약점·위협 인텔 조회(OSV, deps.dev, EPSS, KEV)가 라이브 API 대신 반입된 오프라인 스냅샷에서 처리되며, 외부로 HTTP 요청을 시도하지 않습니다.

| 작업 | 엔드포인트 |
|---|---|
| 번들 반입 | `POST /api/admin/snapshot/import` (multipart, `SYSTEM_ADMIN`) |
| 번들 내보내기 | `GET /api/admin/snapshot/export` |
| 번들 상태 | `GET /api/admin/snapshot` |

인터넷이 연결된 장비에서 내보낸 뒤 번들을 반입하면 됩니다. 스냅샷에 없는 컴포넌트는 "취약점 없음"이 아니라 **데이터 없음**으로 처리됩니다. [내장 AI](Embedded-AI.md) 사이드카와 조합하면 네트워크 케이블을 뽑은 상태에서 스캔·트리아지·AI 분석까지 모두 동작합니다.

---

## 운영 기능

| 기능 | 방법 |
|---|---|
| **Prometheus 메트릭** | `/actuator/prometheus` — micrometer로 노출, 관리자 권한 필요 |
| **헬스 / 정보** | `/actuator/health`, `/actuator/info` |
| **Flyway 마이그레이션** | `OSWL_FLYWAY_ENABLED=true`로 옵트인(`baseline-on-migrate`). 기본은 `ddl-auto` 유지 — [DB 스키마](Database-Schema.md) 참고 |
| **OIDC 싱글 사인온** | `application-prod.yaml`의 `spring.security.oauth2.client` 블록 주석을 해제하고 `OSWL_OIDC_CLIENT_ID` / `OSWL_OIDC_CLIENT_SECRET` / `OSWL_OIDC_ISSUER_URI` 설정(Okta, Entra ID 등 모든 OIDC 제공자). 프로바이더가 등록된 경우에만 로그인 화면에 SSO 버튼이 표시됩니다. |
| **감사 로그 SIEM 내보내기** | `GET /api/admin/audit-logs/export?format=jsonl\|cef` — 기존 감사 로그 필터를 그대로 사용하며 `AUDIT_LOG_EXPORT` 권한이 필요합니다. 내보내기 행위 자체도 감사 기록됩니다. |

---

## 신규 권한

관리 → 역할 템플릿에서 부여합니다.

| 권한 | 허용 범위 |
|---|---|
| `ORG_DASHBOARD_VIEW` | 조직 대시보드 조회 |
| `AUDIT_LOG_VIEW` | 감사 로그 조회 |
| `AUDIT_LOG_EXPORT` | SIEM용 감사 로그 내보내기 |
| `SETTINGS_JIRA_MANAGE` | Jira 연동 관리 |
| `SETTINGS_SNAPSHOT_MANAGE` | 오프라인 스냅샷 번들 관리 |

기존 역할 템플릿은 변경되지 않았으므로 이 권한들은 **미부여** 상태로 시작합니다. 필요한 역할에 명시적으로 부여하세요.

---

## 신규 에코시스템

| 에코시스템 | 매니페스트 | purl |
|---|---|---|
| PHP | `composer.lock` | `pkg:composer/<vendor>/<package>@<version>` |
| C/C++ | `conan.lock` (Conan 2.x) | `pkg:conan/<name>@<version>` |

두 포맷 모두 기존 매니페스트 파이프라인에서 처리되므로 브랜치 임포트·전이 경로·데이터 보강이 다른 에코시스템과 동일하게 동작합니다.

---

## 일본어 지원

UI, 메일 템플릿, 랜딩 페이지, 오픈소스 고지문이 **영어·한국어·일본어**로 제공됩니다. 상단 바의 언어 선택기(또는 `?lang=ja`)로 전환하세요.

AI 출력은 서버가 아니라 **작업자**를 따릅니다. 스캔을 시작한 사용자의 로케일이 스캔 레코드에 기록되고, AI Insight 결과도 해당 언어로 생성됩니다. 프롬프트 템플릿은 한국어·일본어 오버레이(`ai/prompts_ko.properties`, `ai/prompts_ja.properties`)를 제공하며, 템플릿 로케일은 설정 → AI에서 지정합니다.

---

## 업그레이드 참고 사항

* **스키마** — `libraries` 테이블에 컬럼 2개(`malicious`, `typosquat_risk`)가 추가됩니다. 둘 다 `NOT NULL DEFAULT false`이므로 데이터가 있는 DB에도 `ddl-auto=update`로 안전하게 적용됩니다. 수동 마이그레이션은 필요하지 않습니다.
* **모르는 사이에 켜지는 기능은 없습니다** — 게이트, 폐쇄망 모드, Flyway, OIDC는 모두 옵트인입니다. 예외는 연속 모니터링 하나로, 기본 활성 상태이며 `OSWL_MONITORING_ENABLED=false`로 끌 수 있습니다.
* **신규 권한은 기본 미부여**이므로 부여하기 전까지 기존 사용자 화면에는 변화가 없습니다.
