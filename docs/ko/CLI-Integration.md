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

`POST /api/scan` — 요청 본문 형식은 [영문 CLI 문서](../CLI-Integration.md)와 동일합니다.

### 성공 응답

```json
{
  "scanId": 87,
  "projectId": 42,
  "version": "1.4.2",
  "status": "PENDING",
  "message": "Scan received successfully"
}
```

### 상태 확인

```
GET /api/scan/{scanId}/status
```

상태: `PENDING` → `SCANNING` → `ANALYZING` → `COMPLETED` (또는 `FAILED`)

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

---

## 관련 문서

- [Scan API 보안](Scan-Api-Security.md)
- [Quick Import](Quick-Import.md) — 동일 파서, 원격 Git URL
- [API 레퍼런스](API-Reference.md)
