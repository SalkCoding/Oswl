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

각 임포트는 자체 진행 카드를 가진 비동기 **작업(job)**으로 실행됩니다.

| 단계 | 설명 |
|---|---|
| `QUEUED` | 워커 슬롯 대기 또는 곧 시작 |
| `CLONING` | 저장소 클론 — 기본값은 매니페스트 파일만 가져오는 blobless sparse checkout이며, 전체 얕은 클론(shallow clone)으로 대체될 수 있습니다 |
| `PARSING` | 생태계 감지 및 의존성 매니페스트 파싱 |
| `SCANNING` | 프로젝트 생성 및 스캔 페이로드 제출 |
| `ENRICHING` | CVE/라이선스 데이터 파이프라인 (deps.dev, OSV, 위협 인텔) |
| `DONE` | 데이터 파이프라인 완료 — CVE/라이선스 결과 준비 완료; AI 요약은 백그라운드에서 계속 생성 중일 수 있음 |
| `FAILED` | 오류 또는 취소 — 작업 메시지 확인 |

작업 카드는 단계 외에도 다음과 같은 실시간 필드를 노출합니다.

| 필드 | 설명 |
|---|---|
| `percent` | 0~100 사이의 연속 진행률. 구간은 `CLONING` 5–20, `PARSING` 20–40(매니페스트 N/M개 처리), `SCANNING` 40–55, `ENRICHING` 데이터 조회 55–80, AI 블록 80–100입니다. |
| `subPhase` | 현재 보강 블록: `CVE`, `LICENSE`, `INSIGHTS` 중 하나 (v1.0.4부터 보안 상태·보안/라이선스 트렌드·버전 비교 인사이트가 하나의 결합 호출로 통합되었습니다). |
| `detailLines` | 배치 진행 상황과 라이브러리별 발견 요약 등 롤링 로그 라인. |
| `aiPreviews` | 자유 형식 AI 출력의 롤링 테일. v1.0.4부터는 결합-인사이트 호출이 스트리밍 텍스트 대신 JSON을 반환하므로 일반 스캔 중에는 이 필드가 채워지지 않습니다. |
| `aiStatus` | `NOT_APPLICABLE`, `PENDING`, `RUNNING`, `COMPLETED`, `FAILED` 중 하나. `aiStatus`가 아직 `PENDING`이나 `RUNNING`인 상태에서도 작업이 `DONE`에 도달할 수 있습니다. |
| `cacheTotal` / `cacheHit` / `cacheToFetch` | deps.dev 캐시 판정 결과; 최소 한 개 컴포넌트가 캐시에서 제공되면 캐시 적중 배지로 표시됩니다. |

동시 실행 및 처리 방식:

- 최대 **3건**까지 동시 실행됩니다 (`oswl.quick-import.max-concurrent`, 기본 `3`). 초과분은 FIFO 큐에서 대기하며 `queuePosition`으로 순서를 표시합니다. 사용자별로 최대 **3건**까지 큐에 넣을 수 있습니다 (`oswl.quick-import.max-queued-per-user`, 기본 `3`).
- 이전 작업이 끝나기 전에도 **여러 임포트**를 시작할 수 있습니다.
- 이미 대기 중이거나 실행 중인 작업이 있는 저장소를 다시 시작하면 **409 Conflict**로 거부됩니다 — 기존 작업이 끝나거나 취소한 뒤 다시 시도하세요.
- 각 작업 카드의 **취소** 버튼(`POST /api/quick-import/job/{jobId}/cancel`)으로 작업을 중단할 수 있습니다. 대기 중인 작업은 즉시 멈추고, 실행 중인 작업은 다음 단계 경계에서 멈춥니다(긴 클론은 먼저 끝납니다). 취소된 작업은 `FAILED` 상태에 "취소됨" 메시지로 표시됩니다. 작업이 `DONE`에 도달한 이후에는, 계속 실행 중인 AI 보강이 독립적으로 이어지며 Quick Import 작업 취소로 중단되지 않습니다.
- UI는 **`GET /api/quick-import/job/{jobId}/stream`** (SSE `job-update`)을 구독하고, 필요 시 `GET /api/quick-import/job/{jobId}` 폴링으로 대체합니다. 진행률이 진전되지 않는 한 고빈도 업데이트는 500ms당 1개의 SSE 프레임으로 제한됩니다.
- 스캔이 끝나면 단계별 소요 시간(`clone`, `parse`, `ingest`, `depsdev`, `osv`, `threatintel`, `ai.*`, `cleanup`)을 요약한 `[Timing]` INFO 로그 한 줄이 출력됩니다.

임시 클론 디렉터리는 수집 후 비동기 삭제 큐에 들어갑니다.

### CLI와 공유 파서

의존성 감지·매니페스트 파싱은 공식 CLI(`oswl scan`)와 동일한 **`DependencyManifestParserService`** 를 사용합니다. CLI는 `GET /api/scan/manifest-rules`(정적 파일: `/scripts/manifest-rules.json`) 기준으로 manifest zip을 업로드하고, Quick Import는 저장소를 클론한 뒤(기본값은 blobless sparse checkout) 같은 규칙으로 트리를 순회합니다. [CLI 연동](CLI-Integration.md) 참고.

### 성능 관련 참고사항

v1.0.4에서 도입된 여러 파이프라인 변경 사항이 Quick Import의 속도와 리소스 사용량에 영향을 줍니다.

- **클론**: 기본값인 blobless sparse checkout(`--filter=blob:none --sparse`)은 파서가 필요로 하는 매니페스트 파일 패턴만 가져옵니다. 부분 클론을 지원하지 않는 서버이거나 빌드 도구 실행 모드(`oswl.quick-import.allow-build-exec=true`)인 경우 전체 얕은 클론(shallow clone)으로 대체됩니다.
- **파싱**: 클론된 트리를 정확히 한 번만 순회하여 매니페스트 인덱스를 구축합니다 — 기존의 에코시스템별 다중 순회 방식을 대체합니다.
- **정리**: 임시 클론 디렉터리는 비동기로 삭제되므로 삭제 작업이 더 이상 단계 전환을 막지 않습니다.
- **보강 HTTP 클라이언트**: deps.dev와 OSV 클라이언트는 명시적인 연결/읽기 타임아웃을 사용합니다. deps.dev 요청은 고유한 `(ecosystem, name, version)` 기준으로 중복 제거되며, 버전 메타데이터 캐시 적중 시에는 자체 TTL로 재조회를 건너뛰고, 동시성은 `oswl.client.deps-dev.max-concurrent`(기본 `24`)로 설정할 수 있습니다.
- **수집(Ingest)**: 라이브러리는 대량 조회로 해석되어 청크 단위로 저장되며, CVE 위협 인텔 업데이트는 배치로 처리됩니다.
- **AI**: 동일한 CVE/라이선스 컨텍스트(심각도, CVSS, 수정 버전, EPSS 구간, KEV 상태, 배포 프로필 등으로 해시)는 이전 AI 요약을 재사용합니다. 보안 상태, 트렌드, 버전 비교 인사이트는 하나의 결합된 호출로 생성됩니다.
- **폐쇄망 모드**: `oswl.airgapped.enabled=true`일 때 스캔 결과 페이지와 SBOM/VEX/SARIF 내보내기에 취약점 정의 기준일 배너가 표시됩니다.

---

## SBOM으로 가져오기 (v1.0.4)

소스를 클론할 수 없는 경우 — 협력사 납품물, 컨테이너 베이스 이미지, 다른 도구가 만든 SBOM — 해당 CycloneDX 파일을 업로드하면 됩니다.

**Quick Import → SBOM 가져오기** 또는 `POST /api/sbom/import` (multipart).

컴포넌트는 CycloneDX `components` 배열의 `purl`로 읽어들이며, 이후 클론 스캔과 완전히 동일하게 보강됩니다 — CVE, 라이선스, KEV / EPSS, 공급망 배지가 모두 적용됩니다.

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

[API 레퍼런스 — Quick Import](API-Reference.md#quick-import) 참고. 인터랙티브 스키마는 Swagger UI(`local` 프로파일).

---

## 문제 해결

| 증상 | 가능한 원인 |
|---|---|
| "Token validation failed" | PAT 권한 부족 또는 만료 |
| "Repository not found" | 비공개 저장소 접근 권한 없음 |
| `CLONING` / `PARSING`에서 멈춤 | 네트워크 또는 디스크 공간 |
| `ENRICHING`에서 멈춤 | OSV / deps.dev 등 외부 API 속도 제한 |
| 폴링 시 job `404` | 서버 재시작 — 메모리 작업은 약 30분 후 만료 |
