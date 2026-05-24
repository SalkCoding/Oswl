# Quick Import

Quick Import를 사용하면 CLI 코드 작성 없이 VCS(Version Control System) 호스트 — 현재 **GitHub**, **GitLab**, **Bitbucket** — 에서 직접 프로젝트를 가져올 수 있습니다.

---

## 지원 제공업체

| 제공업체 | 인증 방식 |
|---|---|
| GitHub | Personal Access Token (PAT) |
| GitLab | Personal Access Token (PAT) |
| Bitbucket | App Password |

---

## 1단계 — VCS 연결 추가

**설정 → VCS**로 이동하여 **연결 추가**를 클릭합니다.

다음 정보를 입력합니다:

| 필드 | 설명 |
|---|---|
| **제공업체** | GitHub / GitLab / Bitbucket |
| **표시 이름** | 친숙한 레이블 (예: "GitHub – my-org") |
| **액세스 토큰** | `repo` / `read_repository` 권한이 있는 PAT 또는 App Password |

OsWL이 즉시 제공업체 API에 대해 토큰을 검증하고 인증된 사용자 이름을 표시합니다.  
토큰은 `OSWL_ENCRYPTION_KEY`를 사용하여 **암호화**(AES-256-GCM)되어 저장됩니다.

> 필요 권한: `SETTINGS_VCS_MANAGE` 또는 시스템 관리자.

---

## 2단계 — 저장소 임포트

**프로젝트 → Quick Import** (`/projects/quick-import`)로 이동합니다.

1. 드롭다운에서 VCS **연결**을 선택합니다.
2. 계정과 접근 가능한 **저장소** 목록이 자동으로 불러와집니다.
3. **저장소**를 선택합니다.
4. **브랜치**를 선택합니다.
5. **임포트**를 클릭합니다.

OsWL이 저장소를 임시 디렉토리에 클론하고, 의존성 트리를 분석하여 스캔을 제출합니다. 실시간 진행 표시기가 각 단계를 보여줍니다:

| 단계 | 설명 |
|---|---|
| `SCANNING` | 저장소 클로닝 및 의존성 매니페스트 파싱 |
| `ANALYZING` | NVD / OSV / deps.dev에서 CVE 및 라이선스 데이터 보강 |
| `COMPLETED` | 스캔 완료 성공 |
| `FAILED` | 오류 발생 — 스캔 레코드의 오류 메시지 확인 |

> 클로닝에는 VCS 연결에 저장된 액세스 토큰이 사용됩니다. 임시 클론은 수집 후 삭제됩니다.

---

## 브랜치 재임포트

언제든지 동일한 저장소/브랜치를 다시 임포트하여 새로운 스캔 결과를 생성할 수 있습니다. OsWL은 모든 스캔을 별도로 기록하므로 [버전 비교](Version-Diff.md) 및 [리스크 트렌드](Risk-Trend.md)에서 비교할 수 있습니다.

---

## GitHub Enterprise Server (GHES)

GitHub Enterprise Server를 사용하려면 환경 변수를 설정하세요:

```bash
OSWL_GITHUB_API_BASE=https://github.example.com/api/v3
```

GitLab과 Bitbucket 자체 호스팅 인스턴스는 연결 설정에서 적절한 API 기본 URL을 제공하여 지원됩니다.

---

## 문제 해결

| 증상 | 가능한 원인 |
|---|---|
| "Token validation failed" | PAT 권한 부족 (`repo` / `read_repository`) 또는 토큰 만료 |
| "Repository not found" | 저장소가 비공개이고 토큰에 접근 권한 없음 |
| `SCANNING` 단계에서 중단 | VCS 호스트에 대한 네트워크 문제, 또는 클론 임시 디렉토리 공간 부족 |
| `ANALYZING` 단계에서 중단 | NVD / OSV / deps.dev 요청 속도 제한 도달; 자동 재시도됨 |
