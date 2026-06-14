# OsWL 문서

**OsWL** (Open-source Software Watchlist) 문서 허브에 오신 것을 환영합니다.

OsWL은 단일 마이크로서비스부터 전체 제품 포트폴리오까지 모든 OSS 의존성의 CVE 취약점과 라이선스 컴플라이언스를 팀이 한 곳에서 추적할 수 있도록 하는 사내 **SCA(Software Composition Analysis)** 플랫폼입니다.

---

## 탐색

| 페이지 | 내용 |
|---|---|
| [시작하기](Getting-Started.md) | 시스템 요구사항, 설치, 설정 마법사, 첫 로그인 |
| [사용자 가이드](User-Guide.md) | 프로젝트 대시보드, 스캔 카드, 휴지통, 필터 |
| [Quick Import](Quick-Import.md) | GitHub / GitLab / Bitbucket 연결, 브랜치 임포트 |
| [CLI 연동](CLI-Integration.md) | API 키, 스캔 페이로드 형식, 파이프라인 연동 |
| [보안 센터](Security-Center.md) | CVE 목록, 심각도 순위, 상태 업데이트, 일괄 작업 |
| [라이선스 분석](License-Analysis.md) | SPDX 감지, 정책 항목, 리스크 배지 |
| [리스크 트렌드](Risk-Trend.md) | 히스토리 차트, AI 인사이트, 스캔 한도 |
| [버전 비교](Version-Diff.md) | 두 버전 나란히 비교 |
| [관리](Administration.md) | 사용자 관리, 역할, 감사 로그, 보안 및 SMTP 설정 |
| [권한 레이어](Authorization-Layers.md) | 역할 템플릿 vs 프로젝트 멤버십 |
| [프로젝트 접근 제어](Project-Access-Control.md) | ACL 기술 참고 |
| [운영 배포 체크리스트](Production-Deployment-Checklist.md) | `prod` 프로파일 출시 전 점검 |
| [데이터베이스 스키마](Database-Schema.md) | Flyway, `ddl-auto`, `oswl-app/src/main/resources/db/` 마이그레이션 |
| [스캔 API 보안](Scan-Api-Security.md) | CLI 스캔 제출 보호 개요 |
| [API 레퍼런스](API-Reference.md) | 전체 REST 엔드포인트 목록 |
| [용어사전](Glossary.md) | OsWL 모든 용어 정의 |

---

## 핵심 개념 한눈에 보기

```
┌──────────────────────────────────────────────────────────────┐
│  Project (프로젝트)                                           │
│   ├─ ProjectVersion  (브랜치 스냅샷)                          │
│   └─ ScanResult      (CLI / Quick Import 스캔 1회)            │
│        └─ ScanComponent → Library → CVE / License            │
└──────────────────────────────────────────────────────────────┘
```

* **Project**는 최상위 단위 — 보통 저장소 하나에 해당합니다.
* **Scan**은 특정 시점의 전체 의존성 트리를 캡처합니다.
* **Library**는 전역 공유 레코드(이름 + 버전 + 에코시스템)로, CVE 데이터는 한 번 보강되어 모든 프로젝트에서 재사용됩니다.
* **CVE** 데이터는 deps.dev와 OSV에서(OSV가 제공 시 CWE 포함), 라이선스 데이터는 deps.dev에서 가져옵니다.

---

## 주요 워크플로우

1. **프로젝트 등록** → Quick Import (VCS) 또는 CLI 푸시
2. **스캔 실행** → 임포트 시 자동 실행, 또는 `POST /api/scan`으로 수동 실행
3. **검토** → CVE는 보안 센터, 정책 위반은 라이선스 탭에서 확인
4. **시간에 따른 추적** → AI 요약이 포함된 리스크 트렌드 차트
5. **관리** → CVE 상태 업데이트, 라이선스 정책 조정, 리포트 내보내기

---

## 도움 받기

* **Swagger UI** (로컬 프로파일만): `http://localhost:8080/swagger-ui.html`
* **H2 콘솔** (로컬 프로파일만): `http://localhost:8080/h2-console`
* **이슈**: [GitHub Issues](https://github.com/SalkCoding/Oswl/issues)
