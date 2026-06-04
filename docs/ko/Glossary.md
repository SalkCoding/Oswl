# 용어사전

OsWL에서 사용되는 모든 용어 — 보안 개념부터 플랫폼 특화 어휘까지.

---

## ㄱ

**감사 로그 (Audit Log)**  
사용자 또는 시스템이 수행한 모든 중요한 작업(로그인, 스캔 제출, CVE 상태 변경, 설정 업데이트 등)을 시간 순서로 기록한 불변 기록. 설정 → 관리자 → 감사 로그에서 시스템 관리자가 접근할 수 있습니다.

---

## ㄴ

**내장 역할 템플릿**  
빈 DB 최초 기동 시 **Admin**, **Developer**, **Viewer** 템플릿이 생성됩니다. 이후 편집·추가 가능합니다. [권한 레이어](Authorization-Layers.md) 참고.

---

## ㄷ

**deps.dev**  
OsWL이 각 스캔된 라이브러리의 SPDX 라이선스 식별자, 최신 버전 상태, 지원 중단 공지를 가져오기 위해 조회하는 Google의 [Open Source Insights](https://deps.dev) API입니다.

**데이터 보강 (Enrichment)**  
스캔 수집 후 수행되는 비동기 후처리 단계. OsWL은 NVD, OSV, deps.dev를 조회하여 감지된 모든 라이브러리의 CVE 데이터, CVSS 점수, 수정 버전, 라이선스 이름, 버전 상태를 채웁니다.

---

## ㄹ

**라이선스 (License)**  
오픈소스 라이브러리가 배포되는 법적 조건. OsWL은 SPDX 식별자를 사용하여 라이선스를 감지하고 설정된 정책에 따라 평가합니다.

**라이선스 정책 (License Policy)**  
관리자가 관리하는 테이블로, SPDX 라이선스 식별자를 컴플라이언스 상태(PERMITTED, CAUTION, RESTRICTED)에 매핑합니다. 모든 프로젝트에 전역으로 적용됩니다.

**라이선스 상태 (License Status)**  
정책 평가 후 라이브러리 라이선스에 부여되는 컴플라이언스 상태:

| 상태 | 의미 |
|---|---|
| `PERMITTED` | 정책에 의해 명시적으로 허용 |
| `CAUTION` | 검토 필요 — 잠재적 카피레프트 또는 상업적 제한 |
| `RESTRICTED` | 정책에서 비호환으로 표시 |
| `UNKNOWN` | SPDX 식별자 미조회 또는 미인식 |

**리스크 레벨 (Risk Level)** → *심각도* 참조

**리스크 트렌드 (Risk Trend)**  
프로젝트의 가장 최근 스캔들에 걸쳐 CVE 수와 라이선스 컴플라이언스 상태가 어떻게 변화했는지를 보여주는 시계열 시각화.

**역할 템플릿 (Role Template)**  
인스턴스 전역 **권한** 묶음(Admin / Developer / Viewer 등). *어떤 기능*을 쓸 수 있는지 결정합니다. 프로젝트 멤버십과 다릅니다. [권한 레이어](Authorization-Layers.md).

**프로젝트 멤버십 (Project membership)**  
`project_members`에 사용자–프로젝트 연결. *어떤 프로젝트*를 열 수 있는지 결정합니다. 역할 템플릿과 함께 동작합니다.

**시스템 관리자 (System administrator)**  
초기 설정 시 만드는 최상위 운영 계정. 사용자·역할·감사 로그 관리, 모든 프로젝트 접근. 역할 템플릿 이름 "Admin"과 다릅니다.

---

## ㅁ

**MFA / 2FA (다중 인증 / 이중 인증)**  
표준 이메일 + 비밀번호에 추가하여 이메일로 발송된 일회용 비밀번호(OTP)를 요구하는 추가 로그인 단계. 설정 → 보안에서 전역으로 설정 가능합니다.

---

## ㅂ

**버전 비교 (Version Diff)**  
두 스캔 결과를 비교하여 버전 간에 추가, 제거 또는 변경된 컴포넌트를 보여주는 기능.

---

## ㅅ

**SCA (소프트웨어 구성 분석, Software Composition Analysis)**  
소프트웨어 프로젝트에서 사용되는 OSS 컴포넌트를 식별하고 평가하는 관행 — 특히 보안 취약점과 라이선스 컴플라이언스 측면에서. OsWL은 SCA 플랫폼입니다.

**SPDX (Software Package Data Exchange)**  
라이선스 식별자를 포함한 소프트웨어 청구서(SBOM) 정보를 전달하기 위한 개방형 표준. OsWL은 SPDX 식별자(예: `MIT`, `Apache-2.0`, `GPL-3.0-only`)를 사용하여 라이브러리 라이선스를 표현합니다. 전체 목록은 [spdx.org/licenses](https://spdx.org/licenses/)에서 확인할 수 있습니다.

**SPDX 식별자**  
특정 소프트웨어 라이선스를 식별하는 표준화된 짧은 문자열. 예: `MIT`, `Apache-2.0`, `GPL-3.0-only`.

**스캔 (Scan)**  
프로젝트 버전에 대한 의존성 분석 파이프라인의 단일 실행. 감지된 컴포넌트 목록과 해결된 CVE 및 라이선스 데이터로 구성됩니다. Quick Import 또는 CLI(`POST /api/scan`)로 실행됩니다.

**스캔 상태 (Scan Status)**

| 상태 | 설명 |
|---|---|
| `PENDING` | 수신됨; 처리 대기 중 |
| `SCANNING` | 의존성 매니페스트 파싱 중 |
| `ANALYZING` | CVE 및 라이선스 데이터 보강 중 |
| `COMPLETED` | 완전히 보강됨; 결과 최종 확정 |
| `FAILED` | 복구 불가능한 오류 발생 |

**심각도 (Severity)**  
CVSS 점수를 기반으로 한 CVE 위험 수준 분류. OsWL 사용 기준: CRITICAL, HIGH, MEDIUM, LOW, NONE.

| 점수 범위 | OsWL 심각도 |
|---|---|
| 9.0 – 10.0 | CRITICAL |
| 7.0 – 8.9 | HIGH |
| 4.0 – 6.9 | MEDIUM |
| 0.1 – 3.9 | LOW |
| 0.0 | NONE |

**신뢰 기기 (Trusted Device)**  
OTP 검증 성공 후 신뢰로 표시된 브라우저. 신뢰된 기기는 설정 가능한 신뢰 기간(기본값: 30일) 동안 후속 로그인 시 OTP 단계를 건너뜁니다.

**싱글 세션 강제 (Single-Session Enforcement)**  
OsWL은 사용자당 하나의 활성 세션만 허용합니다. 다른 브라우저/기기에서 새로 로그인하면 이전 세션이 무효화됩니다.

---

## ㅇ

**에코시스템 (Ecosystem)**  
라이브러리가 속한 패키지 관리 시스템. OsWL 지원: `MAVEN`, `NPM`, `PYPI`, `GO`, `CARGO`, `NUGET`, `RUBYGEMS`.

**OsWL (Open-source Software Watchlist)**  
이 문서에서 설명하는 플랫폼 — OSS 의존성의 CVE와 라이선스 리스크를 추적하기 위한 사내 SCA 도구.

**OSV (Open Source Vulnerabilities)**  
오픈소스 패키지에 초점을 맞춘 Google 호스팅 취약점 데이터베이스 및 API([osv.dev](https://osv.dev)). OsWL은 영향받는 버전 범위와 수정 버전을 포함한 어드바이저리 데이터를 OSV에서 조회합니다.

**OTP (일회용 비밀번호, One-Time Password)**  
2FA 인증의 두 번째 요소로서 사용자 이메일 주소로 발송되는 6자리 코드. 로컬 개발 프로파일에서 OTP 코드는 서버 로그에 `*** OTP CODE: NNNNNN ***`으로 표시됩니다.

---

## ㅈ

**전이적 의존성 (Transitive Dependency)**  
프로젝트 매니페스트에 직접 선언되지 않지만 직접 의존성(또는 더 깊은 수준)의 의존성으로 포함되는 라이브러리. *직접 의존성*과 대조됩니다.

**직접 의존성 (Direct Dependency)**  
프로젝트 매니페스트(예: `pom.xml`, `package.json`)에 명시적으로 선언된 라이브러리. *전이적 의존성*과 대조됩니다.

---

## ㅊ

**CVE (공통 취약점 및 노출, Common Vulnerabilities and Exposures)**  
전 세계적으로 고유한 ID(예: `CVE-2021-44228`)로 식별되는 공개 보안 취약점. 각 CVE는 하나 이상의 영향받는 라이브러리 버전과 연결되며 CVSS 점수를 가집니다.

**CVSS (공통 취약점 점수 시스템, Common Vulnerability Scoring System)**  
보안 취약점의 심각도를 평가하는 업계 표준 프레임워크. CVSS 기본 점수는 0.0에서 10.0 사이이며, OsWL에서 심각도 레이블(CRITICAL, HIGH, MEDIUM, LOW)로 매핑됩니다.

---

## ㅋ

**컴포넌트 (Component)**  
스캔에서 감지된 개별 오픈소스 라이브러리. OsWL에서 (이름, 버전, 에코시스템)으로 키잉된 `Library` 엔티티로 표현됩니다. UI에서 *의존성*과 *라이브러리*와 혼용됩니다.

---

## ㅍ

**PAT (개인 액세스 토큰, Personal Access Token)**  
VCS 제공업체(GitHub, GitLab, Bitbucket)가 사용자 계정을 대신하여 API 접근을 허용하기 위해 생성하는 비밀 토큰. OsWL이 Quick Import 및 저장소 탐색에 사용합니다.

**패치 가능성 (Patchability)**  
라이브러리에 영향을 미치는 취약점에 대한 수정 가능 여부를 나타내는 파생 속성:

| 값 | 의미 |
|---|---|
| `PATCHABLE` | 알려진 `fixVersion`이 있는 CVE가 하나 이상 존재 |
| `NON_PATCHABLE` | CVE는 존재하지만 알려진 수정 없음 |
| `UNKNOWN` | CVE 없음, 또는 보강 미완료 |

**프로젝트 (Project)**  
OsWL의 최상위 엔티티로 분석 중인 애플리케이션 또는 서비스를 나타냅니다. 하나의 프로젝트는 보통 하나의 저장소(또는 CLI 전용 프로젝트의 경우 하나의 논리적 단위)에 매핑됩니다.

**프로젝트 버전 (Project Version)**  
특정 VCS 브랜치에 연결된 프로젝트 스냅샷. Quick Import에 의해 자동으로 생성됩니다.

---

## ㅎ

**해결 버전 (Fix Version)**  
관련 CVE가 패치된 라이브러리의 가장 이른 버전. OSV 및 NVD 어드바이저리 데이터에서 가져옵니다. 보안 센터에서 권장 업그레이드 대상으로 표시됩니다.

---

## NVD

**NVD (국가 취약점 데이터베이스, National Vulnerability Database)**  
NIST가 관리하는 미국 정부의 CVE 데이터 저장소. OsWL은 NVD REST API를 통해 CVSS 점수와 설명을 가져옵니다. 설정 → 외부 API에서 API 키(설정 가능)를 등록하면 속도 제한이 30초당 5→50 요청으로 증가합니다.

---

## Quick Import

**Quick Import**  
저장소를 선택하고 브랜치를 고르는 것만으로 VCS 제공업체(GitHub / GitLab / Bitbucket)에서 직접 프로젝트를 임포트하는 브라우저 기반 워크플로우. OsWL이 저장소를 클론하고, 의존성을 해결하며, 스캔을 실행합니다 — CLI가 필요 없습니다.

---

## VCS

**VCS (버전 관리 시스템, Version Control System)**  
GitHub, GitLab, Bitbucket과 같은 소스 코드 호스팅 플랫폼. OsWL은 Quick Import를 위해 개인 액세스 토큰을 통해 VCS 제공업체에 연결합니다.

**VCS 연결 (VCS Connection)**  
OsWL이 VCS API에 인증하는 데 사용하는 암호화 저장된 PAT + 제공업체 구성. 설정 → VCS에서 관리합니다.

---

## 제로데이 (Zero-Day)

공개적으로 알려져 있지만 공식 패치가 아직 없는 취약점(수정 버전이 null인 경우). OsWL은 수정 버전이 게시될 때까지 이러한 CVE를 `NON_PATCHABLE`로 표시합니다.
