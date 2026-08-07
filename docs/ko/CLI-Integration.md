# CLI 연동

OsWL은 공식 CLI(`oswl`)와 REST API를 제공하여, 웹 브라우저나 VCS 연결 없이 로컬·CI에서 의존성 스캔을 제출할 수 있습니다.

---

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
| `GET` | `/api/projects/{projectId}/sbom` | 세션 / 키 | **v1.0.4** — CycloneDX 1.6 SBOM |
| `GET` | `/api/projects/{projectId}/vex` | 세션 / 키 | **v1.0.4** — CycloneDX VEX |
| `GET` | `/api/projects/{projectId}/sarif` | 세션 / 키 | **v1.0.4** — SARIF 2.1.0 |
| `POST` | `/api/sbom/import` | 세션 | **v1.0.4** — 외부 CycloneDX 파일 가져오기 |

> CLI 엔드포인트는 `Authorization: Bearer` 헤더만으로 인증하며, 세션 쿠키나 CSRF 토큰은 필요 없습니다. `POST /api/scan`, `POST /api/scan/parse`, `GET /api/scan/ping`은 브라우저 CSRF 검사에서 제외되고, 그 외 경로는 기존 CSRF 보호가 유지됩니다. [Scan API 보안](Scan-Api-Security.md) 참고.

---

## API 키 관리

### 프로젝트 범위 키

```
POST /api/projects/{projectId}/keys
```

UI: 프로젝트 → **설정(⚙)** → **CLI** → **키 생성**

### 관리자 전역 키

**설정 → 관리자 → CLI 키** — [API 레퍼런스](API-Reference.md) 참고

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

서버 기본값(요청별 재정의 가능):

| 필드 | 환경 변수 | 기본값 |
|---|---|---|
| `failOnSeverity` | `OSWL_GATE_FAIL_ON_SEVERITY` | `HIGH` |
| `failOnKev` | `OSWL_GATE_FAIL_ON_KEV` | `true` |
| `failOnEpss` | `OSWL_GATE_FAIL_ON_EPSS` | `0.5` |
| `failOnLicenseViolation` | `OSWL_GATE_FAIL_ON_LICENSE_VIOLATION` | `true` |
| `onlyNew` | `OSWL_GATE_ONLY_NEW` | `true` |
| `onlyReachable` | `OSWL_GATE_ONLY_REACHABLE` | `false` |
| `failOnSecrets` | `OSWL_GATE_FAIL_ON_SECRETS` | `false` |

`onlyNew`는 직전 완료 스캔을 베이스라인으로 비교하므로 기존 부채가 머지를 막지 않습니다. `onlyReachable`(**v1.0.5**)은 여기에 더해 바이트코드 호출 그래프 분석으로 취약 라이브러리가 실제로 참조되는 것이 확인된 경우에만 차단하도록 하는 추가 노이즈 컷입니다. `oswl.reachability.bytecode-root`가 설정된 Java/Gradle 컴포넌트에만 적용되며, 그 외에는 전부 UNKNOWN으로 남아 이 옵션으로는 절대 차단되지 않으므로 Java 프로젝트가 아니라면 꺼둘 것을 권장합니다. 요청에 GitHub 대상을 포함하면 판정이 Check Run과 PR 코멘트로도 게시됩니다.

확정 악성 패키지(OSV `MAL-` 어드바이저리)는 위의 모든 임계값 및 `onlyNew`/`onlyReachable`과 무관하게 항상 차단됩니다 — 유일한 해제 방법은 승인된 정책 예외(waiver, **v1.0.5**, `/api/policies/exceptions` 참고)뿐입니다.

`failOnSecrets`(**v1.0.5**)는 Quick Import 클론 스캔에서 CRITICAL/HIGH 등급 시크릿 탐지(정규식 + 엔트로피 규칙 — AWS 키, GitHub/GitLab/Slack/npm 토큰, 임베디드 프라이빗 키 블록 등)가 하나라도 있으면 차단합니다. 아직 조직/팀/프로젝트 정책 계층에는 포함되지 않으며, 요청 오버라이드와 인스턴스 기본값만 적용됩니다.

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
