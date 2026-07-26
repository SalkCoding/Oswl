# 프로젝트 접근 제어 (기술 참고)

> **비기술 개요:** [권한 레이어](Authorization-Layers.md)에서 역할 템플릿·프로젝트 멤버십·시스템 관리자를 설명합니다.

## 개요

OsWL은 **두 레이어**가 함께 동작합니다.

1. **전역 권한** — **역할 템플릿**에 붙는 `Permission` (예: `SCAN_VIEW`, `LICENSE_EXPORT`).
2. **프로젝트 멤버십** — `project_members`에 사용자가 등록되어 있는지.

보통 **권한 + 멤버십**이 모두 필요합니다. **시스템 관리자**는 멤버십 검사를 생략합니다.

## 데이터

| 테이블 | 용도 |
|--------|------|
| `project_members` | `user_id` ↔ `project_id`, 역할 `ADMIN` 또는 `MEMBER` |

- 멤버십 **ADMIN** — 프로젝트 생성자.
- 멤버십 **MEMBER** — 기본값; 기능 허용은 전역 `Permission`이 결정.

## 적용 지점

`ProjectAccessService`:

| 메서드 | 용도 |
|--------|------|
| `assertCanViewProject` | 프로젝트 단위 화면·API, 거부 시 403 |
| `assertCanSubmitScan` | CLI 스캔 제출 |
| `accessibleProjectIds` | 목록·휴지통 필터 |

## 멤버십 검사가 있는 영역

보안 센터, 라이선스(내보내기 포함), 컴포넌트 상세, 버전 비교, 리스크 트렌드, 스캔 기록, 프로젝트 API 키, VCS 브랜치 조회, 스캔 상태 폴링 등.

## CLI 스캔 인증

`POST /api/scan`은 다음을 모두 요구합니다:

1. 유효한 프로젝트 **API 키** (인터셉터에서 검증)
2. `SCAN_SUBMIT` 권한을 가진 제출자의 **이메일 + 비밀번호**
3. 제출자가 해당 프로젝트의 **`project_members`**에 포함되어 있을 것

감사 이벤트: `SCAN.INGEST`, `SCAN.AUTH_FAILURE`, `SCAN.API_KEY_FAILURE`, `SCAN.AUTH_RATE_LIMITED`. 속도 제한은 `oswl.scan-api.*`로 설정할 수 있습니다.

## 신규 설치 vs 업그레이드

- **신규 설치:** Hibernate `ddl-auto`(또는 사용 중인 스키마 도구)가 `project_members`를 생성하며, 생성자는 자동으로 추가됩니다.
- **기존 데이터베이스:** `project_members` 테이블이 존재하는지 확인한 뒤 재시작하면, `ProjectMemberBootstrapRunner`가 필요한 경우 생성자를 소급 채워 넣습니다.

## 관련 문서

- [권한 레이어](Authorization-Layers.md)
- [스캔 API 보안](Scan-Api-Security.md)
- [CLI 연동](CLI-Integration.md)
- [CLI 연동](CLI-Integration.md)
