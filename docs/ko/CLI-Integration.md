# CLI 연동

OsWL은 웹 브라우저나 VCS 연결 없이도 모든 빌드 도구, CI 파이프라인, 커스텀 스크립트에서 의존성 스캔을 제출할 수 있는 REST API를 제공합니다.

---

## 개요

```
빌드 파이프라인
       │
       │  POST /api/scan
       │  Authorization: Bearer oswl_<키>
       │  Body: { version, components[], submitterEmail, submitterPassword }
       ▼
OsWL 서버
  ├── API 키 인증 → 프로젝트 확인
  ├── 제출자 자격증명 인증 → SCAN_SUBMIT 권한 확인
  ├── 의존성 목록 수집
  └── CVE + 라이선스 데이터 비동기 보강 (OSV / deps.dev)
```

---

## 사전 요구사항

1. OsWL에 등록된 **프로젝트** (먼저 대시보드를 통해 생성 — 이름은 무엇이든 가능).
2. **프로젝트 API 키** (`oswl_...`) — **설정 → CLI** 탭 또는 프로젝트의 API 키 페이지에서 발급.
3. 스캔 제출자로 사용할 `SCAN_SUBMIT` 권한이 있는 **사용자 계정**.

---

## API 키 관리

### 프로젝트 범위 키 생성

```
POST /api/projects/{projectId}/keys
```

UI를 통해: 프로젝트 열기 → **설정(⚙)** → **CLI** 탭 → **키 생성**.

### 키 목록 조회

```
GET /api/projects/{projectId}/keys
```

### 키 취소

```
DELETE /api/projects/{projectId}/keys/{keyId}
```

### 관리자 전역 키

시스템 관리자는 **설정 → 관리자 → CLI 키**에서 프로젝트 간 키를 관리할 수 있습니다:

```
GET    /api/admin/cli-keys
POST   /api/admin/cli-keys
PATCH  /api/admin/cli-keys/{keyId}/toggle    # 활성화 / 비활성화
```

---

## API 인증

모든 CLI 엔드포인트는 다음 헤더가 필요합니다:

```
Authorization: Bearer oswl_<your_api_key>
```

### 키 테스트

```bash
curl -H "Authorization: Bearer oswl_<key>" \
     http://localhost:8080/api/scan/ping
```

예상 응답:

```json
{ "status": "ok", "projectId": 42 }
```

---

## 스캔 제출

```
POST /api/scan
Authorization: Bearer oswl_<key>
Content-Type: application/json
```

### 요청 본문

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
      "dependencyInfo": "Direct (1)",
      "dependencyPaths": [
        [
          { "name": "com.example:my-app", "version": "1.4.2" },
          { "name": "org.springframework:spring-core", "version": "6.1.4" }
        ]
      ]
    },
    {
      "name": "lodash",
      "version": "4.17.21",
      "ecosystem": "NPM",
      "dependencyInfo": "Transitive (2)",
      "dependencyPaths": []
    }
  ]
}
```

### 필드 설명

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `version` | string | ✅ | 스캔 시점의 프로젝트 버전 (예: `"1.4.2"`) |
| `submitterEmail` | string | ✅ | 스캔을 제출하는 OsWL 사용자 이메일 |
| `submitterPassword` | string | ✅ | 제출자 비밀번호 — 서버 측에서 BCrypt로 검증; 저장되거나 로그에 기록되지 않음 |
| `components` | array | — | 발견된 OSS 컴포넌트 목록 |
| `components[].name` | string | ✅ | 패키지 이름 (Maven은 `group:artifact`, npm은 패키지명) |
| `components[].version` | string | — | 패키지 버전 |
| `components[].ecosystem` | string | ✅ | 다음 중 하나: `MAVEN`, `NPM`, `PYPI`, `GO`, `CARGO`, `NUGET`, `RUBYGEMS` |
| `components[].dependencyInfo` | string | — | 사람이 읽을 수 있는 경로 요약 (예: `"Direct (1) + Transitive (3)"`) |
| `components[].dependencyPaths` | array of arrays | — | 루트에서 이 컴포넌트까지의 전체 경로 트리 |

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

### 스캔 상태 확인

```
GET /api/scan/{scanId}/status
```

응답:

```json
{
  "scanId": 87,
  "status": "COMPLETED",
  "componentCount": 138
}
```

상태 값: `PENDING` → `SCANNING` → `ANALYZING` → `COMPLETED` (또는 `FAILED`)

---

## GitHub Actions 예시

```yaml
- name: OsWL 스캔 제출
  run: |
    curl -s -X POST https://oswl.example.com/api/scan \
      -H "Authorization: Bearer ${{ secrets.OSWL_API_KEY }}" \
      -H "Content-Type: application/json" \
      -d @scan-payload.json
```

각 언어의 의존성 해석기(Maven, npm ls, pip list, go list 등)를 사용하여 `scan-payload.json`을 생성하세요.

---

## Maven 예시 (Bash)

```bash
#!/usr/bin/env bash
# Maven 의존성 수집 후 OsWL에 제출

VERSION=$(mvn help:evaluate -Dexpression=project.version -q -DforceStdout)

# JSON 페이로드 생성
COMPONENTS=$(mvn dependency:list -DincludeScope=runtime -q | \
  grep ':.*:' | \
  awk '{print $1}' | \
  jq -R 'split(":") | {"name": "\(.[0]):\(.[1])", "version": .[3], "ecosystem": "MAVEN"}' | \
  jq -s '.')

PAYLOAD=$(jq -n \
  --arg ver "$VERSION" \
  --arg email "$OSWL_USER_EMAIL" \
  --arg pass "$OSWL_USER_PASSWORD" \
  --argjson comps "$COMPONENTS" \
  '{"version":$ver,"submitterEmail":$email,"submitterPassword":$pass,"components":$comps}')

curl -s -X POST "$OSWL_URL/api/scan" \
  -H "Authorization: Bearer $OSWL_API_KEY" \
  -H "Content-Type: application/json" \
  -d "$PAYLOAD"
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
