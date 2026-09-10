# 용어사전

OsWL에서 사용되는 모든 용어 — 보안 개념부터 플랫폼 특화 어휘까지.

---

## ㄱ

**감사 로그 (Audit Log)**  
사용자 또는 시스템이 수행한 모든 중요한 작업(로그인, 스캔 제출, CVE 상태 변경, 설정 업데이트 등)을 시간 순서로 기록한 불변 기록. 설정 → 관리자 → 감사 로그에서 시스템 관리자가 접근할 수 있습니다.

**GO**  
Go 프로그래밍 언어의 모듈 에코시스템. `github.com/gin-gonic/gin`과 같은 임포트 경로로 식별되는 패키지.

**권한 (Permission)**  
역할 템플릿을 통해 사용자에게 부여되는 세분화된 접근 제어 기능. 예: `PROJECT_VIEW`, `SCAN_SUBMIT`, `SECURITY_CENTER_UPDATE_STATUS`.

---

## ㄴ

**내장 역할 템플릿**  
빈 DB 최초 기동 시 **Admin**, **Developer**, **Viewer** 템플릿이 생성됩니다. 이후 편집·추가 가능합니다. [권한 레이어](Authorization-Layers.md) 참고.

**NUGET**  
.NET 패키지 에코시스템. [nuget.org](https://www.nuget.org)에 배포된 패키지.

---

## ㄷ

**deps.dev**  
OsWL이 각 스캔된 라이브러리의 SPDX 라이선스 식별자, 최신 버전 상태, 지원 중단 공지를 가져오기 위해 조회하는 Google의 [Open Source Insights](https://deps.dev) API입니다.

**데이터 보강 (Enrichment)**  
스캔 수집 후 수행되는 비동기 후처리 단계. OsWL은 OSV와 deps.dev를 조회하여 감지된 모든 라이브러리의 CVE 데이터, CVSS 점수, CWE ID(OSV), 수정 버전, 라이선스 이름, 버전 상태를 채웁니다. 재조회 정책은 **설정 → 캐시**(`cache_settings` 테이블)로 제어합니다.

---

## ㄹ

**라이브러리 (Library)**  
(이름, 버전, 에코시스템)으로 키잉된, OsWL의 오픈소스 패키지 정규 레코드. 라이브러리 레코드는 모든 프로젝트에서 공유됩니다 — 두 프로젝트가 동일한 버전의 `spring-core`를 사용하면 해당 CVE와 라이선스 데이터는 한 번만 저장됩니다.

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

**RUBYGEMS**  
`gem` 도구로 관리되는 Ruby 패키지 에코시스템. [rubygems.org](https://rubygems.org)에 배포된 패키지.

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

**MAVEN**  
Apache Maven이 관리하는 Java/JVM 에코시스템. `groupId:artifactId`로 식별되는 패키지.

**MFA / 2FA (다중 인증 / 이중 인증)**  
표준 이메일 + 비밀번호에 추가하여 이메일로 발송된 일회용 비밀번호(OTP)를 요구하는 추가 로그인 단계. 설정 → 보안에서 전역으로 설정 가능합니다.

**악성 패키지 (Malicious Package)**  
OSV가 `MAL-` 접두사 어드바이저리 ID로 표시한 패키지 버전 — 정상적인 코드에 존재하는 일반적인 취약점이 아니라, 처음부터 사용자를 공격할 목적으로 배포된 패키지를 의미합니다. OsWL은 이를 자동으로 `CRITICAL` 심각도로 승격하고, 컴포넌트 상세와 보안 센터 목록에 빨간색 **악성(Malicious)** 배지를 표시합니다.

---

## ㅂ

**배포 프로필 (Deployment Profile)**  
스캔 대상 소프트웨어가 실제로 어떻게 배포되는지를 나타내는 값 — `SAAS`, `INTERNAL_TOOL`, `ON_PREMISE_DISTRIBUTION`, 또는 기본값인 `COMMERCIAL_PRODUCT`. AI 트리아지와 라이선스 위험 판단의 비중을 조정합니다: 동일한 CVE나 카피레프트 라이선스라도 사내 전용 도구와 고객에게 배포되는 소프트웨어 사이의 노출도는 크게 다릅니다. 프로젝트별로 설정하며(`PATCH /api/projects/{id}/deployment-profile`), 지정하지 않으면 AI 설정의 **기본 배포 프로필**을 사용합니다.

**버전 비교 (Version Diff)**  
두 스캔 결과를 비교하여 버전 간에 추가, 제거 또는 변경된 컴포넌트를 보여주는 기능.

**보안 센터 (Security Center)**  
프로젝트의 주요 취약점 관리 페이지. 스캔된 컴포넌트에 영향을 미치는 모든 CVE를 필터링, 정렬, 상태 관리, AI 인사이트와 함께 표시합니다.

---

## ㅅ

**SBOM (소프트웨어 자재 명세서, Software Bill of Materials)**  
빌드에 포함된 모든 컴포넌트의 기계 판독 가능한 목록. OsWL은 SPDX(라이선스 중심)와 CycloneDX 1.6(v1.0.4) SBOM을 생성하며, 다른 도구가 출력한 CycloneDX 파일을 가져올 수 있습니다.

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

**AI 인사이트 (AI Insight)**  
스캔 보강 중 생성되는 LLM 기반 서술형 요약. OsWL은 스캔마다 *보안 상태 인사이트*, *보안 리스크 트렌드 인사이트*, *라이선스 리스크 트렌드 인사이트* 세 가지를 생성합니다. 각각 한 단락 분량의 자연어 평가입니다. 설정에서 AI 공급자가 구성되어 있어야 합니다.

**에코시스템 (Ecosystem)**  
라이브러리가 속한 패키지 관리 시스템. OsWL 지원: `MAVEN`, `NPM`, `PYPI`, `GO`, `CARGO`, `NUGET`, `RUBYGEMS`, 그리고 v1.0.4부터 `COMPOSER`(PHP, `composer.lock`)와 `CONAN`(C/C++, `conan.lock`).

**NPM**  
`npm` 도구로 관리되는 Node.js/JavaScript 패키지 에코시스템. [npmjs.com](https://www.npmjs.com)에 배포된 패키지.

**오탐 (False Positive)**  
해당 취약점이 이 프로젝트에는 해당하지 않는다고 확인된 CVE 트리아지 상태(예: 취약한 코드 경로가 실행되지 않거나, 영향을 받는 기능을 사용하지 않는 경우).

**OsWL (Open-source Software Watchlist)**  
이 문서에서 설명하는 플랫폼 — OSS 의존성의 CVE와 라이선스 리스크를 추적하기 위한 사내 SCA 도구.

**OSV (Open Source Vulnerabilities)**  
오픈소스 패키지에 초점을 맞춘 Google 호스팅 취약점 데이터베이스 및 API([osv.dev](https://osv.dev)). OsWL은 영향받는 버전 범위와 수정 버전을 포함한 어드바이저리 데이터를 OSV에서 조회합니다.

**OTP (일회용 비밀번호, One-Time Password)**  
2FA 인증의 두 번째 요소로서 사용자 이메일 주소로 발송되는 6자리 코드. 로컬 개발 프로필에서 OTP 코드는 서버 로그에 `*** OTP CODE: NNNNNN ***`으로 표시됩니다.

**의존성 경로 (Dependency Path)**  
루트 프로젝트에서 특정 라이브러리까지 이어지는 패키지 체인. 하나의 라이브러리는 여러 경로(직접 및/또는 전이적)로 도달할 수 있습니다. OsWL은 컴포넌트별로 해결된 모든 경로를 기록하고 표시합니다.

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

**CWE (Common Weakness Enumeration, 공통 약점 분류)**  
취약점 *유형*의 분류 식별자(예: `CWE-79` XSS). OsWL은 OSV `database_specific.cwe_ids`의 첫 CWE를 `library_cves`에 저장하고 컴포넌트 상세에 표시합니다.

**취약점 (Vulnerability)** → *CVE* 참조

---

## ㅋ

**CARGO**  
`cargo` 도구로 관리되는 Rust 패키지 에코시스템. [crates.io](https://crates.io)에 배포된 패키지.

**CLI (커맨드라인 인터페이스, Command-Line Interface)**  
OsWL의 언어 독립적인 REST 기반 스캔 제출 방식. API 키만 있으면 어떤 빌드 도구나 CI 파이프라인에서도 스캔 페이로드를 POST할 수 있습니다. [CLI 연동](CLI-Integration.md) 참고.

**컴포넌트 (Component)**  
스캔에서 감지된 개별 오픈소스 라이브러리. OsWL에서 (이름, 버전, 에코시스템)으로 키잉된 `Library` 엔티티로 표현됩니다. UI에서 *의존성*과 *라이브러리*와 혼용됩니다.

---

## ㅍ

**PYPI**  
Python 패키지 인덱스. [pypi.org](https://pypi.org)에 배포된 패키지.

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
관련 CVE가 패치된 라이브러리의 가장 이른 버전. OSV 어드바이저리 데이터에서 가져옵니다. 보안 센터에서 권장 업그레이드 대상으로 표시됩니다.

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

---

## v1.0.4 신규 용어

**CycloneDX**  
OWASP의 SBOM 표준. OsWL은 CycloneDX 1.6 SBOM·VEX 문서를 내보내고, 외부 CycloneDX 파일을 가져올 수 있습니다.

**VEX (Vulnerability Exploitability eXchange)**  
포함된 취약점에 실제로 영향을 받는지를 기계 판독 가능하게 진술하는 문서. OsWL은 트리아지 판단을 그대로 CycloneDX VEX로 내보냅니다 — 무시는 `not_affected`, 유예는 `in_triage`, 조치 완료는 `resolved`.

**SARIF (Static Analysis Results Interchange Format)**  
도구 탐지 결과에 대한 OASIS 표준(2.1.0). OsWL의 SARIF 내보내기는 `github/codeql-action/upload-sarif`와 스키마 호환이므로 결과가 GitHub Security 탭에 표시됩니다.

**KEV (Known Exploited Vulnerabilities)**  
실제 공격에 사용된 것이 확인된 취약점에 대한 CISA 목록. "실제로 악용되고 있다"는 사실이 높은 CVSS 점수보다 강한 트리아지 신호이므로 OsWL은 KEV 등재 CVE를 최우선 표시합니다. `OSWL_GATE_FAIL_ON_KEV`로 CI 게이트 실패 조건에 넣을 수 있습니다.

**EPSS (Exploit Prediction Scoring System)**  
향후 30일 내 악용 확률을 0~1로 추정하는 FIRST.org 점수. KEV에 없는 항목의 우선순위를 정하는 데 사용하며, `OSWL_GATE_FAIL_ON_EPSS`로 게이트 임계값을 지정합니다.

**purl (package URL)**  
패키지를 가리키는 표준 좌표 문자열(예: `pkg:maven/org.springframework/spring-core@6.1.0`). SBOM·VEX·SARIF 내보내기에 사용되며, SBOM 가져오기 시에도 이 값을 읽습니다.

**Scorecard (OpenSSF)**  
유지보수·리뷰 관행·빌드 보안을 평가한 deps.dev의 0~10 프로젝트 건강도 점수. 컴포넌트 상세에 표시되어, 아직 취약점은 없지만 관리가 중단된 의존성을 조기에 확인할 수 있습니다.

**Typosquatting**  
유명 패키지와 한두 글자 차이나는 이름으로 패키지를 배포하는 공급망 공격(`express` → `expres`). OsWL은 유명 패키지 목록과의 Levenshtein 거리로 후보를 탐지해 컴포넌트 상세에 배지로 표시합니다.

**보안 게이트 (Security Gate)**  
`POST /api/scan/gate`. 심각도·라이선스·KEV·EPSS 임계값으로 스캔을 판정해 CI 잡의 통과/실패를 결정합니다. 기본적으로 직전 완료 스캔을 베이스라인으로 삼아 신규 항목만 평가합니다.

**폐쇄망 모드 (Air-gapped Mode)**  
외부 인터넷이 없는 망에서 동작하는 모드. 기동 전 `OSWL_AIRGAPPED_ENABLED=true`로 설정한다. 취약점·위협 인텔 조회(OSV, deps.dev, FIRST.org EPSS, CISA KEV)가 반입된 오프라인 스냅샷 저장소에서 제공되며 외부로 HTTP를 시도하지 않는다. 임베디드 AI 자동 다운로드도 건너뛴다. 스냅샷에 없는 컴포넌트는 "취약점 없음"이 아니라 **데이터 없음**으로 처리된다. `POST /api/admin/snapshot/import`로 번들을 반입한다(시스템 관리자). [관리 — 오프라인 스냅샷 번들](Administration.md) 참고.

**스냅샷 번들 (Snapshot Bundle)**  
`osv.jsonl`, `depsdev.jsonl`, `epss.jsonl`, `kev.jsonl`, 필요시 `unresolved.jsonl`과 이들의 메타데이터를 담은 `meta.json`로 구성된 zip 파일. `oswl-vdb`가 생성하는 v2 형식이며 `POST /api/admin/snapshot/import`로 반입한다. `replace`(소스별 삭제 후 삽입)와 `merge`(키 기준 upsert, `"_deleted":true` 삭제 마커 처리) 모드를 지원한다.

**oswl-vdb**  
오프라인 취약점 DB 번들 빌더 CLI(`scripts/oswl-vdb/oswl-vdb.{sh,ps1}` 또는 `./gradlew vdbBuild`). 인터넷이 연결된 머신에서 OSV·deps.dev·FIRST.org EPSS·CISA KEV 등 라이브 업스트림으로부터 스냅샷 번들을 생성한다. `--wanted`로 wanted-list 범위를 제한하고, `--mode delta --since`로 증분 델타 번들을 만들며, `--offline-sources`로 캐시된 소스 디렉터리만으로 네트워크 없이 빌드할 수 있다. `verify`·`inspect` 서브커맨드로 번들을 검증·열람한다.

**wanted-list**  
해당 OsWL 인스턴스가 지금까지 스캔한 모든 고유한 `(ecosystem, name, version)`을 JSONL로 내보낸 목록. `GET /api/admin/snapshot/wanted-list`로 다운로드하며(시스템 관리자), 생태계·이름·버전만 포함하고 프로젝트명·저장소 URL·파일 경로는 포함하지 않는다. 인터넷 연결 머신의 `oswl-vdb build --wanted`에 넘겨 실제 의존성에 필요한 정의만 가져오게 한다.

**unresolved**  
`oswl-vdb` 빌더가 업스트림에서 확실히 해소하지 못한 wanted-list 컴포넌트. 예를 들어 OSV 영향 범위를 평가할 수 없거나 deps.dev 조회가 실패한 경우다. 스냅샷 번들의 `unresolved.jsonl`에 기록되며, 설정 → 관리자 → 오프라인 스냅샷에서 별도의 `unresolved` 소스로 표시된다. "취약점 없음"이 아니라 **데이터 없음**으로 취급해야 한다.

**정의 기준일 (Definition As-of)**  
반입된 오프라인 스냅샷 소스의 업스트림 데이터 기준일. 번들 `meta.json`의 `sources.<source>.asOf`에서 `airgapped_snapshot_meta.source_as_of`에 저장된다. 스캔 히스토리와 보안 센터 상단 배너에는 모든 소스 중 가장 오래된 `sourceAsOf`이 표시되어, 당시 취약점 정의가 어느 시점의 것인지 감사자가 확인할 수 있다. 설정 → 관리자 → 오프라인 스냅샷 페이지에는 `OSWL_AIRGAPPED_STALENESS_WARN_DAYS`(기본 7일)와 `OSWL_AIRGAPPED_STALENESS_CRITICAL_DAYS`(기본 30일) 임계값에 따른 최신성 배지가 표시된다.
