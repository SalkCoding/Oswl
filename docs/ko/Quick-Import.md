# Quick Import

Quick Import를 사용하면 CLI 없이 VCS 호스트 — **GitHub**, **GitLab**, **Bitbucket** — 에서 직접 프로젝트를 가져올 수 있습니다.

---

## 지원 제공업체

| 제공업체 | 인증 방식 |
|---|---|
| GitHub | Personal Access Token (PAT) |
| GitLab | Personal Access Token (PAT) |
| Bitbucket | App Password |

---

## 1단계 — VCS 연결 추가

**설정 → VCS**에서 **연결 추가**를 클릭합니다.

| 필드 | 설명 |
|---|---|
| **제공업체** | GitHub / GitLab / Bitbucket |
| **표시 이름** | 친숙한 레이블 (예: "GitHub – my-org") |
| **액세스 토큰** | `repo` / `read_repository` 권한이 있는 PAT 또는 App Password |

OsWL이 제공업체 API로 토큰을 즉시 검증합니다. 토큰은 **저장 시 암호화**됩니다 (`OSWL_ENCRYPTION_KEY`, 운영 환경 필수).

> 필요 권한: `SETTINGS_VCS_MANAGE` 또는 시스템 관리자.

---

## 2단계 — 저장소 임포트

**프로젝트 → Quick Import** (`/projects/quick-import`)를 엽니다.

다음 중 하나로 진행할 수 있습니다.

1. **저장소 URL** (및 선택적 브랜치)을 붙여넣고 **가져오기 & 스캔** 클릭  
2. 연결된 계정 **브라우저**에서 저장소·브랜치 선택 후 임포트. GitHub 목록은 API의 `Link` 헤더 페이지네이션을 따라가며(페이지당 100개, 계정/조직당 최대 1,000개), 그보다 큰 계정은 로드 실패 대신 잘려서 표시됩니다.

### 진행 상태와 동시 실행

각 임포트는 별도 **작업(job)** 카드로 표시됩니다.

| 단계 | 설명 |
|---|---|
| `QUEUED` | 슬롯 대기 또는 곧 시작 |
| `CLONING` | 저장소 얕은 클론 |
| `PARSING` | 생태계 감지 및 의존성 매니페스트 파싱 |
| `SCANNING` | 프로젝트 생성 및 스캔 제출 |
| `ENRICHING` | CVE/라이선스 보강 및 AI 요약(설정 시) |
| `DONE` | 완료 — 프로젝트·API 키 사용 가능 |
| `FAILED` | 오류 또는 취소 — 작업 메시지 확인 |

- 최대 **2건**까지 동시 실행 (`oswl.quick-import.max-concurrent`, 기본 `2`). 초과분은 FIFO 큐에서 대기하며 `queuePosition`으로 순서를 표시합니다.
- 이전 작업이 끝나기 전에도 **여러 임포트**를 시작할 수 있습니다.
- 이미 대기 중이거나 실행 중인 작업이 있는 저장소를 다시 시작하면 **409 Conflict**로 거부됩니다 — 기존 작업이 끝나거나 취소한 뒤 다시 시도하세요.
- 각 작업 카드의 **취소** 버튼(`POST /api/quick-import/job/{jobId}/cancel`)으로 작업을 중단할 수 있습니다. 대기 중인 작업은 즉시 멈추고, 실행 중인 작업은 다음 단계 경계에서 멈춥니다(긴 클론은 먼저 끝납니다). 취소된 작업은 `FAILED` 상태에 "취소됨" 메시지로 표시됩니다.
- UI는 **`GET /api/quick-import/job/{jobId}/stream`** (SSE `job-update`)을 구독하고, 필요 시 `GET /api/quick-import/job/{jobId}` 폴링으로 대체합니다.
- `ENRICHING` 중에는 `percent`, `subPhase` (`CVE`, `LICENSE`, `POSTURE`, `TREND`, `DIFF`), `detailLines`, `aiPreviews`가 채워집니다.

임시 클론 디렉터리는 수집 후 삭제됩니다.

### CLI와 공유 파서

의존성 감지·매니페스트 파싱은 공식 CLI(`oswl scan`)와 동일한 **`DependencyManifestParserService`** 를 사용합니다. CLI는 `GET /api/scan/manifest-rules`(정적 파일: `/scripts/manifest-rules.json`) 기준으로 manifest zip을 업로드하고, Quick Import는 저장소를 얕게 클론한 뒤 같은 규칙으로 트리를 순회합니다. [CLI 연동](CLI-Integration.md) 참고.

---

## 브랜치 재임포트

동일 저장소/브랜치를 다시 임포트하면 새 스캔 결과가 생성됩니다. [버전 비교](Version-Diff.md)와 [위험 추세](Risk-Trend.md)에서 비교하세요.

---

## GitHub Enterprise Server (GHES)

```bash
OSWL_GITHUB_API_BASE=https://github.example.com/api/v3
```

GitLab·Bitbucket 자체 호스팅은 VCS 연결의 API 기본 URL로 지원합니다.

---

## REST API 요약

[API 레퍼런스 — Quick Import](../API-Reference.md#quick-import) 참고. 인터랙티브 스키마는 Swagger UI(`local` 프로파일).

---

## 문제 해결

| 증상 | 가능한 원인 |
|---|---|
| "Token validation failed" | PAT 권한 부족 또는 만료 |
| "Repository not found" | 비공개 저장소 접근 권한 없음 |
| `CLONING` / `PARSING`에서 멈춤 | 네트워크 또는 디스크 공간 |
| `ENRICHING`에서 멈춤 | OSV / deps.dev 등 외부 API 속도 제한 |
| 폴링 시 job `404` | 서버 재시작 — 메모리 작업은 약 30분 후 만료 |
