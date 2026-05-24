<div align="center">

# 🦉 OsWL

**오픈소스 소프트웨어 감시 목록 — SCA 플랫폼**

모든 소프트웨어 컴포넌트의 CVE 취약점과 라이선스 리스크를 한눈에 추적하세요.

[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.0.5-6DB33F?logo=springboot&logoColor=white)](https://spring.io/projects/spring-boot)
[![Java](https://img.shields.io/badge/Java-25-ED8B00?logo=openjdk&logoColor=white)](https://openjdk.org/)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-supported-336791?logo=postgresql&logoColor=white)](https://www.postgresql.org/)

[English](README.md) | **한국어**

</div>

---

## OsWL이란?

**OsWL** (Open-source Software Watchlist)은 OSS 의존성의 보안 취약점(CVE)과 라이선스 리스크를 추적·관리하는 사내 **SCA(Software Composition Analysis)** 플랫폼입니다.

전체 소프트웨어 포트폴리오를 단일 대시보드에서 관리할 수 있습니다. Git 저장소를 연결해 자동으로 임포트하거나 CLI를 통해 스캔 결과를 제출하면, CVSS 기반으로 정렬된 취약점 목록, 라이선스 컴플라이언스 현황, 시간 흐름에 따른 리스크 트렌드, AI 생성 인사이트를 즉시 확인할 수 있습니다.

### 주요 기능

| 기능 | 설명 |
|---|---|
| **보안 센터** | CVSS 점수, 심각도 순위, 상태 관리(Open / Suppressed / False Positive)를 포함한 전체 CVE 목록 |
| **라이선스 분석** | 의존성별 SPDX 라이선스 감지 및 정책 적용 (Permitted / Caution / Restricted) |
| **리스크 트렌드** | 최대 10개 스캔에 걸친 CVE 수 및 라이선스 현황 변화 히스토리 차트 |
| **버전 비교** | 두 스캔 결과를 나란히 비교 — 추가·제거·변경된 의존성 확인 |
| **Quick Import** | VCS 연결을 통해 GitHub / GitLab / Bitbucket에서 원클릭 임포트 |
| **CLI 연동** | 프로젝트 범위 API 키를 사용한 언어 무관 스캔 제출 REST API |
| **AI 인사이트** | CVE 현황 및 라이선스 컴플라이언스에 대한 선택적 LLM 생성 요약 |
| **역할 기반 접근 제어** | 관리자가 관리하는 역할 템플릿을 통한 세밀한 권한 시스템 |
| **감사 로그** | 모든 사용자·시스템 이벤트에 대한 불변 감사 로그 (CSV 내보내기 지원) |
| **2FA / 신뢰 기기** | 브라우저별 신뢰 기기 지원을 포함한 이메일 OTP 이중 인증 |

---

## 빠른 시작

### 사전 요구사항

| 도구 | 버전 |
|---|---|
| JDK | 25 이상 |
| Gradle Wrapper | 포함 (`./gradlew`) |
| PostgreSQL | 15 이상 (운영 환경) |
| (선택) Docker | PostgreSQL 로컬 실행용 |

### 1. 클론

```bash
git clone https://github.com/SalkCoding/Oswl.git
cd Oswl
```

### 2. 로컬 실행 (H2 파일 모드)

```bash
./gradlew bootRun
# 애플리케이션이 http://localhost:8080 에서 시작됩니다
```

기본적으로 `local` 프로파일이 활성화됩니다. 임베디드 H2 데이터베이스(`./oswl-db.mv.db`)를 사용하므로 외부 데이터베이스가 필요 없습니다.

최초 실행 시 **설정 마법사**가 `http://localhost:8080/setup`에서 자동으로 열립니다.  
첫 번째 시스템 관리자 계정을 생성하여 완료하세요.

### 3. PostgreSQL로 실행 (운영 프로파일)

```bash
export SPRING_PROFILES_ACTIVE=prod
export DB_URL=jdbc:postgresql://localhost:5432/oswl
export DB_USERNAME=oswl
export DB_PASSWORD=your_password
export OSWL_ENCRYPTION_KEY=$(openssl rand -base64 32)

./gradlew bootRun
```

---

## 빌드

```bash
# 전체 빌드 (Java + Tailwind CSS 컴파일)
./gradlew build

# Tailwind CSS만 재빌드
./gradlew buildTailwindCss

# 테스트 실행
./gradlew test

# 테스트 커버리지 리포트 → build/reports/jacoco/test/html/index.html
./gradlew jacocoTestReport
```

> **참고:** 첫 번째 빌드 시 Tailwind CSS 독립 실행형 CLI 바이너리(~7MB)가 `build/tools/`에 다운로드됩니다. 이후 빌드에서는 캐시된 바이너리를 사용합니다.

---

## 설정 레퍼런스

모든 설정은 환경 변수 또는 `application.yaml` 프로파일을 통해 제어됩니다.

| 변수 | 기본값 | 설명 |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | `local` | 활성 프로파일: `local` 또는 `prod` |
| `OSWL_ENCRYPTION_KEY` | *(로컬에서는 더미 키)* | VCS 토큰 암호화에 사용되는 Base64 인코딩 32바이트 AES 키. **운영 환경에서 필수.** `openssl rand -base64 32`로 생성 |
| `DB_URL` | `jdbc:postgresql://localhost:5432/oswl` | PostgreSQL JDBC URL (prod 프로파일) |
| `DB_USERNAME` | `oswl` | 데이터베이스 사용자 (prod 프로파일) |
| `DB_PASSWORD` | `oswl` | 데이터베이스 비밀번호 (prod 프로파일) |
| `OSWL_CLONE_TEMP_DIR` | 시스템 임시 디렉토리 | Quick Import 중 임시 git 클론 디렉토리 |
| `OSWL_GITHUB_API_BASE` | `https://api.github.com` | GitHub API 기본 URL (GHES 대체용) |
| `OSWL_RISK_TREND_LIMIT` | `10` | 리스크 트렌드 차트에 표시되는 최대 스캔 수 |
| `OSWL_AUDIT_MAX_PAGE_SIZE` | `200` | 감사 로그 API 페이지당 최대 레코드 수 |
| `OSWL_AUDIT_RETENTION_MONTHS` | `6` | 감사 로그 레코드 자동 삭제 기간(월) |

---

## 로컬 개발 부가 기능

### H2 콘솔

```
URL:  http://localhost:8080/h2-console
JDBC: jdbc:h2:file:./oswl-db
사용자: sa
비밀번호: (없음)
```

### OTP 이메일 (로컬 프로파일)

`local` 프로파일은 임베디드 **GreenMail** SMTP 서버를 시작합니다. 실제 이메일은 발송되지 않습니다.  
OTP 코드는 서버 로그에 표시됩니다:

```
*** OTP CODE: 123456 ***
```

### 테스트 데이터 시드

로그인 후 다음을 호출하세요:

```
GET http://localhost:8080/data/test
```

이 엔드포인트는 **모든** 기존 데이터를 초기화하고 다양한 샘플 프로젝트, 스캔, CVE, 라이선스 데이터로 DB를 재구성합니다.

---

## 아키텍처 개요

```
브라우저 / CLI
     │
     ▼
Spring MVC 컨트롤러  (얇은 레이어 — Service에 위임)
     │
     ▼
서비스 레이어        (비즈니스 로직, 트랜잭션)
     │
   ┌─┴──────────────────┐
   ▼                    ▼
JPA 리포지토리      외부 클라이언트
(PostgreSQL / H2)   (NVD · OSV · deps.dev · VCS API)
```

**핵심 도메인 모델:**

```
Project
 └── ProjectVersion (브랜치별)
 └── ScanResult     (CLI / Quick Import 스캔별)
      └── ScanComponent
           └── DependencyPath

Library  (프로젝트 간 공유 — group:artifact@version)
 └── Cve
 └── LicensePolicyEntry
```

---

## API 문서

인터랙티브 Swagger UI:

```
http://localhost:8080/swagger-ui.html
```

OpenAPI 스펙 (JSON): `http://localhost:8080/v3/api-docs`

---

## 문서

전체 문서는 [`docs/ko/`](docs/ko/) 폴더에서 확인할 수 있습니다:

| 페이지 | 설명 |
|---|---|
| [홈](docs/ko/Home.md) | 플랫폼 개요 및 탐색 가이드 |
| [시작하기](docs/ko/Getting-Started.md) | 설치, 설정 마법사, 첫 번째 프로젝트 |
| [사용자 가이드](docs/ko/User-Guide.md) | 대시보드 일상적 사용법 |
| [Quick Import](docs/ko/Quick-Import.md) | GitHub / GitLab / Bitbucket에서 프로젝트 임포트 |
| [CLI 연동](docs/ko/CLI-Integration.md) | 빌드 파이프라인에서 스캔 제출 |
| [보안 센터](docs/ko/Security-Center.md) | 취약점(CVE) 관리 |
| [라이선스 분석](docs/ko/License-Analysis.md) | 라이선스 컴플라이언스 및 정책 관리 |
| [리스크 트렌드](docs/ko/Risk-Trend.md) | 히스토리 리스크 차트 해석 |
| [버전 비교](docs/ko/Version-Diff.md) | 두 스캔 결과 비교 |
| [스캔 히스토리](docs/ko/Scan-History.md) | 스캔 기록 관리 |
| [관리](docs/ko/Administration.md) | 사용자, 역할, 감사 로그, 보안 설정 |
| [API 레퍼런스](docs/ko/API-Reference.md) | REST API 엔드포인트 요약 |
| [용어사전](docs/ko/Glossary.md) | 용어 및 정의 |

---

## 라이선스

이 프로젝트는 [MIT 라이선스](LICENSE) 하에 배포됩니다.
