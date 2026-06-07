# OsWL — Project Guidelines

OsWL는 OSS 컴포넌트의 보안 취약점(CVE)과 라이선스 리스크를 추적·관리하는 사내 SCA(Software Composition Analysis) 플랫폼입니다.

# Test account rule
테스트 계정은 무조건
이메일: test@test.com
비밀번호: 1q2w3e4r
로 진행하며 이름은 test로 통일한다.
OTP는 개발용이라 000000으로 통과 가능하며, 테스트용으로 발급할 때는 123456을 사용한다.

## Tech Stack

- **Backend:** Spring Boot 4.0.5 (Java 25) — WebMVC, Data JPA, Security, Mail, Validation, AspectJ
- **View:** Thymeleaf + `thymeleaf-extras-springsecurity6`
- **Frontend:** Vanilla HTML/CSS/JS, Tailwind CSS 3.4 (precompiled standalone CLI), Chart.js, HTMX·Alpine.js (필요 시)
- **DB:** PostgreSQL (prod) / H2 file-mode (`local` profile, PostgreSQL 호환)
- **Auth:** Spring Security + Email OTP(2FA) + Trusted Device + Single-Session Enforcement
- **API Docs:** `springdoc-openapi` (Controller 스펙은 `controller/spec/*Spec.java` 인터페이스로 분리)
- **Local SMTP:** GreenMail 임베디드 (포트 3025, OTP 코드는 서버 로그에 `*** OTP CODE: NNNNNN ***` 로 출력)
- **Build:** Gradle Wrapper (`./gradlew`)

## Project Structure

```text
OsWL/
├── src/main/
│   ├── java/com/salkcoding/oswl/
│   │   ├── OswlApplication.java
│   │   ├── aop/                 # 공통 관점 (감사 로깅 등)
│   │   ├── auth/                # 인증·인가 모듈 (자체 패키지로 분리)
│   │   │   ├── config/          # SecurityConfig, LocalSmtpConfig 등
│   │   │   ├── controller/      # /login, /setup, /api/admin/*, /api/settings/{security,vcs,cache}
│   │   │   ├── dto/  entity/  enums/  repository/  security/  service/  web/
│   │   ├── client/              # 외부 API 클라이언트 (OSV, deps.dev, GitHub …)
│   │   ├── controller/          # 비즈니스 컨트롤러 (Project, SecurityCenter, License, RiskTrend …)
│   │   │   └── spec/            # 컨트롤러 스펙 인터페이스 (springdoc 어노테이션 집중)
│   │   ├── domain/entity/       # JPA 엔티티 (Project, ScanResult, Library, Cve …)
│   │   ├── domain/enums/        # ImportSource, LicenseStatus, Severity …
│   │   ├── dto/  exception/  repository/  scheduler/  service/  web/
│   └── resources/
│       ├── application.yaml             # 공통 + profile 스위치
│       ├── application-local.yaml       # H2 file (./oswl-db), devtools, 자세한 로깅
│       ├── application-prod.yaml        # PostgreSQL
│       ├── static/{css,js,img,icon,graphic,scripts}/
│       └── templates/
│           ├── auth/{login,otp-verify,setup}.html
│           ├── projects/{index,quick-import,cli-integration,git-integration}.html
│           ├── security-center/{index,fragments/}
│           ├── component-detail/{index,fragments/}
│           ├── license/  risk-trend/  scan-history/  version-diff/  glossary/
│           ├── settings/{index, tabs/{admin,security,ai,vcs,cli,cache}.html}
│           ├── error/{401,403,404,500,503,_owl-error}.html
│           └── fragments/{head,topbar,risk-graph,tags}.html
├── tailwind/{input.css, tailwind.config.js}    # Tailwind 빌드 입력
├── build.gradle / settings.gradle / gradlew
└── README.md
```

## 핵심 도메인 (참고용)

- **Repository / Project / ProjectVersion** — 리포지토리(연결된 VCS) 단위로 `Project`를 생성하고, 브랜치/버전별로 `ProjectVersion`을 관리합니다.
- **Scan / ScanResult / ScanComponent / DependencyPath / DependencyGraph** — 각 스캔(수집·분석) 결과와 컴포넌트·의존성 경로, 그래프를 나타냅니다.
- **SBOM / BillOfMaterials** — 스캔 결과나 외부 연동으로부터 생성되는 SBOM(Software Bill Of Materials)을 저장·조회합니다
- **Library / Artifact** — 공유 라이브러리(예: `group:artifact@version`)와 메타데이터를 중심으로 관리합니다.
- **Vulnerability (CVE/OSV/OSV-like)** — 취약점 엔티티(심각도, 설명, 출처, 패치 정보) 및 연관 라이브러리 매핑.
- **License / LicensePolicy / PolicyViolation** — 라이선스 정보와 정책, 정책 위반 기록 및 정책 기반 알림/차단 로직.
- **Finding / Evidence / Enrichment** — 스캔·분석에서 도출된 소결과(발견사항), 증거자료, 외부 데이터 보강(Enrichment).
- **AiSetting / ApiKey / CacheSetting / Webhook** — AI 설정, 프로젝트 CLI 키, 라이브러리 보강 캐시(`cache_settings`), 웹훅 등록·관리.
- **Auth entities:** User / Role / RoleTemplate / AuditLog / SecuritySetting / VcsConnection / TrustedDevice / OtpChallenge

설계 원칙: 도메인은 공유 가능한 `Library` 중심, 스캔 결과는 불변(이력)으로 저장, 정책·알림은 별도 엔티티로 분리하여 재사용성을 높입니다.

## 주요 URL 맵 (요약)

아래는 주요 엔드포인트의 요약(REST/HTTP 기반). 세부 스펙은 `controller/spec/*Spec.java` 인터페이스에 명세합니다.

- **Auth / Account**: `GET/POST /login`, `GET/POST /login/otp-verify`, `POST /login/otp-resend`, `GET/POST /setup`, `POST /api/auth` (토큰/CLI 인증)
- **Projects / Repositories**: `GET /projects`, `GET /projects/list`, `POST /projects` (수동 생성), `GET/PUT/DELETE /projects/{id}`, `POST /projects/{id}/restore`, `DELETE /projects/{id}/permanent`, `DELETE /projects/trash/all|selected`, `POST /projects/trash/restore-selected`
- **Project Versions / Branches**: `GET /projects/{id}/versions`, `POST /projects/{id}/versions`, `GET /projects/{projectId}/versions/{versionId}`
- **Quick Import / VCS Connections**: `GET /projects/quick-import`, `GET /api/quick-import/connections`, `POST /api/quick-import/start`, `GET /api/quick-import/job/{jobId}`, `POST /api/import/webhook` (VCS push hook)
- **VCS Providers (GitHub/GitLab/Bitbucket)**: `POST/DELETE /api/github/connect|disconnect|accounts/{login}`, `GET /api/github/{status,accounts,repos,branches,branch-updated-at}`, 유사한 경로로 GitLab/Bitbucket 지원
- **Scans / Scan Jobs**: `POST /api/scan` (새 스캔 시작), `GET /api/scan/{scanId}/status`, `GET /api/scan/{scanId}/result`, `GET /projects/{id}/scan-history`, `DELETE /projects/{id}/scan-history/{scanId}`, `POST /api/scan/schedule` (예약 스캔), `GET /api/scan/jobs`
- **Security Center / Findings**: `GET /projects/{id}/security-center`, `PATCH /projects/{id}/security-center/bulk-status`, `GET /projects/{id}/findings`, `PATCH /projects/{id}/findings/{findingId}`
- **Component Detail / SBOM / Dependency Graph**: `GET /projects/{id}/components/{compId}` (HX-Request fragment), `GET /projects/{id}/sbom/{sbomId}`, `GET /projects/{id}/dependency-graph`
- **License / Policy**: `GET /projects/{id}/license`, `GET/PUT /api/policies/license`, `POST /api/policies/evaluate`, `GET /api/policies/violations` 
- **Risk Trend / Analytics**: `GET /projects/{id}/risk-trend` (최대 `oswl.risk-trend.limit` 스캔), `GET /api/analytics/risk`, `GET /api/metrics` (애플리케이션 지표)
- **Version Diff / History**: `GET /projects/{id}/version-diff`, `GET /projects/{id}/history` 
- **Glossary / Docs**: `GET /glossary`, `GET /docs/**`
- **Settings / Admin**: `GET /settings` (탭: admin/security/ai/vcs/cli/cache), `GET/PUT /api/settings/security`, `POST /api/settings/security/mail/test`, `GET/PUT /api/settings/ai`, `PUT /api/settings/ai/deactivate`, `PUT /api/settings/ai/activate/{provider}`, `GET/POST /api/settings/vcs`, `DELETE /api/settings/vcs/{id}`, `GET/PUT /api/settings/cache`, `POST /api/settings/cache/clear`
- **Admin Users / Role Templates / Audit**: `GET/POST /api/admin/users`, `PUT /api/admin/users/{id}/{roles|display-name|activate|deactivate}`, `DELETE /api/admin/users/{id}`, `GET/POST /api/admin/role-templates`, `GET /api/admin/role-templates/permissions`, `PUT/DELETE /api/admin/role-templates/{id}`, `GET /api/admin/audit-logs`, `GET /api/admin/audit-logs/export.csv`
- **AI / Enrichment / External Data**: `POST /api/ai/assist`, `GET/PUT /api/settings/ai`, `POST /api/external/enrich/{libraryId}`
- **Webhooks & Integrations**: `POST/GET/DELETE /api/webhooks`, `POST /api/webhooks/test`, `POST /api/external/push` (integration callbacks)
- **CLI Keys / API Keys**: `GET/POST /api/projects/{id}/api-keys`, `DELETE /api/projects/{id}/api-keys/{keyId}`, `GET/POST /api/admin/cli-keys`, `PATCH /api/admin/cli-keys/{keyId}/toggle`
- **CLI Public API / Scan API**: `POST /api/auth` (CLI token), `GET /api/scan/ping`, `POST /api/scan`, `GET /api/scan/{scanId}/status`, `GET /api/scan/{scanId}/result` 
- **SBOM / Exports**: `GET /api/projects/{id}/exports/sbom`, `GET /api/projects/{id}/exports/findings.csv`, `POST /api/projects/{id}/exports/start`
- **Health / Observability**: `GET /actuator/health`, `GET /actuator/metrics`, `GET /actuator/prometheus` (optional)
- **Test Data (local only)**: `GET /data/test`, `GET /data/test-api-key` (local-profile only)

참고: 컨트롤러 스펙은 `controller/spec/*Spec.java`에 집중시킵니다.

## Code Style & Architecture

**Backend**
- **Layered:** `Controller` → `Service` → `Repository`. Controller는 가능한 얇게.
- **Controller Spec 분리:** OpenAPI 어노테이션은 `controller/spec/*ControllerSpec.java` 인터페이스에 모은다. 구현체는 비즈니스 로직만 깔끔히.
- **DI:** Lombok `@RequiredArgsConstructor` 기반 생성자 주입. `@Autowired` 금지.
- **Authorization:** 메서드 레벨 `@PreAuthorize("hasPermission(null, 'PROJECT_VIEW') or hasRole('SYSTEM_ADMIN')")` 패턴.
- **Audit Logging:** 사용자 액션·시스템 이벤트는 `AuditLogService.log…()` 호출로 기록 (Setup, Security Setting 변경 등).
- **Profile gating:** 로컬 전용 빈은 `@Profile("local")` (예: `TestDataController`, `LocalSmtpConfig`).
- **JPA cascade:** Project → ProjectVersion/ScanResult/ScanComponent/DependencyPath, Library → Cve 가 cascade ALL.

**Frontend**
- **Vanilla First.** 필요할 때만 HTMX(부분 갱신), Alpine.js(소규모 상태) 추가. 서버 사이드 렌더링은 Thymeleaf로.
- **Tailwind CSS:** **CDN 금지**. `/css/tailwind.css` (Gradle 빌드 산출물) 만 사용. 새 클래스를 쓰면 빌드 시 자동 포함.
- **HTMX 패턴:** `Component Detail`처럼 `HX-Request: true` 헤더 시 fragment만 반환하는 컨트롤러 분기 활용.
- **Chart.js / D3.js**: Risk Trend 등 시각화에 사용 가능. CDN 또는 webjar.
- **자산 위치:** JS는 `static/js/`, 페이지별로 하위 폴더 분리 (`projects/`, `risk-trend/`).

## DB / 환경

- **로컬 DB:** H2 file (`./oswl-db.mv.db`). 콘솔: `http://localhost:8080/h2-console` (JDBC URL `jdbc:h2:file:./oswl-db`, user `sa`). `local`은 `ddl-auto: update`.
- **운영 DB:** PostgreSQL, `ddl-auto: validate`. 업그레이드 시 `src/main/resources/db/*.sql` 수동 실행 (`schema_cleanup.sql` 등). 문서: `docs/Database-Schema.md`.
- **DB 초기화:** 서버 정지 후 `oswl-db.*` 파일 삭제 → 재기동 시 빈 상태에서 시작 (Setup 화면으로 진입).
- **테스트 데이터 시드:** 로컬에서 `GET /data/test` 호출 (인증 필요). 모든 기존 데이터를 지우고 풍부한 케이스로 재시드.
- **암호화 키 (`OSWL_ENCRYPTION_KEY`):** 로컬은 더미 키가 박혀 있고, 운영은 `openssl rand -base64 32` 로 생성한 32바이트 키 필수.
- **OTP 메일:** 로컬 프로파일은 GreenMail이 자동 기동되어 모든 메일 가로챔. **OTP 코드는 서버 로그의 `*** OTP CODE: NNNNNN ***` 라인에서 확인** (실제 메일 발송 X).

## Build & Run

```powershell
./gradlew bootRun                  # 로컬 기동 (기본 profile=local, 포트 8080)
./gradlew build                    # 풀 빌드 (Tailwind 포함)
./gradlew test                     # JUnit Platform 테스트
./gradlew buildTailwindCss         # Tailwind만 재빌드
```

## Conventions

- **DB 명명:** snake_case 테이블/컬럼, 엔티티 PK는 `Long id`.
- **DTO:** `dto/` 패키지에 record 또는 Lombok `@Value` 형태 권장.
- **의존성 추가:** 빌드 시 자동 다운로드 가능한 라이브러리는 `build.gradle` 추가, 프론트는 CDN 또는 webjars 우선.
- **에러 페이지:** `error/{401,403,404,500,503}.html` — 공통 부엉이 일러스트 (`_owl-error.html` fragment) 사용.
- **로그 톤:** 비즈니스 INFO/WARN/ERROR · 디테일은 `log.debug` (예: `client`, `VulnerabilityEnrichmentService`).
