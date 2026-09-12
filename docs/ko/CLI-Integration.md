# CLI 연동

OsWL은 공식 CLI(`oswl`)와 REST API를 제공하여, 웹 브라우저나 VCS 연결 없이 로컬·CI에서 의존성 스캔을 제출할 수 있습니다.

---

게이트 기준은 같은 프로젝트에서 평가 대상보다 앞선 완료 스캔 중 가장 가까운 스캔이며, 스캔 시각과 scanId 순으로 정합니다. 과거 스캔 평가에서 이후 스캔을 기준으로 선택하지 않으며 최근 10개 제한도 없습니다. 앞선 완료 스캔이 없으면 기준 스캔 없이 평가합니다. 보호 브랜치 기준이나 정책 revision 고정까지 구현된 것은 아닙니다.


심각도가 없어도 KEV·EPSS 게이트 규칙은 독립적으로 평가합니다. 해당 규칙으로 차단된 취약점은 심각도를 추정하지 않고 `UNSCORED`로 표시합니다. EPSS는 설정된 임계값 이상일 때 차단합니다. 이 동작이 누락된 심각도나 위협 정보 근거를 보충하는 것은 아닙니다.

조회 완료 판정은 미래의 fetchedAt·vulnerabilityLookupAt을 거절하고, 출처별 결과가 저장돼 있으면 조회 시각을 요구합니다. 보존 판정은 캡처 당시 lookupTimesVerified를 저장하고 나중의 시계나 공유 캐시로 다시 검증하지 않습니다. 이전 보존 판정에 플래그가 없으면 근거 미완료로 처리하므로 값을 소급 채우지 말고 새 분석을 실행해야 합니다. 이는 로컬 조회 시각 검증이며 공급자 자료의 신선도를 증명하지 않습니다. 출처별 결과가 없는 legacy live 캐시는 수집 시각이 있어도 미완료입니다. 영구 캐시 설정에서도 다음 분석 시 재조회하며, 없는 과거 출처 결과를 추정해 채우지 않습니다.

NVD/CPE 근거만 있는 발견은 신뢰도가 HIGH여도 매칭 후보로 유지합니다. 게이트는 확정 CVE 위반 대신 `MATCH_REVIEW`와 미완료 `COVERAGE`를 반환합니다. 컴포넌트 무시, 심각도 임계값, onlyNew/onlyReachable 필터로 이 검토를 건너뛸 수 없습니다. 기준 스캔의 후보는 나중에 패키지 근거로 확인된 발견을 숨기지 않습니다. OSV·deps.dev·GitHub Advisory 출처가 함께 있으면 패키지 기반 평가를 유지합니다. 전체 CPE configuration·환경 조건 평가와 검토 해소 절차는 아직 미구현이며, 출처와 신뢰도 정보가 모두 없는 레코드는 기존 동작을 유지합니다.

공통 패치 추천도 CPE 후보 검토가 남아 있으면 버전을 보류합니다. 후보에 수정 문자열이 있거나 캐시의 공통 수정 판정에 같은 ID가 있어도 패치 가능성은 UNKNOWN입니다. 원래 후보 정보는 보존하며 최신 릴리스를 보안 수정의 대체값으로 사용하지 않습니다. 같은 발견에 패키지 공급자 근거가 있으면 기존 공통 수정 검사를 유지합니다. 최종 PR 대상 검증기의 더 엄격한 NVD/CPE 식별 가드는 유지합니다.

인쇄용 컴플라이언스 보고서에도 같은 후보 구분을 적용합니다. CPE 후보는 별도 검토 표에 표시하고 확정 심각도·KEV 집계 및 수정 버전 안내에서 제외합니다. 컴포넌트를 검토·보류해도 후보 목록은 남으며, 미조치 후보는 심각도가 없어도 위험 컴포넌트로 집계합니다. 이 구분이 전체 조회 완료를 증명하거나 다른 대시보드에 일괄 적용되는 것은 아닙니다.


요청·정책·서버 기본값을 합친 최종 EPSS 임계값은 유한한 1 이하 값인지 검증합니다. [0,1]은 규칙을 켜고 유한한 음수는 강제 기준이 허용할 때만 규칙을 끕니다. NaN·무한대·1 초과 값은 게이트 결과 대신 잘못된 요청 오류를 반환합니다. 요청은 아래의 유효 기준 강화 규칙을 따릅니다.


강제 게이트 요청은 유효 조직·팀·프로젝트 정책과 서버 기본값을 강화할 수 있지만 완화할 수 없습니다. 심각도는 더 넓은 차단 범위(HIGH보다 LOW 등)를 사용하고, 켜진 KEV·라이선스·시크릿 규칙은 유지하며, EPSS는 활성 임계값 중 더 낮은 값을 사용합니다. 요청의 음수 EPSS나 NONE 심각도로 활성 기준을 끌 수 없습니다. 정책의 NONE은 심각도 비교를 끄되 KEV·EPSS 규칙은 유지하며, 요청으로 심각도 차단을 다시 켤 수 있습니다. 잘못된 심각도 이름은 거절합니다. 요청은 onlyNew·onlyReachable을 꺼서 범위를 넓힐 수 있지만 기준 정책이 허용하지 않는 필터를 켜서 범위를 좁히지 못합니다. 응답에는 실제 적용한 값을 반환합니다.

기존 계층에서 잠기지 않은 하위 정책 필드는 상위를 재정의할 수 있고 잠긴 필드의 동작도 유지합니다. 이번 변경은 이렇게 결정된 유효 정책을 요청이 완화하지 못하게 합니다. 보호 브랜치 기준·revision 고정과 별도 참고 평가는 남은 작업입니다.


## 업로드 재전송

`oswl scan`은 실행마다 새 무작위 재전송 키를 생성하고 업로드 전에 출력합니다. 응답이 유실됐다면 같은 입력·자격증명으로 `oswl scan ... --idempotency-key <출력된-키>`를 실행하세요. 서버는 분석을 다시 시작하지 않고 기존 scanId를 반환합니다. 같은 키로 입력이 달라지면 409이며, 새 분석에는 새 키를 쓰거나 옵션을 생략합니다. 실패한 스캔을 재실행하기 위해 같은 키를 사용하지 마세요.

자동 업로드 재시도는 없습니다. 서버가 재전송 키·입력 해시 계약(V42 스키마)을 지원해야 하며, 구버전 서버는 키를 무시하고 중복 스캔을 만들 수 있습니다. 키는 CLI 설정에 저장하지 않습니다. manifest 변경 후 재파싱, 배열 순서 변경, 제출자 이메일 변경은 충돌로 거절되어 기존 결과를 보존할 수 있습니다. 나머지 스캔 옵션과 자격증명은 계속 필요합니다.

## 빠른 시작 (공식 CLI)

### 1. 설치

**Mac / Linux**

```bash
curl -fsSL https://<your-server>/scripts/install.sh | bash
```

**Windows (PowerShell)**

```powershell
iex ((New-Object System.Net.WebClient).DownloadString('https://<your-server>/scripts/install.ps1'))
```

**사전 요구 도구**

| 플랫폼 | 도구 |
|---|---|
| Mac / Linux | `curl`, `jq`, `zip` |
| Windows | PowerShell 5.1+, `curl.exe` |

### 2. API 키 저장 (선택)

```bash
oswl auth --key oswl_<your_api_key> --server https://<your-server>
```

### 3. 프로젝트 스캔

```bash
cd /your/project
oswl scan -k oswl_<your_api_key> -u you@company.com --server https://<your-server>
```

- `-u`(이메일)는 **필수**입니다.
- `-p`(비밀번호)는 생략 가능 — 생략 시 **대화형으로 입력**을 요청합니다.
- `project_dir`를 생략하면 **현재 디렉터리**가 대상입니다.

**CI/CD 예시**

```bash
export OSWL_API_KEY=oswl_xxx
export OSWL_USERNAME=ci@company.com
export OSWL_PASSWORD=secret
export OSWL_SERVER_URL=https://sca.company.com
cd /your/project && oswl scan
```

### 사용자에게 보이는 흐름

```
[OsWL] Scanning dependencies... (version: 1.4.2)
[OsWL] Parsing manifests on server...
[OsWL] Found 128 component(s).
[OsWL] Sending to server: https://...
[OsWL] Scan submitted! scanId=87
       Analysis is running on the server. Check the Security Center for results.
```

**입력하는 명령은 동일**하고, 파싱만 서버에서 **Quick Import와 같은 엔진**으로 수행됩니다.

---

## 아키텍처

```
로컬 / CI
       │
       │  1. manifest 파일 zip (lock, pom, package.json, …)
       │     규칙: GET /scripts/manifest-rules.json
       │
       │  2. POST /api/scan/parse  (multipart archive)
       │     Authorization: Bearer oswl_<key>
       ▼
OsWL 서버 — DependencyManifestParserService (Quick Import와 공유)
       │
       │  3. POST /api/scan  (JSON + 제출자 자격증명)
       ▼
ScanIngestService → CVE·라이선스 비동기 보강 (OSV / deps.dev)
```

---

매니페스트 압축 파일의 수집 범위는 `/scripts/manifest-rules.json`을 따릅니다. 의존성 매니페스트 외에 빌드 설정, 래퍼 파일, `buildSrc`의 Java/Kotlin 파일도 포함될 수 있으므로 전송 전에 수집 규칙을 확인하세요.

## 사전 요구사항

1. OsWL에 등록된 **프로젝트**
2. **프로젝트 API 키** (`oswl_...`) — **설정 → CLI** 또는 프로젝트 API 키 페이지
3. `SCAN_SUBMIT` 권한과 해당 프로젝트 **멤버십**이 있는 **사용자 계정** ([인증 계층](Authorization-Layers.md))

---

## API 엔드포인트 (CLI)

| Method | Path | 인증 | 설명 |
|---|---|---|---|
| `GET` | `/api/scan/ping` | API key | 키 유효성 확인 |
| `GET` | `/api/scan/manifest-rules` | API key | manifest 수집 규칙 (JSON) |
| `GET` | `/scripts/manifest-rules.json` | 없음 | 동일 규칙 (CLI 캐시용 정적 파일) |
| `POST` | `/api/scan/parse` | API key | manifest zip 파싱 → components |
| `POST` | `/api/scan` | API key + 비밀번호 | 스캔 제출·보강 |
| `GET` | `/api/scan/{scanId}/status` | 세션 | 스캔 상태 폴링 (UI) |
| `POST` | `/api/scan/gate` | API 키 | **v1.0.4** — PR / CI 보안 게이트, `exitCode`가 담긴 판정 반환 |
| `GET` | `/api/projects/{projectId}/sbom` | 세션 | **v1.0.4** — CycloneDX 1.6 SBOM |
| `GET` | `/api/projects/{projectId}/vex` | 세션 | **v1.0.4** — CycloneDX VEX |
| `GET` | `/api/projects/{projectId}/sarif` | 세션 | **v1.0.4** — SARIF 2.1.0 |
| `POST` | `/api/sbom/import` | 세션 | **v1.0.4** — 외부 CycloneDX 파일 가져오기 |

> CLI 요청은 `Authorization: Bearer`에 프로젝트 API 키를 전달합니다. `POST /api/scan`은 제출자의 이메일·비밀번호·권한·프로젝트 접근 권한도 확인합니다. `POST /api/scan`, `POST /api/scan/parse`, `POST /api/scan/gate`에는 브라우저 세션이나 CSRF 토큰이 필요하지 않습니다. `GET /api/scan/ping`은 키를 검증합니다. [스캔 API 보안](Scan-Api-Security.md)을 참고하세요.

---

## API 키 관리

### 프로젝트 범위 키

```
POST /api/projects/{projectId}/keys
```

UI: 프로젝트 → **설정(⚙)** → **CLI** → **키 생성**

### 관리자의 키 관리

관리자는 여러 프로젝트의 CLI 키를 조회·폐기하고 지정한 `projectId`에 키를 발급할 수 있습니다. 스캔 키의 범위는 프로젝트 단위입니다. 별도 SCIM 토큰은 스캔 제출에 사용할 수 없습니다.

---

## 스캔 제출 (raw API)

`oswl` 스크립트 없이 API만 직접 호출할 수도 있습니다.

### 1단계 — manifest 파싱 (`components`를 직접 만들 경우 생략 가능)

```bash
curl -s -X POST https://oswl.example.com/api/scan/parse \
  -H "Authorization: Bearer oswl_<key>" \
  -F "archive=@manifests.zip"
```

### 2단계 — 스캔 제출

```
POST /api/scan
Authorization: Bearer oswl_<key>
Content-Type: application/json
```

```json
{
  "version": "1.4.2",
  "submitterEmail": "dev@company.com",
  "submitterPassword": "yourpassword",
  "components": [
    {
      "name": "org.springframework:spring-core",
      "version": "6.1.4",
      "ecosystem": "MAVEN",
      "dependencyInfo": "Direct",
      "dependencyPaths": []
    }
  ]
}
```

### 필드

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `version` | string | ✅ | 스캔 시점의 프로젝트 버전 |
| `submitterEmail` | string | ✅ | OsWL 사용자 이메일 |
| `submitterPassword` | string | ✅ | BCrypt로 검증되며 저장·로그에 남지 않음 |
| `components` | array | — | 감지된 OSS 컴포넌트 |
| `components[].name` | string | ✅ | 패키지 이름 |
| `components[].version` | string | — | 패키지 버전 |
| `components[].ecosystem` | string | ✅ | `MAVEN`, `NPM`, `PYPI`, `GO`, `CARGO`, `NUGET`, `RUBYGEMS`, `COMPOSER`, `CONAN` |
| `components[].dependencyInfo` | string | — | 사람이 읽을 수 있는 경로 요약 |
| `components[].dependencyPaths` | array | — | 선택적 경로 트리 |

### 성공 응답

```json
{
  "scanId": 87,
  "projectId": 42,
  "version": "1.4.2",
  "status": "SCANNING",
  "message": "Scan received successfully"
}
```

### 상태 확인

```
GET /api/scan/{scanId}/status
```

```json
{
  "scanId": 87,
  "status": "COMPLETED",
  "componentCount": 128,
  "aiStatus": "RUNNING",
  "securityPostureInsight": null
}
```

상태: `PENDING` → `SCANNING` → `ANALYZING` → `COMPLETED` (또는 `FAILED`)

`aiStatus`(**v1.0.4**)는 AI 보강 진행을 별도로 추적합니다: `NOT_APPLICABLE` → `PENDING` → `RUNNING` → `COMPLETED` (또는 `FAILED`). CVE·라이선스 분석이 끝나면 스캔은 바로 `COMPLETED`가 되며, AI 요약은 완료를 막지 않고 백그라운드에서 계속 생성됩니다(AI 프로바이더가 설정되지 않은 경우 `NOT_APPLICABLE`). `securityPostureInsight`는 `aiStatus`가 `COMPLETED`가 될 때까지 `null`입니다.

---

## 보안 게이트 (v1.0.4)

`POST /api/scan/gate`는 프로젝트의 최신 스캔을 기준으로 임계값을 평가해 기계 판독 가능한 판정을 반환합니다. `exitCode`를 CI 잡의 종료 코드로 사용하세요(`0` 통과, `1` 실패).

```bash
verdict=$(curl -sS -X POST "$OSWL_URL/api/scan/gate"   -H "Authorization: Bearer $OSWL_API_KEY"   -H 'Content-Type: application/json'   -d '{"failOnSeverity":"HIGH","onlyNew":true}')

echo "$verdict"
exit "$(echo "$verdict" | jq -r .exitCode)"
```

서버 기본값(요청은 유효 기준을 강화하는 경우만 반영):

| 필드 | 환경 변수 | 기본값 |
|---|---|---|
| `failOnSeverity` | `OSWL_GATE_FAIL_ON_SEVERITY` | `HIGH` |
| `failOnKev` | `OSWL_GATE_FAIL_ON_KEV` | `true` |
| `failOnEpss` | `OSWL_GATE_FAIL_ON_EPSS` | `0.5` |
| `failOnLicenseViolation` | `OSWL_GATE_FAIL_ON_LICENSE_VIOLATION` | `true` |
| `onlyNew` | `OSWL_GATE_ONLY_NEW` | `true` |
| `onlyReachable` | `OSWL_GATE_ONLY_REACHABLE` | `false` |
| `failOnSecrets` | `OSWL_GATE_FAIL_ON_SECRETS` | `false` |

`onlyNew`는 기준 스캔에 이미 존재하던 CVE·라이선스 문제를 제외합니다. 통과를 보장하지는 않습니다. 악성 패키지, 활성화한 시크릿 탐지 등 다른 적용 규칙으로 실패할 수 있습니다. `onlyReachable`은 지원되는 바이트코드 또는 소스 참조 분석 결과가 `REACHABLE`인 컴포넌트의 CVE만 평가합니다. 활성화하면 `UNKNOWN`인 CVE는 제외하지만, 참조가 확인되지 않았다는 사실이 악용 불가능함을 뜻하지는 않습니다. 라이선스와 악성 패키지 검사는 이 필터와 별개입니다. 분석 범위를 검토한 뒤 활성화하세요. GitHub 대상을 설정하면 Check Run과 PR 댓글로 결과를 게시할 수 있습니다.

확정 악성 패키지(OSV `MAL-` 어드바이저리)는 위의 모든 임계값 및 `onlyNew`/`onlyReachable`과 무관하게 항상 차단됩니다 — 유일한 해제 방법은 승인된 정책 예외(waiver, **v1.0.5**, `/api/policies/exceptions` 참고)뿐입니다.

`failOnSecrets`(**v1.0.5**)는 Quick Import 클론 스캔에서 CRITICAL/HIGH 등급 시크릿 탐지(정규식 + 엔트로피 규칙 — AWS 키, GitHub/GitLab/Slack/npm 토큰, 임베디드 프라이빗 키 블록 등)가 하나라도 있으면 차단합니다. 다른 임계값처럼 유효 정책·기본값을 강제하고 요청은 이를 강화하는 경우만 반영합니다.

---

## GitHub Actions 예시

```yaml
- name: Install OsWL CLI
  run: curl -fsSL https://oswl.example.com/scripts/install.sh | bash

- name: Submit OsWL Scan
  env:
    OSWL_API_KEY: ${{ secrets.OSWL_API_KEY }}
    OSWL_USERNAME: ${{ secrets.OSWL_USERNAME }}
    OSWL_PASSWORD: ${{ secrets.OSWL_PASSWORD }}
    OSWL_SERVER_URL: https://oswl.example.com
  run: oswl scan
```

---

## 에코시스템 값

| 에코시스템 | 이름 형식 예시 |
|---|---|
| `MAVEN` | `org.springframework:spring-core` |
| `NPM` | `lodash`, `@angular/core` |
| `PYPI` | `requests`, `django` |
| `GO` | `github.com/gin-gonic/gin` |
| `CARGO` | `serde` |
| `NUGET` | `Newtonsoft.Json` |
| `RUBYGEMS` | `rails` |
| `COMPOSER` | `monolog/monolog` (v1.0.4) |
| `CONAN` | `openssl` (v1.0.4) |

---

## 관련 문서

- [Scan API 보안](Scan-Api-Security.md)
- [Quick Import](Quick-Import.md) — 동일 파서, 원격 Git URL
- [API 레퍼런스](API-Reference.md)
