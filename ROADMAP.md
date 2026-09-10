# OsWL 1.0.6 실행 로드맵

기준일: **2026-09-07**. 기존 미완료 목록과 엔터프라이즈·망분리·생태계별 정확도 조사를 하나의 실행 목록으로 통합했다. 모든 작업은 문서에 나오는 순서대로 **1~63번**을 부여하며, 선행 작업과 본문의 참조도 같은 번호를 사용한다. 기존 완료 기능은 재구현 대상으로 되돌리지 않는다.

**현재 지원 생태계 전체의 데이터 이용·재배포 권한과 일반/망분리 탐지 정확도가 검증 완료된 상태는 아니다.** 이 문서는 수정 계획이며 구현·테스트 수행이나 1.0.6 출시 선언이 아니다. 조사 당시 HEAD는 `d5c386d`; 초기 보안 조사에는 `5b0534a` 및 당시 작업 트리 근거도 포함된다. 착수 시 해당 경로를 재확인하고, 이미 수정됐다면 재구현 대신 남은 검증만 수행한다.

## 실행 규칙과 출시 기준

- **2026-09-11 누적 회귀 검증:** `e888efb`까지의 OSV batch 대응/불완전 입력과 Maven 미해석 selector 변경을 포함해 Windows/Java 25에서 `.\gradlew.bat build verifyProdJar` 성공. 전체 2,916건 중 2,907건 통과·기존 환경 의존 skip 9건·실패/오류 0. 운영 JAR local controller 제외 검사 통과. 로그 `build/roadmap-query-validation-build.log`. `uiTest`, 실제 PostgreSQL, 전체 공급자 실환경 및 API/UI의 불확실성 표시 종단 검증을 완료했다는 의미는 아니다. 전체 로드맵은 계속 진행 중이다.

- **P0:** 1.0.6의 기본 신뢰성·보안·데이터 배포 경계를 위해 우선 해결할 작업. CVSS 등급이나 실제 침해의 심각도 표시가 아니다.
- **P1:** 지원한다고 선언한 생태계·배포·기능에서는 출시 전에 완료해야 하는 정확도/운영 작업. 미완료 상태를 정상 지원으로 광고하지 않는다.
- **P2:** 성능·사용성·운영 검증. 실제 결함이 필수 계약을 깨면 출시 차단으로 올린다.
- **P3 / 조건부:** 수요·권한·실환경·측정 근거가 필요한 확장. 기존 기능의 미완료 검증과 새 기능 도입을 구분한다.
- **[코드 확인]**은 정적 근거이며 실제 오탐률/공격 재현을 뜻하지 않는다. **[설계]**, **[검증 잔여]**, **[권한 확인]**은 아직 완료되지 않은 작업이다.
- 항목을 추가·삭제·이동하면 전체 번호를 문서 순서대로 다시 매기고 선행 작업·본문·부록의 참조도 함께 갱신한다.
- 각 항목의 **현재·대상 → 수정 → 선행 → DoD(완료 기준)**를 따라 한 작업 단위씩 진행한다. 선행 항목 중 계약 합의만 필요한지 실제 구현/검증까지 필요한지 해당 DoD로 판단한다.
- 구현·배포·커밋/푸시는 실제 사용자 요청 범위를 따른다. 이 문서만으로 승인받은 것으로 취급하지 않는다. 테스트 파일 생성·수정·삭제는 명시적 사용자 지시가 필요하며 기존 테스트 실행은 허용된다.
- 구현 완료, 로컬 검증, 실환경 검증, 권한 확인을 구분한다. 완료 근거에는 commit·실행 명령/환경·결과·미검증 범위를 남기고, 모두 충족된 항목만 목록에서 제거한다. 완료 이력은 Git과 기존 검증 기록에서 확인한다.
- 조사 본문과 진행 상태는 이 최상위 파일에서 관리한다. README·Home·언어별 docs에 로드맵을 복제하지 않는다. 원문 LICENSE/NOTICE는 실제 배포 자산으로 별도 관리한다.
- **2026-09-10 실행 승인:** 사용자가 필요한 회귀 테스트 생성·수정과 항목별 로컬 커밋을 명시적으로 허용했다. push는 금지한다. 진행 상태도 해당 구현 커밋에 포함하도록 이 파일을 Git 추적 대상으로 전환한다.

**실행 흐름:** 1단계 권한·지원 계약 → 2단계 수집·인증 경계 → 3단계 공통 판정 → 4단계 생태계별 보완 → 5단계 로컬 데이터·망분리 → 6단계 정확도·운영 검증 → 7단계 조건부 확장.

처음 착수할 묶음은 **1·3번의 데이터/지원 범위 확정**, **4~9번의 수집·인증 경계**, **10~14번의 잘못된 취약 판정 방지**다. 권한 확인이 필요한 소스가 있어도 독립적인 로컬 판정 수정은 진행할 수 있다. 해당 소스의 외부 재배포는 확인 전 허용하지 않는 배포 정책을 구현한다. 이 정책은 아직 제안이다.

최소 출시 기준은 P0 완료와 선택한 운영 프로파일의 42~45번 통과다. **현재 지원 생태계 전부를 기업용으로 지원하려면 20~31번 전체와 기존 49번의 실데이터 검증까지 충족해야 한다.** 일부를 먼저 검증한다면 나머지는 제한/미검증 상태와 게이트 처리를 명시하고, 기능 지원을 조용히 삭제하거나 “모두 정확”으로 표시하지 않는다. HA는 50번까지 완료한 프로파일에서만 보장한다.

## 1단계 — 데이터 권한과 지원 계약 확정

### 1. 원천별 데이터 권리·재배포 승인 목록 — P0 · [권한 확인]

- 현재·대상: OSV 원천, GHSA, NVD/CVE, KEV, EPSS, deps.dev, Specs, 내장 Conda 매핑. 일부 고지는 보완됐지만 모든 이용권이 확인된 것은 아니다. 아래 부록 A의 조사 근거를 사용한다.
- [ ] 수정: 출처별 사내 이용/고객 DB 전달/공개 표시/변형/재배포 조건을 나누고 검증 URL·확인일·권리자·담당자를 기록한다. EPSS·Debian/Alpine·NVD의 미확인 범위, Conda 원본 revision을 해결한다. 확인 불가 시 대체·제외와 커버리지 공백을 명시한다.
- 선행: 없음. 권한 문의·데이터 도입은 해당 작업의 실제 사용자 승인 범위를 따른다.
- DoD: 각 배포 프로파일의 포함 데이터가 이용 근거와 대응한다. 미확인을 허용 또는 불법으로 단정하지 않는다. 고지 작성과 사용 권한 확보를 별도로 판정한다.

### 2. 데이터 provenance와 고지의 배포·내보내기 전파 — P0 · [설계]

- 현재·대상: [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md), [배포 고지](src/main/resources/META-INF/THIRD_PARTY_LICENSES.txt), [내장 매핑 원문](src/main/resources/META-INF/licenses/conda-forge-bot-data-LICENSE.txt)과 3개 언어 화면은 일부 보완됐다. VDB/보고서 전체 전파는 미구현이다.
- [ ] 수정: record에 원출처·revision·URL·SPDX/LicenseRef·공급된 attribution·변경 이력을 연결한다. JAR/CLI/컨테이너/데이터 팩/보고서마다 포함 데이터의 LICENSE/NOTICE를 추출하고 망분리 배포에는 원문을 동봉한다. Ubuntu ShareAlike와 RustSec GHSA 예외를 유지한다.
- 선행: 1번의 권리 분류. 번들 연계는 34~37번에서 완료.
- DoD: 고지 누락/미승인 출처를 배포 검사에서 탐지한다. 단순 CVE ID나 OSV 표기만으로 완료 처리하지 않는다. 새 라이브러리·데이터·룰의 고지와 권리 검토가 실제 배포 내용과 일치한다.

### 3. 지원 생태계·입력·배포 프로파일 명세 — P0 · [설계]

- 현재·대상: parser 존재, 원격 API 조회 가능, 망분리 전체 DB 지원이 혼용될 수 있다. 원래 요구는 일반 환경과 내부망의 신뢰 가능한 탐지다.
- [ ] 수정: 생태계별 선언/lock/해석 결과/아티팩트 지원 수준, registry·OS release·architecture, 데이터 권한/갱신/판정 엔진 상태를 작성한다. 인터넷·승인 proxy·완전 망분리, 단일 조직/단일 인스턴스/HA를 구분한다.
- 선행: 1번의 미확인 범위 반영. 최종 지원 확정은 42~45번 결과 반영.
- DoD: 지원·제한·미검증·미지원 범위와 UNKNOWN 처리 규칙이 명시된다. '0건=안전', '온프레미스=망분리', 'API 캐시=전체 로컬 DB'라는 설명이 없다.

## 2단계 — Auto Import·CLI 수집과 인증 경계

### 4. VCS 토큰을 승인된 연결 대상에 고정 — P0 · [코드 확인/재현 필요]

- 현재·대상: [QuickImportService](src/main/java/com/salkcoding/oswl/service/ingest/QuickImportService.java)의 공급자 추정과 사용자/공급자 기준 자격 증명 선택 경로를 재확인한다. 실제 토큰 유출은 입증하지 않았다.
- [ ] 수정: connectionId와 canonical scheme/host/port에 토큰을 결합한다. 유사 호스트·다른 포트·redirect·DNS 재해석, submodule/LFS에도 같은 허용 정책을 적용한다. 내부 Git은 승인된 origin/대역만 허용한다.
- 선행: 3번의 연결 프로파일.
- DoD: 승인 공용/내부 Git은 정상 수집되고 승인 밖 목적지에 자격 증명이 전달되지 않는다. 원문 토큰을 검증 로그에 남기지 않는다. [SSRF 설계 참고](https://cheatsheetseries.owasp.org/cheatsheets/Server_Side_Request_Forgery_Prevention_Cheat_Sheet.html).
- **2026-09-10 부분 구현:** 복제 실행 시 활성 연결의 canonical HTTPS host/port와 연결 ID를 함께 사용한다. 공급자 이름만으로 토큰을 재조회하지 않으며, 유사 호스트·userinfo·query/fragment·경로 인코딩/상대 경로·모호한 복수 연결을 거부한다. 공용 기본 포트는 명시적 443과 동일하게 정규화하고 사내 비표준 포트는 유지한다. 인증 복제는 사용자/시스템 Git 설정과 상속된 `GIT_*` 설정을 격리하고 redirect·credential helper·hook·LFS smudge·submodule 재귀 실행을 차단한다. [Git redirect 설정 계약](https://git-scm.com/docs/git-config#Documentation/git-config.txt-httpfollowRedirects)에 따라 redirect 응답은 오류로 처리한다. 구현은 `fix: bind quick import credentials to trusted origins` 커밋에서 확인한다. 당시에는 ignore된 로컬 진행 기록으로 유지했으며, 이후 사용자 요청에 따라 구현과 함께 추적한다.
- **로컬 검증:** Windows / Java 25에서 `.\gradlew.bat test --tests '*QuickImportServiceTest' --tests '*QuickImportServiceParserTest' --tests '*QuickImportControllerTest' --tests '*CloneRootPathGuardTest'` 실행, 55건 통과·실패/skip 0. 테스트 파일 변경 없이 JShell에서 실제 컴파일된 URL 파서의 공용 주소/기본 포트·유사 호스트·userinfo·경로·사내 포트·연결 ID·중복 연결 18개 조건 통과. `GitCloneExecutor.runGit`의 실제 자식 Git에서 `git config --get --fixed-value`로 redirect/TLS/protocol/helper/hook/LFS/submodule 설정 10개 조건 통과(가상 자격 증명, 네트워크 접속 없음). JShell 초기 시도는 Windows 경로 공백·명령 길이 및 shell alias 인자 오류로 실행 실패했으며 classpath 축소와 직접 Git 인자 전달 후 재실행 결과만 통과로 집계한다.
- **잔여/지원 제한:** 4번 전체 DoD는 미완료다. 연결 선택은 실행 시점이므로 접수 시 connectionId/revision 고정, 관리자 승인 origin/대역 정책, DNS 재해석 방어, 실제 redirect/자격 증명 비전달 통신 검증, 사내 CA/proxy 환경의 정상 복제를 추가 검증해야 한다. HTTP 입력은 HTTPS로 암묵 변경하지 않고 거부한다. 인증 복제에서 사용자/시스템 Git 설정 및 `GIT_*` CA 설정에 의존하던 설치는 별도 명시적 신뢰 설정 지원이 필요하다. submodule/LFS는 실행 차단 상태이며 수집 지원을 완료했다고 표시하지 않는다. 익명 복제의 기존 Git 설정 경로는 이번 변경 대상이 아니며 5~6번 출구/실행 격리에서 함께 다룬다.

### 5. 망분리·정적 모드의 전체 외부 접속 통제 — P0 · [코드 확인]

- **2026-09-11 클라이언트 초기화 경계:** OSV/GHSA/NVD/deps.dev/EPSS/KEV에서 `airgapped=true`인데 snapshot service가 null이면 온라인 모드로 조용히 바뀌던 생성자를 수정했다. 이제 해당 조합은 초기화 오류이며 망분리 플래그를 false로 강등하지 않는다. 정상 온라인 설정과 저장소가 있는 망분리 설정은 유지한다. CocoaPods는 이미 저장소가 없어도 온라인으로 전환하지 않고 미확인 결과를 반환하므로 이 변경 대상에서 제외했다.
- **초기화 회귀 검증:** 수정 전 새 12건 중 여섯 클라이언트의 잘못된 설정 거부 검사 6건 실패. 수정 후 Windows/Java 25의 `test --tests '*AirgappedClientInitializationTest' --tests '*Osv*Test' --tests '*GitHubAdvisoryRangeTest' --tests '*Epss*Test' --tests '*Kev*Test' --tests '*DepsDev*Test' --tests '*Nvd*Test' --tests '*CocoaPodsSnapshotTest'` 239건 통과·실패/오류/skip 0. 로그 `build/roadmap-offline-init-before.log`, `build/roadmap-offline-init-after.log`. 커밋 제목 `fix: reject offline clients without snapshot storage`. 초기화 검사 자체는 원격 요청을 보내지 않았으며 전체 앱의 egress 차단/네트워크 캡처 검증을 대신하지 않는다. 새 외부 데이터/라이브러리·스키마·UI 변경 없음. 정적 모드의 다른 실행 도구·프록시·CA 및 실패 시 전체 흐름 검증은 계속 잔여다.
- 현재·대상: [DependencyManifestParserService](src/main/java/com/salkcoding/oswl/service/ingest/DependencyManifestParserService.java)의 npm 해석, [MavenBomVersionResolver](src/main/java/com/salkcoding/oswl/service/ingest/MavenBomVersionResolver.java)의 HTTP 경로가 VDB offline 설정과 별개다. npm에는 이미 --ignore-scripts가 있다.
- [ ] 수정: 정적 수집과 외부 해석 허용 설정을 통일한다. Git/npm/BOM/registry/AI 다운로드/IdP discovery·JWKS/SMTP/webhook 등 출구를 열거하고 내부 mirror·사내 CA·proxy 정책을 적용한다. 자료 부족은 PARTIAL/UNKNOWN으로 남긴다.
- 선행: 3~4번. 외부 해석 실행은 6번의 격리 후 활성화.
- DoD: 최초 설치·재기동·분석·장애 처리에서 외부 DNS/HTTP 시도와 승인 내부 통신을 구분해 계측한다. airgapped 플래그 하나나 TLS 검증 우회를 차단 증거로 삼지 않는다.
- **2026-09-10 부분 구현:** 공통 `oswl.ingest.allow-external-resolution`을 기본 false로 추가했다. Quick Import/CLI 공통 파서의 npm 잠금 생성·Maven/Gradle/dotnet 실행은 외부 해석과 build 실행이 모두 opt-in일 때만 허용하며 airgapped는 항상 우선 차단한다. Maven Central BOM HTTP도 같은 외부 해석/airgapped 설정을 따른다. 기존 supplied manifest/lock 및 로컬 BOM loader는 유지한다. `IngestResolutionPolicyTest`는 4가지 차단 설정에서 네 생태계 입력을 파싱하며 ProcessBuilder 생성 0건, BOM HTTP 차단 3조합 및 명시적 온라인 조회 1건을 검증한다. 이 8건과 기존 Maven/manifest/parser 40건을 Windows/Java 25의 `test --tests '*IngestResolutionPolicyTest' --tests '*MavenBomVersionResolverTest' --tests '*ManifestParserBoundaryTest' --tests '*QuickImportServiceParserTest'`로 실행해 48건 통과·실패/skip 0. commit 제목: `fix: require explicit external dependency resolution`.
- **잔여:** 전체 출구 정책, 내부 mirror/CA/proxy, 설치·재시작의 실제 DNS/HTTP 계측과 자료 부족의 module별 PARTIAL/UNKNOWN 증거는 미완료다. 명시적 build 실행은 신뢰된 입력 전용이며 6번 worker 격리가 완료됐다는 의미가 아니다.

### 6. 패키지 해석을 격리 worker로 제한 — P0 · [설계]

- 현재·대상: 패키지 관리자의 metadata resolution도 저장소 설정·환경·외부 통신·프로세스 자원을 사용한다. 단순 timeout은 격리가 아니다.
- [ ] 수정: 기본은 실행 없는 수집으로 두고, 정밀 해석은 비특권 일회성 worker로 이동한다. 환경 allowlist, CPU/RAM/디스크/프로세스/시간 상한, 내부 mirror, 취소·자식 프로세스 회수를 적용한다. 서버 DB 비밀값·Docker socket·호스트 credential을 전달하지 않는다.
- 선행: 5번. 생태계별 해석 명령은 20~31번에서 이 경계를 사용.
- DoD: 악성/오류 fixture의 출력 폭주·timeout·자식 프로세스·실패 후 자원이 회수된다. 격리 미준비 환경에서는 신뢰하지 않는 소스의 실행을 비활성화한다.

### 7. Auto Import·/parse·/scan 자원 및 중복 제어 통일 — P0 · [코드 확인/재현 필요]

- 현재·대상: [ScanController](src/main/java/com/salkcoding/oswl/controller/ingest/ScanController.java)의 직접 parse와 Quick Import admission 경계를 대조한다. 기존 ZIP 제한·queue·lease는 유지한다.
- [ ] 수정: 모든 진입점에 파일 수/크기/깊이/압축 해제/JSON·YAML·XML 복잡도·시간·동시성 예산을 적용한다. traversal·symlink·중복 엔트리·압축 폭탄·외부 엔티티를 검사하고 job quota·공정 대기·취소/재시작·temp 회수를 공유한다.
- 선행: 5~6번. 요청 재전송과 새 스캔의 식별은 16번 계약 사용.
- DoD: 동시 /parse도 서버 예산을 우회하지 못한다. 동일 요청만 중복 제거하며 취소·실패가 원본/기존 스캔을 손상시키지 않고 일반 API 응답 예산을 유지한다.
- **2026-09-10 부분 구현:** `GitCloneExecutor`의 출력 보관을 64 KiB로 제한하고 초과분은 계속 drain한다. 잘린 마지막 행은 제거해 부분 시크릿 노출을 피하며 실제 전달한 비밀번호도 로그/오류에서 마스킹한다. timeout/interruption/예외 때 관측 가능한 자식 프로세스와 부모를 종료하고 스트림을 닫는다. interruption은 sparse→full 재시도를 실행하지 않고 전파한다. 기존 관련 JUnit 46건 통과, JShell에서 실제 1 MiB 출력 실패 프로세스의 제한·시크릿 마스킹·실제 대기 프로세스의 interruption 후 종료 3개 조건 통과. 검증용 테스트 파일 변경은 없다. commit 제목: `fix: bound git output and clean up interrupted processes`.
- **잔여:** API 공통 admission/입력 복잡도 예산과 job 취소의 즉시 프로세스 중단 연결은 미완료다. 프로세스 트리 관측은 OS 수준 worker 격리를 대체하지 않으며 분리·재부모화된 프로세스는 6번 worker 경계에서 검증해야 한다.

### 8. 비활성 계정·권한 회수와 SSO 연결 검증 — P0 · [코드 확인/실환경 필요]

- 현재·대상: [OidcLoginSuccessHandler](src/main/java/com/salkcoding/oswl/auth/security/OidcLoginSuccessHandler.java), CLI 직접 인증, 기존 session의 enabled/권한 확인 경계를 재점검한다. OIDC의 정적 근거를 모든 SAML 경로의 결함으로 확대하지 않는다.
- [ ] 수정: 로그인·CLI·기존 session에 공통 계정 상태/권한 revision 검사를 적용한다. OIDC issuer+subject, SAML IdP+안정 subject로 연결하고 email 재할당·재연결 절차를 보호한다. SCIM 비활성화/역할 축소 시 session·개인 key 회수와 서비스 책임자 이관을 처리한다.
- 선행: 3번의 인증/배포 범위. 다중 인스턴스 최종 검증은 50번.
- DoD: 실제 IdP에서 비활성 사용자의 신규 접근, 기존 cookie, 축소 전 권한 사용이 거부된다. MFA 인증 문맥·회수 지연 상한·비상 관리자 복구를 확인한다.

### 9. CLI 자격 증명과 설치·전송 경계 보완 — P0 · [코드 확인]

- 현재·대상: [install.sh](src/main/resources/static/scripts/install.sh)의 jq/curl 인자 비밀번호 전달과 현재 사람 비밀번호/API 키 방식의 scope·만료·폐기를 확인한다.
- [ ] 수정: 비밀값을 argv/로그에 남기지 않는 입력·전송으로 바꾸고 안전한 임시 파일·데이터형 설정을 사용한다. 발급 대상 프로젝트·허용 runner·만료/폐기·감사를 연결한다. installer는 버전 고정·digest/서명 사전 검증·오프라인 설치를 제공한다.
- 선행: 8번. 서명된 출시 자산은 41번, 장기적인 machine identity는 58번.
- DoD: 프로세스 인자·진단 로그에 비밀번호/키가 없고 만료·폐기 credential이 거부된다. 설치 실패/취소가 기존 CLI를 훼손하지 않는다.

## 3단계 — 공통 판정 엔진과 스캔 증거 수정

### 10. 확정 영향·후보·미확인과 매칭 증거 모델 — P0 · [설계]

- 현재·대상: [VulnerabilityEnrichmentService](src/main/java/com/salkcoding/oswl/service/vulnerability/VulnerabilityEnrichmentService.java), [GatePolicyService](src/main/java/com/salkcoding/oswl/service/gate/GatePolicyService.java). 기존 소스별 coverage는 유지한다.
- [ ] 수정: 패키지 확인 수준, AFFECTED/NOT_AFFECTED/UNKNOWN과 식별 후보 CANDIDATE, 실제 악용 가능성을 분리한다. MatchEvidence에 query identity·원문/range·engine/DB revision·결과/reason을 저장한다. NOT_AFFECTED는 해당 advisory와 조사 범위로 한정한다.
- 선행: 3번; 2번 provenance 계약과 함께 확정.
- DoD: 비교 불능·이름 후보·조회 0건이 확정 영향/전체 안전으로 합쳐지지 않는다. API/UI/CLI와 재평가가 같은 의미를 사용하고 변경 전 데이터는 근거 미상으로 이행한다.

### 11. 범용 버전 비교기와 GHSA 비교 실패 처리 교체 — P0 · [코드 확인/진단]

- 현재·대상: [SimpleVersionComparator](src/main/java/com/salkcoding/oswl/vdb/SimpleVersionComparator.java), [GitHubAdvisoryClient.isVersionAffected](src/main/java/com/salkcoding/oswl/client/GitHubAdvisoryClient.java). 비교 예외/빈 버전을 true로 반환하는 경로가 있다.
- [ ] 수정: source range 문법 파서와 ecosystem comparator를 분리한다. 비교 불능은 UNKNOWN, 지원 문법은 각 생태계 의미로 평가한다. SemVer build metadata/prerelease를 먼저 수정하고 생태계별 native oracle 검증을 연결한다.
- 선행: 10번. 후보 라이브러리는 부록 B의 라이선스를 채택 버전에서 재확인.
- DoD: 진단에서 확인한 1.0.0+1 대 1.0.0+2의 -1, alpha 대 정식의 예외가 올바른 SemVer 결과로 바뀐다. 다른 생태계/지원하지 않는 문법을 SemVer로 강제하지 않는다.
- **2026-09-10 부분 구현:** `SemVerVersionComparator`를 추가해 명시적 OSV SEMVER 범위에만 연결했다. 빌드 메타데이터 무시, prerelease 순서, 큰 숫자 비교, 잘못된 문법 거부와 입력 길이 예산을 적용했다. 22개 자동 검사가 명세의 순서/메타데이터/잘못된 문법/1,501개 prerelease 식별자를 검증했다. `SimpleVersionComparator`의 GHSA 경로와 GHSA 비교 불능→확정 영향 문제는 아직 남아 있으므로 11번 전체 완료가 아니다.

- **2026-09-10 GHSA 실패 보존:** 빈 설치 버전/범위, 빈 clause, 미지원 연산자/범위 문법과 비교 예외를 확정 영향으로 반환하지 않고 lookup 실패로 전파한다. 앞 clause가 false여도 나머지 clause를 확인해 malformed를 정상 비영향으로 숨기지 않는다. NPM의 canonical 버전 비교에는 SemVer 비교기를 적용해 prerelease/build metadata 순서를 바로잡았다. [GitHub SecurityVulnerability 문서](https://docs.github.com/en/graphql/reference/security-advisories#securityvulnerability)의 comma로 결합하는 비교 연산자 계약을 확인했다(2026-09-10). npm dependency range의 전체 문법 지원을 뜻하지 않는다.
- **GHSA 회귀 검증:** mock GraphQL HTTP→실제 client→`GitHubAdvisorySource` 경로로 10건 검사, 수정 전 7건 실패를 확인했다. 수정 후 Windows/Java 25의 `.\gradlew.bat test --tests '*GitHubAdvisoryRangeTest' --tests '*VulnerabilityEnrichmentServiceTest' --tests '*SemVerVersionComparatorTest'` 62건 통과·실패/skip 0. 로그 `build/roadmap-ghsa-before.log`, `build/roadmap-ghsa-after.log`. 커밋 제목 `fix: preserve unresolved github advisory version ranges`. 자체 합성 응답이며 외부 데이터/라이브러리 추가와 UI 변경은 없다. 다른 생태계의 기존 범용 비교기 교체, 미완료 조회 중 이미 확인한 live findings 보존, 정규화·실제 데이터 대조·fixed 후보 충돌 검사는 여전히 잔여다.

### 12. OSV 범위·이벤트 의미를 공통 엔진으로 구현 — P0 · [코드 확인]

- **2026-09-11 온라인 상세 재판정:** [OSV query 공식 계약](https://google.github.io/osv.dev/post-v1-query/)의 fuzzy version matching 결과를 그대로 확정하지 않고, revision을 확인한 상세 공지의 package identity와 versions/ranges를 `OsvRangeEvaluator`로 다시 판정한다. 일치하는 패키지의 명시적 비영향 버전은 finding에서 제외하고, 패키지 누락/불일치·범위 근거 없음/지원 불능은 미완료 상태로 남긴다. PyPI 이름 비교에는 기존 canonical 규칙을 사용한다. 상세 조회 자체 실패/불일치의 ID 보존 정책은 유지하며, 미확인 사유를 finding과 별개 evidence로 영속화하는 작업은 잔여다.
- **공지 내부 합집합 일치:** bulk의 여러 affected 항목에서 같은 component가 확정 영향이면 다른 항목의 비교 불능 때문에 해당 공지 자체를 미확인으로 바꾸지 않는다. 이는 [OSV affected 합집합 의미](https://ossf.github.io/osv-schema/)에 따른 기존 ranges 합집합 정책과 같다. 같은 공지의 중복 finding을 방지하면서, 다른 공지에서 얻은 component 미확인 상태는 제거하지 않는다. unsupported 범위가 포함된 경우 fixed 선택기는 계속 후보를 보류한다.
- **검증:** 새 온라인/오프라인 비교 13건 중 수정 전 10건 실패, 여러 affected 항목의 합집합 비교 추가 후 bulk 불일치 1건을 별도로 재현했다. 수정 후 경계 버전/prerelease/build metadata/versions 합집합/미지원 버전/잘못된 identity·범위와 bulk→snapshot 조회 비교, 다른 공지의 미확인 보존을 포함한 `test --tests '*Osv*Test' --tests '*VulnerabilityEnrichmentServiceTest'` 163건 통과·실패/오류/skip 0. 고정 GHSA fixture의 기존 4개 버전 검사는 parseVuln 직접 호출에서 mock querybatch→상세 HTTP→revision/identity/범위 판정으로 강화했다. 기존 정상 mock에는 검증 대상 패키지/버전 근거를 보완하고 기대 판정은 유지했다.
- **누적 빌드:** Windows/Java 25에서 `.\gradlew.bat build verifyProdJar` 성공: 전체 2,848건 중 2,839건 통과·기존 환경 의존 skip 9건·실패/오류 0, 운영 JAR local controller 제외 검사 통과. 로그 `build/roadmap-osv-live-range-before.log`, `build/roadmap-osv-live-union-before.log`, `build/roadmap-osv-live-range-after.log`, `build/roadmap-osv-consistency-build.log`. 커밋 제목 `fix: recheck live osv findings with shared range evidence`. 자체 합성 입력과 기존 CC-BY-4.0 고지/출처가 있는 고정 GHSA 자료를 재사용했으며 새 외부 데이터/코드/라이브러리는 도입하지 않았다. UI 변경 없음. 실제 전체 공급자·생태계·Git ancestry 및 미지원 비교기 검증을 완료한 것은 아니다.
- 현재·대상: [OsvBulkSource.resolveAffected](src/main/java/com/salkcoding/oswl/vdb/OsvBulkSource.java)의 versions 우선 반환, 마지막 introduced 재사용, last_affected 배타 처리, limit 누락, GIT 일반 비교.
- [ ] 수정: versions와 ranges를 합집합으로 평가하고 이벤트 순서대로 여러 구간을 복원한다. fixed/limit는 제외, last_affected는 포함 경계를 적용한다. GIT는 repo·commit graph 증거로 판정하고 없으면 UNKNOWN. online/offline에서 공통 함수를 사용한다.
- 선행: 10~11번.
- DoD: 여러 introduced/fixed 구간·열린 구간·inclusive 경계·versions/ranges 혼합·commit ancestry의 정답을 충족한다. 합성 사례는 부록 B와 [OSV schema](https://ossf.github.io/osv-schema/)를 따른다.
- **2026-09-10 부분 구현:** `OsvRangeEvaluator`를 만들어 bulk 판정에 연결했다. versions/ranges 합집합, 정렬되지 않은 이벤트의 timeline, 반복 구간/열린 구간, last_affected 포함·fixed/limit 제외, 복수 limit의 범위 확장과 `*`를 적용했다. GIT 및 미지원 생태계 비교는 UNKNOWN으로 보존하며 Alpine의 기존 apk 비교를 유지한다. 잘못된 이벤트/근거 없음도 UNKNOWN으로 남긴다. 평가기 18건과 실제 bulk record→snapshot 결과/미확인 coverage 연결 2건, SemVer 22건 및 기존 OSV client 14건을 Windows/Java 25에서 실행해 총 56건 통과·실패/skip 0. 명령: `test --tests '*SemVerVersionComparatorTest' --tests '*OsvRangeEvaluatorTest' --tests '*OsvBulkRangeIntegrationTest' --tests '*OsvClientTest' --tests '*OsvLookupOutcomeTest'`. commit 제목: `fix: evaluate osv ranges as ordered version unions`.
- **잔여:** online 판정/재평가와 공통 평가기 통합, repo/commit graph 기반 GIT 판정, 추가 생태계 native comparator, MatchEvidence의 저장/노출 및 실제 데이터 oracle 동등성 검증이 남아 있다. GHSA 비교기는 아직 남아 있다. OSV live fixed 제안은 아래 13번에서 공통 선택기로 교체했다.

### 13. 원문 수명·중복·수정 버전·수집 실패 보존 — P0 · [코드 확인]

- **2026-09-11 OSV 입력 식별자 경계 통일:** 온라인 질의와 오프라인 snapshot key 생성 전에 공통으로 null 질의·누락 필드·빈 문자열·공백만 있는 ecosystem/name/version을 제외한다. 제외된 입력은 원래 위치의 미확인을 유지하고 정상 이웃 질의는 계속 처리한다. 기존 온라인 경로는 공백 필드를 전송하고 null 질의에서 예외가 발생했으며, 오프라인 경로도 공백 식별자로 key를 만들 수 있었다. 구체적인 버전 문법·생태계 지원 여부의 판정까지 이 검사로 확정하지 않는다.
- **회귀 검증:** null 질의와 각 필드의 빈 문자열/공백 7건이 수정 전 모두 실패했다. 수정 후 실제 client의 HTTP 요청 내용과 snapshot 조회 key, 두 모드의 결과 위치·미확인/정상 빈 결과를 함께 확인했다. Windows/Java 25의 `test --tests '*Osv*Test' --tests '*VulnerabilityEnrichmentServiceTest'` 200건 통과·실패/오류/skip 0. 로그 `build/roadmap-osv-identity-before.log`, `build/roadmap-osv-identity-after.log`. 커밋 제목 `fix: preserve unknown results for incomplete osv identities`. 자체 합성 입력이며 새 외부 데이터/코드/라이브러리와 UI 변경은 없다. 스냅샷 전체 패키지 키 정규화·기존 저장 키 migration 및 지원 생태계 계약은 여전히 잔여다.

- **2026-09-11 OSV batch 대응 검증:** [공식 querybatch 계약](https://google.github.io/osv.dev/post-v1-querybatch/)(확인 2026-09-11)은 응답 순서를 요청에 대응시킨다. 응답 개수가 실제 전송한 질의 개수와 다르면 빈 결과를 특정 패키지의 정상 조회로 확정하지 않도록 전체 해당 묶음을 미확인으로 반환한다. 응답에 질의 identity가 없어 누락 위치를 추측할 수 없으므로 잘못된 위치에 공지/수정 후보를 연결하지 않는다. 다음 정상 묶음은 계속 처리하고, null 필드로 전송에서 제외한 입력은 원래 위치의 미확인을 유지한다.
- **회귀 검증:** 새 개수 검사 3건 중 부족/초과 응답 2건이 수정 전 실패했다. 전송 제외 입력의 정렬과 1,001개 질의의 묶음 간 실패 격리까지 추가한 후 Windows/Java 25의 `test --tests '*Osv*Test' --tests '*VulnerabilityEnrichmentServiceTest'` 193건 통과·실패/오류/skip 0. 기존 온라인/오프라인 범위 및 조회 미확인 검사도 포함한다. 로그 `build/roadmap-osv-cardinality-before.log`, `build/roadmap-osv-cardinality-after.log`. 커밋 제목 `fix: reject misaligned osv batch responses`. 공식 응답 계약에서 도출한 자체 HTTP mock이며 새 외부 데이터/코드/라이브러리와 UI 변경은 없다. 실제 공급자 전체 장애 검증 및 조회 실패 이유의 API/화면 전파는 여전히 잔여다.

- **2026-09-11 저장된 GHSA 수정 제안 갱신:** 같은 advisory ID의 현재 공급자 응답을 모은 뒤 저장된 수정 버전을 교체한다. GHSA만 제공한 후보의 변경·제거도 반영하며, GHSA 후보가 없어도 같은 공지에 대한 현재 OSV 후보가 있으면 유지한다. CVE alias만 같은 다른 공지로 기존 값을 덮어쓰지 않고, 저장된 충돌은 기존의 완전한 양쪽 공급자 합의 조건을 충족해야 해제한다. 값이 같은 경우 추가 저장하지 않는다. 조회에서 공지가 아예 사라졌을 때의 철회·삭제 수명 처리는 여전히 잔여다.
- **누적 빌드:** `.\gradlew.bat build verifyProdJar` 성공. 전체 2,902건 중 2,893건 통과·기존 환경 의존 skip 9건·실패/오류 0, 운영 JAR에서 local controller 제외 확인. 로그 `build/roadmap-ghsa-stored-fix-build.log`. 별도 `uiTest`와 실제 PostgreSQL 검증은 실행하지 않았다.
- **회귀 검증:** 새 6건 중 변경·제거 2건이 수정 전 실패했다. 수정 후 Windows/Java 25에서 `test --tests '*VulnerabilityEnrichmentServiceTest' --tests '*GitHubAdvisoryRangeTest' --tests '*ContinuousMonitoringServiceTest' --tests '*FixConflictPersistenceTest'` 105건 통과·실패/오류/skip 0. 로그 `build/roadmap-ghsa-stored-fix-before.log`, `build/roadmap-ghsa-stored-fix-after.log`. 커밋 제목 `fix: reconcile stored fixes with current advisory candidates`. 자체 합성 입력이며 새 외부 자료/라이브러리·스키마·UI 변경은 없다. 이번 환경 확인에서 `docker`/`psql`/`postgres` 실행 명령을 찾지 못했으므로 V36의 실제 PostgreSQL 검증은 완료로 처리하지 않는다.

- **2026-09-11 GHSA cache/live 충돌 근거 병합:** 같은 advisory의 온라인 응답으로 캐시 레코드를 교체할 때 `fixVersionConflictCandidates`까지 없어지던 경로를 수정했다. 동일 ID에 저장된 충돌 후보는 온라인 응답의 후보와 합쳐 보존하며 단일 공급자의 완전/부분 응답만으로 해제하지 않는다. 일반 캐시의 오래된 fixed는 기존처럼 온라인 범위/수정 판정으로 교체한다. 다른 advisory의 캐시 데이터는 변경하지 않는다. snapshot의 과거 충돌을 자동 해제하려면 후보별 source/revision과 새 기준 시점의 증거가 필요하며 그 모델은 계속 잔여다. 현재는 충돌이 해소된 새 snapshot/충분한 재조회 근거가 필요하므로 이 변경을 모든 오래된 충돌의 자동 해제 완료로 해석하지 않는다.
- **병합 회귀 검증:** 확장한 6건 중 충돌 후보가 있는 캐시 3건이 수정 전 실패했다. 수정 후 Windows/Java 25의 `test --tests '*GitHubAdvisoryRangeTest' --tests '*VulnerabilityEnrichmentServiceTest' --tests '*ContinuousMonitoringServiceTest' --tests '*FixConflictPersistenceTest'` 99건 통과·실패/오류/skip 0. 로그 `build/roadmap-cached-conflict-before.log`, `build/roadmap-cached-conflict-after.log`. 커밋 제목 `fix: retain cached conflict evidence during advisory refresh`. 자체 HTTP mock과 기존 JPA/snapshot 검사이며 새로운 외부 자료/라이브러리·스키마·UI 변경은 없다.
- **2026-09-11 snapshot 충돌 후보 왕복:** 공통 SnapshotVuln에 선택 필드 `fixVersionConflictCandidates`를 추가해 export→import→OSV/GHSA offline DTO→enrichment 저장으로 전달한다. 후보가 있으면 scalar fixed는 항상 보류한다. 정기 모니터링의 신규/기존 finding도 전달받은 충돌을 저장한다. 후보 필드가 없는 기존 입력은 빈 후보 목록으로 읽고 기존 생성자 호출도 유지한다. 필드가 있으면 문자열 배열만 허용하며 null/숫자/객체/빈 문자열/100자 초과 후보는 반입 실패로 처리한다. 저장된 payload도 타입을 확인해 숫자→문자열 강제 변환으로 손상을 정상 취급하지 않는다.
- **snapshot 왕복 검증:** 수정 전 실제 DB finding→export/import 검사 1건 실패로 후보 소실을 재현했다. 수정 후 실제 ZIP 반입과 OSV/GHSA offline 조회, 보류 해제 상태에 반입 후보를 다시 적용한 JPA flush/clear/reload, malformed 후보 6종의 반입 롤백/손상 저장값 조회 제외와 기존 호환 경로를 포함해 Windows/Java 25에서 `test --tests '*FixConflictPersistenceTest' --tests '*SnapshotImportTransactionTest' --tests '*CocoaPodsSnapshotTest' --tests '*Osv*Test' --tests '*GitHubAdvisoryRangeTest' --tests '*VulnerabilityEnrichmentServiceTest' --tests '*ContinuousMonitoringServiceTest'` 301건 통과·실패/오류/skip 0. 로그 `build/roadmap-snapshot-conflict-before.log`, `build/roadmap-snapshot-conflict-after.log`. 커밋 제목 `fix: preserve remediation conflicts through offline snapshots`. 자체 합성 입력이며 외부 자료/라이브러리·DB 스키마·UI 추가 변경은 없다. 구버전 소비자가 선택 필드를 무시하는 경우의 보호를 보장하는 번들 capability/version 정책, 실제 운영 PostgreSQL 및 source/advisory/revision별 후보 이력은 잔여다.
- **2026-09-11 수정 충돌 영속화:** `library_cve_fix_conflicts`에 보류된 후보를 저장하고 entity의 보강/동일 공지 refresh/OSV 갱신이 이를 임의 해제하지 않도록 했다. 충돌이 있으면 조회 getter도 fixed를 반환하지 않아 scalar 칼럼에 오래된 쓰기가 남아도 안내를 확정하지 않는다. 현재 OSV·GHSA가 모두 완료되고 해당 finding에 두 출처의 근거가 있으며 모든 후보가 빠짐없이 하나로 일치할 때만 충돌을 해제한다. 후보의 source/advisory/revision별 개별 이력·시점은 아직 저장하지 않으므로 완전한 evidence 모델은 잔여다.
- **영속화 검증:** 수정 전 후속 단일 출처 갱신 검사 8건 중 충돌 4건 실패. 실제 H2/JPA flush→clear→reload 뒤 충돌 후보 유지, 세 가지 일반 갱신 진입점의 보류 유지, scalar stale write 시 조회 보류, 합의 후 해제/재조회와 완전·부분·누락·충돌 재조회 조건을 검사했다. V36 SQL을 H2 PostgreSQL 모드에서 두 번 적용하고 기존 행 보존, 후보 중복/미존재 CVE 거부, 소유 CVE 삭제 시 cascade를 검증했다. 운영 PostgreSQL 인스턴스 적용은 이번 검증에 포함하지 않았다.
- **누적 빌드와 배포:** Windows/Java 25의 `.\gradlew.bat build verifyProdJar` 성공: 전체 2,874건 중 2,865건 통과·기존 환경 의존 skip 9건·실패/오류 0, 운영 JAR local controller 제외 확인. 로그 `build/roadmap-fix-conflict-persistence-before.log`, `build/roadmap-fix-conflict-persistence-after.log`, `build/roadmap-fix-conflict-persistence-build.log`. 커밋 제목 `fix: persist remediation conflicts across source refreshes`. 기존 운영 DB에는 V36을 먼저 적용해야 하며 구버전 앱으로 되돌리면 충돌 보류 보호가 유지되지 않는 점을 deploy README에 명시했다. 자동 운영 DB 변경/배포/푸시는 수행하지 않았다. 자체 합성 검사로 외부 자료/라이브러리 추가와 UI 변경은 없다. snapshot 내보내기/재반입에서 후보 보존, 운영 동시 갱신과 출처 revision 모델은 계속 잔여다.
- **2026-09-11 현재 조회의 수정 후보 충돌:** OSV 저장 후 GHSA가 빈 값만 채우는 처리 순서 때문에 서로 다른 후보가 있어도 첫 값이 남던 경로에 최종 대조를 추가했다. 같은 advisory ID 또는 저장 단계에서 병합되는 CVE alias에 현재 후보가 둘 이상이면 fixed를 null로 보류하고 repository에 저장한다. 동일 후보·한 출처에만 후보가 있는 경우는 유지한다. 버전 문자열이 다르지만 생태계 의미상 동등한지 입증하는 정규화는 아직 적용하지 않아 그 경우도 보류하며, 검증 없이 큰 버전이나 최신 버전을 고르지 않는다.
- **충돌 회귀 검증:** 새 정상/충돌 7건 중 수정 전 충돌 3건 실패. 서로 다른 advisory의 같은 CVE alias 검사까지 추가한 뒤 Windows/Java 25에서 `.\gradlew.bat test --tests '*VulnerabilityEnrichmentServiceTest' --tests '*ContinuousMonitoringServiceTest' --tests '*GitHubAdvisoryRangeTest' --tests '*Osv*Test'` 221건 통과·실패/오류/skip 0. 로그 `build/roadmap-fix-conflict-before.log`, `build/roadmap-fix-conflict-after.log`. 커밋 제목 `fix: withhold conflicting current advisory fix candidates`. 자체 합성 입력이며 새 외부 자료/라이브러리·스키마·UI 변경 없음. 현재 조회 후보의 대조만 추가했으므로 충돌 사유/후보의 영속 저장과 다음 단일 출처 모니터링에서도 보류를 유지하는 처리는 계속 필요하다. 과거 저장 후보만 남은 상황 및 lookup failure/노후 snapshot과 현재 후보 사이의 비교도 미완료다.
- **2026-09-11 지속 모니터링 수정 버전 갱신:** 정기 OSV 재조회에서 이미 저장된 finding을 무조건 건너뛰던 경로에 동일 advisory ID의 fixed 갱신/해제를 연결했다. 스캔 경로와 같은 entity 갱신 API를 사용하며, 변경을 신규 발견으로 집계하거나 알림/메일을 다시 만들지 않는다. CVE alias만 같은 다른 advisory의 fixed는 덮어쓰지 않는다. 조회에서 사라진 finding/철회·현재 coverage 상태의 영속 저장, 과거 alert의 당시 정보와 현재 정보 구분은 잔여다.
- **모니터링 회귀 검증:** 정상 신규 발견 검사를 유지하고 갱신/해제/부분 응답/다른 공지 alias의 5개 사례를 추가했다. 수정 전 전체 6건 중 갱신 경로 4건 실패. 수정 후 Windows/Java 25에서 `.\gradlew.bat test --tests '*ContinuousMonitoringServiceTest' --tests '*VulnerabilityEnrichmentServiceTest' --tests '*Osv*Test'` 175건 통과·실패/오류/skip 0. 실제 runCycle→entity→mock repository 저장과 신규 집계 0/알림·메일·webhook 호출 없음까지 검사했다. 로그 `build/roadmap-monitor-fix-before.log`, `build/roadmap-monitor-fix-after.log`. 커밋 제목 `fix: refresh known advisory fixes during monitoring`. 자체 합성 입력이며 외부 데이터/라이브러리·스키마·UI 변경은 없고, 실제 DB/스케줄러 실행은 이번 검증에 포함하지 않았다.
- **2026-09-11 저장된 OSV 수정 버전 갱신:** 기존 OSV finding은 severity/CWE만 병합해 새 fixed를 반영하지 않고, null로 보류된 fixed 대신 옛 값을 유지하는 경로를 수정했다. 현재 OSV ID와 저장된 advisory ID가 같은 경우에만 최신 fixed로 교체하며 null/빈 값도 해제로 반영한다. deps.dev에 같은 공지가 있는 경로와 OSV 전용 경로 모두 적용하고 repository 저장까지 확인했다. CVE alias만 같은 서로 다른 advisory의 fixed를 이 갱신 API로 교체하지 않는다. 전체 조회 실패/공지 제외 후 남은 저장 finding, GHSA 경로, 출처별 fixed evidence·충돌 및 자동 철회 처리까지 해결한 것은 아니다.
- **저장 회귀 검증:** 기존 정상 backfill 검사를 보존해 확장한 6건 중 수정 전 4건 실패. 부분 응답에서 보류된 fixed의 해제까지 포함한 뒤 Windows/Java 25의 `.\gradlew.bat test --tests '*VulnerabilityEnrichmentServiceTest' --tests '*Osv*Test'` 169건 통과·실패/오류/skip 0. 로그 `build/roadmap-stored-fix-before.log`, `build/roadmap-stored-fix-after.log`. 커밋 제목 `fix: refresh stored fix versions from current osv findings`. 실제 enrichment 서비스→entity→mock repository 저장 호출을 검사했으며 이번 변경의 별도 DB 왕복/UI 검증은 수행하지 않았다. 스키마 및 외부 자료/라이브러리 추가는 없다.
- **2026-09-11 OSV 조회/상세 revision 대조:** querybatch의 `modified`와 상세 공지의 `modified`를 확인한 뒤 상세 정보/철회/수정 버전을 사용한다. 누락·잘못된 형식·미래 시각·불일치는 ID와 미완료 상태만 보존한다. 같은 ID를 뒤 페이지에서 다른 revision으로 받으면 먼저 수집한 상세 정보와 fixed도 무효화하고, 이후 일치하는 중복 행으로 다시 확정하지 않는다. 공유 상세 캐시는 각 query의 revision과 다시 대조한다. 기존 mock의 정상 공지에는 API 계약상 modified를 추가했고 기대 판정은 유지했다.
- **실제 정밀도 확인:** `immutable@5.1.4`를 실제 OSV API에 조회해 기존 권리 확인 공지 `GHSA-wf6x-7x77-mvgw`의 query modified `2026-09-10T03:50:41.600173Z`, 상세 modified `2026-09-10T03:50:41.600173338Z`를 확인했다. [OSV 서버 고정 revision](https://github.com/google/osv.dev/blob/e41b4af34c4d1d48638cbcf769c8d1b2b4f5aaeb/go/internal/database/datastore/vulnerability.go)의 GetModified는 Datastore, GetFull은 원문 protobuf를 읽는다. [Datastore 공식 timestamp 계약](https://docs.cloud.google.com/datastore/docs/reference/data/rest/Shared.Types/Value)은 마이크로초 아래를 절삭한다. 이 근거로 동일 Instant 또는 상세 시각의 마이크로초 절삭값과 정확히 같은 query 시각만 허용하며, 임의 허용 오차/초·밀리초 절삭은 하지 않는다. 동일 마이크로초 내 다른 갱신을 식별하는 content revision/hash는 공급자 응답에 없으므로 완전한 원자적 일관성 보장은 잔여다.
- **revision 회귀 검증:** 수정 전 새 13건 중 오류 경로 11건 실패. 실제 정밀도 차이·인접 마이크로초 불일치·밀리초 및 서로 다른 나노초 거부·공유 캐시 대조를 추가한 뒤 Windows/Java 25에서 `.\gradlew.bat test --tests '*Osv*Test' --tests '*VulnerabilityEnrichmentServiceTest'` 147건 통과·실패/오류/skip 0. 로그 `build/roadmap-osv-revision-before.log`, `build/roadmap-osv-revision-after.log`. 커밋 제목 `fix: validate osv detail revisions before using fixes`. 자동 검사는 자체 합성 데이터이며 실제 API 확인에서는 ID/시각만 기록하고 응답 원문을 저장·번들화하지 않았다. 공식 코드/문서는 동작 근거로 참조만 하고 복사/새 의존성 추가는 하지 않았다. 데이터 전체 재배포 권한과 영속 evidence·자동 재조회·실환경 갱신 경합 검증은 계속 잔여이며 UI 변경은 없다.
- **2026-09-11 OSV 페이지 수집:** [공식 querybatch pagination 계약](https://google.github.io/osv.dev/post-v1-querybatch/#pagination)에 따라 결과별 토큰을 동일한 package/version의 후속 요청에 전달한다. 빈 첫 페이지도 이어서 수집하고 ID 중복을 제거하며 원래 입력 위치를 유지한다. 후속 HTTP/응답 구조 오류·반복/잘못된 토큰·예산 초과에서는 앞서 얻은 finding과 미완료 상태를 보존한다. 패키지당 최초 포함 10페이지, 전체 호출당 후속 100회, 공유 상세 수집의 30초 경과 이후 추가 요청 중단을 적용했다. 개별 진행 중 요청에는 기존 HTTP timeout이 적용되므로 전체 wall time 30초 보장은 아니다. 이미 얻은 개별 전체 advisory 근거의 fixed 검증은 공통 선택기를 유지하며, 미수집 페이지를 근거로 다른 finding을 안전하다고 판정하지 않는다.
- **페이지 회귀 검증:** 수정 전 신규 8건 모두 실패. 페이지 예산 검사를 추가한 뒤 Windows/Java 25에서 `.\gradlew.bat test --tests '*Osv*Test' --tests '*VulnerabilityEnrichmentServiceTest'` 129건 통과·실패/오류/skip 0. 로그 `build/roadmap-osv-pages-before.log`, `build/roadmap-osv-pages-after.log`. 커밋 제목 `fix: collect paginated osv results without losing findings`. 자체 mock HTTP 입력이며 외부 데이터/라이브러리 추가, 원문 복사 및 UI 변경은 없다. 공식 문서는 통신 계약의 근거이며 OSV 집계 데이터의 포괄적 재배포 허가로 해석하지 않는다. 실제 공급자 대규모 pagination·query/detail modified revision 일치·수집 재개 및 영속 evidence는 미검증/잔여다.
- 현재·대상: [OsvClient](src/main/java/com/salkcoding/oswl/client/OsvClient.java)의 live fixed 제안은 패키지 필터/복잡한 범위 보류가 이미 있다. OsvBulkSource는 모든 affected 중 첫 fixed를 선택한다.
- [ ] 수정: 현재 패키지/릴리즈 분기에 수정안을 결합하고 불명확한 업그레이드는 보류한다. aliases/related/upstream, modified/withdrawn, 원문 출처를 보존한다. malformed·페이지 초과·404·인증/상세 조회 실패를 정상 0건으로 바꾸지 않는다.
- 선행: 2·10~12번.
- DoD: 온라인/오프라인 수정안과 철회 상태가 같고 타 패키지 fixed가 섞이지 않는다. alias 중복은 합치되 관련 downstream 공지를 전역 삭제하지 않는다. 현재 GHSA/NVD의 불완전 응답 오류 처리를 퇴행시키지 않는다.
- **2026-09-10 부분 구현:** `OsvFixVersionSelector`를 live 상세 파서와 bulk snapshot 생성에 공통 적용했다. 정확히 같은 생태계/패키지의 설치 버전 영향 범위에서 명시적 fixed 후보를 얻고, 모든 해당 패키지 범위/versions에서 비영향인 후보만 선택한다. 타 패키지 fixed, last_affected/limit, 철회 공지, 해석 불능/충돌은 수정 버전으로 제안하지 않는다. latest 조회는 사용하지 않는다. npm의 canonical SemVer `ECOSYSTEM` 범위도 공통 평가기에 연결했다. 커밋 제목: `fix: share package-specific osv fix selection`.
- **실제 데이터와 이용 근거:** [GitHub Advisory Database 고정 revision](https://github.com/github/advisory-database/blob/623b6fd0079a96ea7f0247007f47166c8cb9bcaf/advisories/github-reviewed/2026/03/GHSA-wf6x-7x77-mvgw/GHSA-wf6x-7x77-mvgw.json)의 identity/affected/modified 필드만 가공한 테스트 fixture를 동봉했다. 원문 SHA-256·revision·수집일·기여자 attribution·가공 내역과 CC-BY-4.0 원문은 `src/test/resources/advisories/`에 보존한다. 공식 저장소 LICENSE의 복제·가공·공유 조건에 따른 테스트 자료이며 운영 데이터 번들 승인을 의미하지 않는다. 구조화 자료의 4.x fixed는 4.3.8이고 설명문의 4.3.7과 충돌한다. [maintainer 4.3.8 release](https://github.com/immutable-js/immutable-js/releases/tag/v4.3.8)의 보안 수정과 대조해 해당 revision의 구조화 값을 회귀 기대값으로 사용했다. 다른 원천의 재배포 권한은 여전히 부록 A의 미확인 상태를 유지한다.
- **검증:** 수정 전 실제 fixture 4건은 모두 실패했다. 수정 후 3.8.2→3.8.3, 4.3.6/4.3.7→4.3.8, 5.1.4→5.1.5가 live 상세 파서 및 bulk→offline query 경로에서 일치한다. snapshot 저장소는 mock이므로 실제 디스크 반입/HTTP 전체 흐름의 증거는 아니다. 충돌·철회·malformed·타 패키지 등 선택기 10건을 포함해 `test --tests '*Osv*Test' --tests '*SemVerVersionComparatorTest' --tests '*VulnerabilityEnrichmentServiceTest'` 100건 통과. 로컬 node-semver 7.7.4(ISC)를 oracle로 사용한 순서 비교 6쌍도 일치했으며 새 런타임 의존성은 없다. 이후 Windows/Java 25에서 `.\gradlew.bat build verifyProdJar` 성공, 전체 1,117건 중 1,108건 통과·9건 skip·실패/오류 0. 로그: `build/roadmap-20260910-accuracy-build.log`. UI 변경은 없다.
- **잔여:** 13번 전체 DoD는 미완료다. 선택 보류 reason의 저장/API/UI 전파, 원문 수명/aliases/withdrawn의 finding 처리, 불완전 수집 상태 및 실데이터 디스크 반입 검증이 남아 있다. 비교기가 없는 생태계의 fixed 제안은 추측하지 않고 보류하므로 native 비교기를 이어서 보완해야 한다. npm 비정규 버전까지 지원한다고 주장하지 않는다. 온라인 API가 반환하는 영향 판정 자체를 동일 revision의 로컬 평가기로 대조하는 작업도 남아 있다. 9건 skip은 외부 환경/저장소/모델/대형 heap 입력 의존 검사로서 통과로 집계하지 않는다.

- **2026-09-10 철회 처리 보완:** `OsvWithdrawal`의 공통 상태 판별을 live 상세 응답과 bulk 수집에 연결했다. 유효한 UTC 철회 시각이 있는 공지를 활성 finding에서 제외한다. null/boolean/잘못된 시각은 UNKNOWN으로 남기고, bulk에서 영향 범위를 신뢰할 수 없으면 해당 bucket의 wanted coverage를 unresolved로 보존한다. fixed 선택기도 동일한 상태를 따른다. 상세 조회 실패는 기존처럼 미완료 finding을 유지하며 철회로 간주하지 않는다. 같은 CVE alias의 다른 활성 공지는 제거하지 않는다. 근거: [OSV withdrawn 필드 계약](https://ossf.github.io/osv-schema/#withdrawn-field), 확인일 2026-09-10. 외부 데이터 추가 없이 자체 합성 회귀 입력을 사용했다.
- **철회 회귀 검증:** 수정 전 새 회귀 3건 실패를 확인했다. 수정 후 alias 독립성 검사까지 추가해 Windows/Java 25에서 `.\gradlew.bat test --tests '*Osv*Test' --tests '*VulnerabilityEnrichmentServiceTest'` 82건 통과·실패/skip 0. 로그 `build/roadmap-withdrawal-before.log`, `build/roadmap-withdrawal-after.log`. 커밋 제목 `fix: exclude withdrawn osv records from active findings`. UI 변경 없음. 원문 철회 이력/시점의 영속 저장, 이전 snapshot 재반입과 저장된 취약점 이력의 갱신, 미래 철회 시각의 기준시점 정책은 잔여이며 이 보완만으로 13번 전체 완료를 뜻하지 않는다.

- **2026-09-10 bulk 실패 보존:** malformed JSON, 잘못된 최상위/공지 ID/패키지 identity/affected 구조, advisory가 없는 응답은 source 실패로 처리한다. Last-Modified/캐시 sidecar 기준일 누락을 오늘로 대체하지 않고 dated snapshot 생성을 거부한다. source 실패 후에도 VDB CLI가 출력 파일을 덮어쓰던 경로를 차단했다. 수집 중 얻은 일부 결과는 실패한 source의 완전한 coverage로 반환하지 않는다. 데이터 새로 반입 없이 자체 합성 입력을 사용했고 외부 의존성/라이선스 변경은 없다.
- **bulk 실패 회귀 근거:** 초기 입력 검사 10건 중 9건 실패, 출력 보존 검사 1건 실패를 수정 전에 확인했다. 실제 임시 디렉터리의 `--offline-sources` 캐시/ZIP/sidecar를 읽는 12건으로 malformed/빈 응답/기준일 누락/오래된 실제 기준일 유지/실패 후 기존 출력 byte 보존/정상 CLI build→verify를 검사했다. Windows/Java 25에서 `.\gradlew.bat test --tests '*Osv*Test' --tests '*Vdb*Test' --tests '*VulnerabilityEnrichmentServiceTest'` 94건 통과·실패/skip 0. 로그 `build/roadmap-bulk-integrity-before.log`, `build/roadmap-bulk-output-before.log`, `build/roadmap-bulk-integrity-after.log`. 커밋 제목 `fix: preserve vdb output when source integrity is unknown`. 전체 JSON schema/ZIP central directory·완전성 검증, cache 본문/sidecar 원자적 갱신, source별 상태의 영속 전파는 잔여다. UI 변경 없음.

- **2026-09-10 GHSA 부분 응답 보존:** 공지별 범위/구조 오류를 분리해 나머지 공지 처리를 계속하고 `IncompleteLookupException`으로 확인된 finding과 미완료 상태를 함께 전달한다. source adapter는 확인된 live finding을 기존 결과에 합치면서 `lookupFailed=true`를 유지한다. GraphQL 부분 오류/추가 페이지/누락된 pageInfo도 정상 완료로 표시하지 않으며 이미 확인한 공지를 버리지 않는다. 공지 identity가 없으면 확정 finding으로 생성하지 않는다. 페이지를 모두 수집하는 구현을 완료했다는 의미는 아니다.
- **부분 응답 검증:** 손상 node가 앞/뒤에 있는 경우, 추가 페이지, GraphQL errors+data, 누락 pageInfo의 HTTP→client→source 회귀 5건은 수정 전 전부 실패했다. 수정 후 기존 범위·보강 검사를 포함한 `.\gradlew.bat test --tests '*GitHubAdvisoryRangeTest' --tests '*VulnerabilityEnrichmentServiceTest'` 45건 통과·실패/skip 0. Windows/Java 25, 로그 `build/roadmap-ghsa-partial-before.log`, `build/roadmap-ghsa-partial-after.log`. 커밋 제목 `fix: retain confirmed findings from partial github responses`. 자체 합성 입력으로 외부 자료/의존성 추가 및 UI 변경은 없다. raw 오류 path/revision의 영속 evidence와 실제 공급자 페이지 연속 수집은 잔여다.

- **2026-09-10 GHSA 페이지 수집:** [GitHub 공식 pagination 계약](https://docs.github.com/en/graphql/guides/using-pagination-in-the-graphql-api)(확인 2026-09-10)에 따라 pageInfo.endCursor를 다음 요청의 after로 전달한다. 마지막 페이지까지 오류 없이 수집했을 때만 완료다. 후속 HTTP 실패/반복·누락 커서/부분 오류/예산 소진은 이전 페이지의 확인된 finding과 미완료 상태를 함께 유지한다. 요청당 first=100, 최대 10페이지, 경과 30초 이후에는 추가 요청을 시작하지 않는다. 진행 중인 HTTP 요청에는 기존 개별 timeout이 적용되므로 전체 wall time이 반드시 30초 이내라는 보장은 아니다.
- **페이지 회귀 검증:** 두 페이지 정상 수집, 후속 HTTP 실패, 반복 커서의 3건이 수정 전 전부 실패했다. 수정 후 10페이지 예산 검사까지 추가해 Windows/Java 25에서 `.\gradlew.bat test --tests '*GitHubAdvisoryRangeTest' --tests '*VulnerabilityEnrichmentServiceTest'` 49건 통과·실패/skip 0. 로그 `build/roadmap-ghsa-pages-before.log`, `build/roadmap-ghsa-pages-after.log`. 커밋 제목 `fix: collect github advisory pages with bounded cursors`. 자체 mock HTTP로 검증하며 원격 실제 계정/대규모 공지 데이터 수집은 아직 미검증이다. 새 데이터 배포나 외부 라이브러리 추가와 UI 변경은 없다. 공급자 전체 revision의 원자적 일관성 및 예산 초과 시 운영 재개는 잔여다.

- **2026-09-10 GHSA identity/철회:** 응답 node의 package.name/ecosystem을 요청과 대조하고, 불일치/누락은 미완료로 처리한다. advisory.withdrawnAt을 GraphQL에서 요청해 유효한 과거 철회 공지를 활성 finding에서 제외하며 malformed/미래 날짜는 미완료로 남긴다. [GitHub SecurityAdvisory 계약](https://docs.github.com/en/graphql/reference/security-advisories#securityadvisory), 확인 2026-09-10. 기존 범위/페이지 fixture에는 실제 요청 필드인 package 정보를 보완했으며 기대 판정은 유지했다.
- **identity/철회 검증:** 정상, 철회, 잘못된 날짜, 다른 이름, 다른 생태계, package 누락의 6건 중 수정 전 5건 실패를 확인했다. 수정 후 Windows/Java 25에서 `.\gradlew.bat test --tests '*GitHubAdvisoryRangeTest' --tests '*VulnerabilityEnrichmentServiceTest'` 55건 통과·실패/skip 0. 로그 `build/roadmap-ghsa-identity-before.log`, `build/roadmap-ghsa-identity-after.log`. 커밋 제목 `fix: validate github advisory identity and withdrawal`. 자체 합성 응답이며 외부 데이터/라이브러리 및 UI 변경은 없다. 생태계별 canonical 이름 정규화, 저장된 과거 GHSA finding 철회 전파는 잔여다. 공식 문서에서 기존 cvss 폐기 안내와 cvssSeverities 대체 필드를 확인했으므로 severity 필드 계약도 후속 수정해야 한다.

- **2026-09-10 GHSA severity 계약 보완:** 폐기 안내가 있는 `advisory.cvss` 대신 현재 GraphQL `cvssSeverities.cvssV3`를 조회·해석해 점수와 v3 vector를 보존한다. 공급자 severity `MODERATE`를 내부 `MEDIUM`으로 매핑한다. [CvssSeverities 및 severity enum 공식 정의](https://docs.github.com/en/graphql/reference/security-advisories#cvssseverities), 확인 2026-09-10. cvssV4를 v3 컬럼에 넣지 않으며 v4 전용 저장·내보내기·UI 경로는 잔여다. 새 필드를 지원하지 않는 구형 GHES에서는 GraphQL 오류가 조회 미완료로 남으므로 지원 프로파일 검증이 필요하다.
- **severity 회귀:** MODERATE 무점수 결과와 UNKNOWN+CVSS v3 9.8/vector의 client→source 검사 2건은 수정 전 모두 실패했다. 수정 후 `.\gradlew.bat test --tests '*GitHubAdvisoryRangeTest' --tests '*VulnerabilityEnrichmentServiceTest'` 57건 통과·실패/skip 0. Windows/Java 25, 로그 `build/roadmap-ghsa-cvss-before.log`, `build/roadmap-ghsa-cvss-after.log`. 커밋 제목 `fix: read current github advisory severity fields`. 자체 합성 응답을 사용하며 새 외부 데이터/라이브러리와 UI 변경은 없다. 공급자 실계정 호출, 점수/vector 불일치 evidence 및 전 source 다중 CVSS 보존은 아직 미검증/미완료다.

- **2026-09-10 GHSA fixed 검증:** 전체 페이지의 동일 GHSA/패키지 범위를 수집한 뒤 firstPatchedVersion 후보가 설치 버전보다 높고 모든 해당 범위에서 비영향인지 확인한다. 여러 검증된 후보는 버전 순서상 최소를 선택하며 latest 조회는 없다. 아직 설치 버전에 영향이 없는 다른 분기의 범위도 후보 검증에 포함한다. 부분 응답/HTTP 실패/커서·예산 문제에서는 확인된 finding을 유지하되 fixed를 보류한다. 현재 검증 비교기가 연결된 NPM/Maven/PIP 이외의 fixed도 추측하지 않고 보류하며 다른 생태계 비교기 구현은 계속 필요하다.
- **GHSA fixed 회귀:** 낮은/동일/여전히 취약한 후보, 정상 후보, 부분 응답, 다른 분기 충돌의 6건 중 수정 전 5건 실패를 확인했다. 후속 페이지가 이전 후보를 무효화하는 검사도 추가했다. Windows/Java 25에서 `.\gradlew.bat test --tests '*GitHubAdvisoryRangeTest' --tests '*VulnerabilityEnrichmentServiceTest'` 65건 통과·실패/skip 0. 로그 `build/roadmap-ghsa-fixes-before.log`, `build/roadmap-ghsa-fixes-after.log`. 커밋 제목 `fix: verify github fix candidates against complete ranges`. 자체 합성 입력이며 새 외부 데이터/라이브러리와 UI 변경 없음. fixed 보류 reason과 공급자 revision의 저장/API 노출, 과거 저장된 잘못된 fixed 정정은 잔여다.

- **2026-09-10 OSV 버전 목록 형식 검증:** 관련 패키지의 versions가 명시적 null/배열 아닌 값이거나 숫자·객체·null·빈 버전 문자열을 포함하면 bulk 수집을 실패 처리한다. asText 변환이나 무시로 정상 비영향 coverage를 만들지 않으며 CLI는 기존 번들을 보존한다. 공통 fixed 선택기도 같은 입력에서 MALFORMED_VERSIONS로 제안을 보류한다. [OSV schema의 versions 문자열 배열 계약](https://ossf.github.io/osv-schema/), 확인 2026-09-10. 필드 생략과 빈 배열은 기존 범위 평가를 유지한다.
- **형식 회귀:** 신규 형식 회귀 17건 중 수정 전 13건 실패를 확인했다. 실제 임시 ZIP/cache를 읽는 수집 검사, 공통 fixed 선택 검사와 malformed 목록의 CLI 출력 byte 보존 검사를 포함해 `.\gradlew.bat test --tests '*Osv*Test' --tests '*Vdb*Test' --tests '*PyPiAdvisoryComparisonTest'` 102건 통과·실패/skip 0. Windows/Java 25, 로그 `build/roadmap-osv-version-shape-before.log`, `build/roadmap-osv-version-shape-after.log`. 커밋 제목 `fix: reject malformed osv version lists`. 자체 합성 입력이며 외부 데이터/라이브러리와 UI 변경 없음. 전체 schema 검증, 목록에 있는 문자열의 모든 생태계별 문법 검증 및 저장된 과거 오판 정정은 잔여다.

- **2026-09-10 GHSA 캐시 병합 보완:** source adapter에서 같은 GHSA ID의 캐시 finding보다 현재 live finding의 판정을 반영한다. 기존 offline-first 중복 제거 때문에 새 fixed 또는 명시적 fixed 보류가 사라지던 문제를 수정했다. 부분 조회의 확인된 finding도 fixed 보류 상태로 교체하면서 lookupFailed를 유지한다. 응답에서 확인하지 못한 다른 ID의 캐시 finding은 보존한다. 이 변경만으로 순수 오프라인 과거 데이터의 수정/철회 전파가 완료되는 것은 아니다.
- **캐시 병합 회귀:** 정상 후보·범위 내 잘못된 후보·부분 응답의 HTTP→client→source 검사 3건은 수정 전 모두 실패했다. 수정 후 `.\gradlew.bat test --tests '*GitHubAdvisoryRangeTest' --tests '*VulnerabilityEnrichmentServiceTest'` 68건 통과·실패/skip 0. Windows/Java 25, 로그 `build/roadmap-ghsa-cache-before.log`, `build/roadmap-ghsa-cache-after.log`. 커밋 제목 `fix: preserve live advisory decisions when merging cache`. 자체 합성 입력이며 외부 자료/의존성 및 UI 변경 없음. 원문 modified 기준 revision 충돌 조정, live 미응답 ID의 철회 판정과 과거 DB fixed 정정은 잔여다.

### 14. CPE 추정을 확정 취약·게이트에서 분리 — P0 · [코드 확인]

- 현재·대상: [CpeNameMapper](src/main/java/com/salkcoding/oswl/client/CpeNameMapper.java), [NvdClient](src/main/java/com/salkcoding/oswl/client/NvdClient.java)의 이름 추정과 configuration 맥락 손실, 게이트의 신뢰도 처리.
- [ ] 수정: 추정 CPE는 후보로 보존한다. vendor/product/edition/OS/architecture 및 configuration AND/OR·환경 노드를 평가하고 isVulnerable 필터만으로 완전 판정을 주장하지 않는다. 공급자 증거 없는 이름 대응을 exact로 승격하지 않는다.
- 선행: 10~11번.
- DoD: 동명 제품·private/public 충돌·vulnerable=false 환경 노드·OS 조건 사례에서 후보와 확정 결과가 분리된다. 필수 후보 검토/UNKNOWN 차단은 별도 사유로 남는다. [NVD API](https://nvd.nist.gov/developers/vulnerabilities).

### 15. 공통 resolved inventory와 workspace 단위 coverage — P0 · [코드 확인/설계]

- 현재·대상: [DependencyManifestParserService](src/main/java/com/salkcoding/oswl/service/ingest/DependencyManifestParserService.java), [파서 지도](.agents/features/manifests.md). 한 workspace의 lockfile 성공을 다른 module까지 확대할 수 있는 경로를 검증한다.
- [ ] 수정: ecosystem/canonical name/resolved version/registry·source/purl qualifiers/digest와 플랫폼·scope·해석 설정을 보존한다. 선언→lock→resolved graph→built/deployed artifact를 구분하고 수집/실패/제외를 module별로 기록한다.
- 선행: 5~6·10번의 계약. 개별 생태계 구현은 20~31번.
- DoD: 혼합 monorepo·일부 lock 부재·중복 버전·사내 패키지·산출물 차이를 잃지 않는다. Auto Import와 CLI의 동일 입력은 동일 inventory/누락 근거를 생성한다.

### 16. 불변 scanId·재시도·재평가 이력 분리 — P0 · [코드 확인]

- 현재·대상: [ScanIngestService](src/main/java/com/salkcoding/oswl/service/ingest/ScanIngestService.java)의 기존 스캔 reset 재사용과 CI/리포트 참조 관계.
- [ ] 수정: 새 분석에는 새 scanId를 부여하고 idempotency key+input digest로 동일 요청 재전송만 합친다. commit·산출물·설정·도구/DB/policy revision을 고정한다. 새 DB 판정은 원본 스캔을 유지하고 evaluation revision으로 추가한다.
- 선행: 10·15번의 증거 계약.
- DoD: 같은 브랜치 재스캔/변경 payload/동일 재전송/새 정의 재평가가 구분된다. 기존 행의 모르는 commit/digest를 추정해 채우지 않고 legacy evidence로 남긴다.

### 17. 소스·시크릿·IaC 등 분석기별 완전성 상태 — P0 · [코드 확인]

- 현재·대상: [SecretIacScanService](src/main/java/com/salkcoding/oswl/service/secretscan/SecretIacScanService.java)의 반환/로그와 CLI manifest-only 제출. 공급원별 coverage 전체를 새로 만드는 작업은 아니다.
- [ ] 수정: SUCCESS/PARTIAL/FAILED/UNSUPPORTED/DISABLED/NOT_APPLICABLE와 결과 건수를 분리한다. 입력 누락·sparse checkout·빈 component·제외 파일·timeout을 기록하고 필수 검사 미실행을 게이트에 전달한다.
- 선행: 10·15~16번.
- DoD: 소스 미제공을 시크릿 검사 완료로, 수집 실패를 의존성 없는 안전한 프로젝트로 표시하지 않는다. NOT_APPLICABLE은 입력/정책 근거가 있고 필수 실패는 차단된다.

### 18. CLI parse receipt·commit·산출물과 결과 결합 — P0 · [설계]

- 현재·대상: [ScanController](src/main/java/com/salkcoding/oswl/controller/ingest/ScanController.java)의 parse→클라이언트 재제출과 CLI collection 흐름.
- [ ] 수정: 서버 내부 parse→ingest 또는 서버 보존 receipt+payload digest로 원본 입력을 연결한다. collector/CI job/commit/artifact identity를 전송하고 응답 scanId와 대조한다. 오프라인 collection bundle과 API schema/크기/참조 검증을 제공한다.
- 선행: 7·9·15~17번.
- DoD: parse 후 변경한 payload, 다른 commit의 스캔, 서버 연결 실패를 해당 CI 통과 증거로 사용할 수 없다. 안정적인 reason/종료 코드와 기존 CLI 이행 기간이 정의된다. collector 서명만으로 입력 완전성을 보장하지 않는다.

### 19. 강제 게이트·baseline·예외와 참고 평가 분리 — P0 · [코드 확인]

- 현재·대상: [GatePolicyService](src/main/java/com/salkcoding/oswl/service/gate/GatePolicyService.java)의 요청 옵션 우선/최근 baseline, [PrGateService](src/main/java/com/salkcoding/oswl/service/gate/PrGateService.java)의 headSha 연결을 재확인한다. scanId 소유 검사는 이미 있다.
- [ ] 수정: 조직 최소 정책을 요청으로 약화하지 못하게 조합 규칙을 만든다. 보호 브랜치 baselineScanId/commit/시점/policy revision을 고정한다. exact scan/artifact에 gate를 결합하고 what-if·VEX/예외·화면 필터를 구분한다.
- 선행: 10·14·16~18번.
- DoD: 완화 옵션·미래 baseline·다른 headSha·만료 예외·필수 UNKNOWN이 부당한 통과로 이어지지 않는다. 영향 취약 차단과 분석 불완전 차단의 사유를 구분하며 기존 override는 영향 preview 후 이행한다.

## 4단계 — 현재 지원 생태계별 입력·매칭 보완

**20~31번 공통:** 1번에서 해당 데이터 이용 조건을 확인하고, 6번의 격리 경계 안에서만 필요한 해석 명령을 실행한다. 11~15번의 공통 엔진·원문·inventory를 재사용한다. 각 항목은 native 규칙과 독립 근거로 확인한 취약/정상/미확인 표본을 제공하고, 42~43번에서 온라인·오프라인 결과를 검증한 뒤 기업 지원 완료로 표시한다. 데이터 제공 범위 밖의 패키지는 UNKNOWN/미지원으로 남긴다.

### 20. npm·Yarn·pnpm의 실제 패키지와 설치 트리 — P1 · [지원 범위별 필수]

- 현재·대상: [NpmManifestParser](src/main/java/com/salkcoding/oswl/service/ingest/parser/NpmManifestParser.java), lockfile·workspace 해석.
- [ ] 수정: alias 설치 이름과 실제 scoped name/registry, 중복 버전, optional/peer/dev, override, Git/tarball 원천을 보존한다. 실제 설치 트리와 lock을 연결하고 source별 SemVer 범위 문법을 적용한다.
- 선행: 4단계 공통 계약, 15번의 workspace별 coverage.
- DoD: alias·동명 private registry·prerelease·서로 다른 workspace lock 사례에서 식별/포함 여부가 맞는다. 선언 범위를 설치 버전으로 확정하지 않는다. [npm spec](https://docs.npmjs.com/cli/v11/using-npm/package-spec/).

### 21. Maven·Gradle 해석 결과와 JAR 구성요소 — P1 · [지원 범위별 필수]

- **2026-09-11 Maven versions 목록의 불확실성:** 설치 버전뿐 아니라 명시적 영향 버전 목록에 들어 있는 미해석 selector·property·range 표현·공백도 검증한다. 목록에 미해석 값이 있으면 정상 concrete version을 비영향으로 확정하거나 fixed 후보로 승인하지 않는다. 다른 명확한 범위 또는 정확한 버전 일치가 확인한 영향은 합집합 의미에 따라 보존한다. 아래 공식 Maven resolver 계약과 기존 concrete version 검사를 공통 OSV 평가기에 적용했으며 저장소 조회로 실제 버전을 해석했다고 주장하지 않는다.
- **회귀 검증:** `LATEST`/`RELEASE`/`${revision}`/`[1.0,2.0)`/공백 목록 5건은 수정 전 전부 실패했다. 수정 후 목록만 있는 경우, 범위와 합쳐진 경우, 확정 영향 보존, fixed 제안 보류를 확인했다. Windows/Java 25의 `test --tests '*MavenAdvisoryComparisonTest' --tests '*Osv*Test' --tests '*GitHubAdvisoryRangeTest'` 201건 통과·실패/오류/skip 0. 로그 `build/roadmap-maven-listed-before.log`, `build/roadmap-maven-listed-after.log`. 커밋 제목 `fix: retain uncertainty from unresolved maven version lists`. 자체 합성 입력이며 새 외부 데이터/코드/라이브러리·UI 변경 없음. 실제 해석 결과 및 Maven/Gradle 설치 구성요소 검증은 계속 잔여다.

- **2026-09-11 Maven 미해석 버전 배제:** [Maven 3.9.15의 공식 DefaultVersionResolver](https://raw.githubusercontent.com/apache/maven/maven-3.9.15/maven-resolver-provider/src/main/java/org/apache/maven/repository/internal/DefaultVersionResolver.java)(확인 2026-09-11)는 정확히 `LATEST`/`RELEASE`를 저장소 metadata로 해석한다. 이 두 값을 concrete version 비교에서 거부해 OSV/GHSA 범위 판정은 미확인/비교 실패로, fixed 선택은 제안 없음으로 유지한다. OSV 명시적 versions 목록도 미해석 설치 버전을 확정 영향/비영향으로 만들지 않도록 먼저 검사한다. `1.0.Final` 등 기존 qualifier와 snapshot 순서 검사는 유지했다. 실제 dependency resolution·timestamped snapshot의 코드 동일성 검증은 잔여다.
- **회귀 검증:** 새 selector 2건은 수정 전 실패했다. 설치 버전·fixed 경계·명시적 versions 목록 및 GHSA 비교 실패를 검증한 후 Windows/Java 25의 `test --tests '*MavenAdvisoryComparisonTest' --tests '*Osv*Test' --tests '*GitHubAdvisoryRangeTest'` 196건 통과·실패/오류/skip 0. 로그 `build/roadmap-maven-selectors-before.log`, `build/roadmap-maven-selectors-after.log`. 커밋 제목 `fix: keep unresolved maven selectors out of advisory comparisons`. Apache-2.0인 공식 구현의 계약을 확인하고 자체 합성 검사로 작성했으며 외부 코드를 복사하거나 새 라이브러리/데이터를 동봉하지 않았다. 기존 Maven 라이브러리의 LICENSE/NOTICE 조치는 유지한다. UI 변경 없음.

- 현재·대상: [MavenPomParser](src/main/java/com/salkcoding/oswl/service/ingest/parser/MavenPomParser.java), [MavenBomVersionResolver](src/main/java/com/salkcoding/oswl/service/ingest/MavenBomVersionResolver.java), Gradle 수집 경로.
- [ ] 수정: group/artifact/version에 scope·configuration·BOM/profile·substitution·classifier/type을 연결한다. Maven qualifier/범위를 native 규칙으로 처리하고 shaded/relocated·중첩 JAR를 아티팩트 증거로 추적한다.
- 선행: 4단계 공통 계약. BOM/parent POM은 5번의 승인 mirror만 사용.
- DoD: 선언과 선택 버전이 다른 사례, BOM·profile·shading·사내 패치본에서 누락/오탐을 구분한다. 수정안이 실제 의존 경로와 연결된다. [Maven 버전 순서](https://maven.apache.org/pom.html#version-order-specification).

- **2026-09-10 Maven native 비교:** `org.apache.maven:maven-artifact:3.9.15`의 `ComparableVersion`을 concrete version wrapper로 연결했다. Maven ECOSYSTEM OSV 범위/공통 fixed 선택과 GHSA Maven 범위 모두 같은 비교기를 사용한다. alpha/beta/RC/SNAPSHOT/정식/Final/ga/sp 순서와 정식 alias를 반영한다. Maven build/registry resolution은 실행하지 않는다. 빈/과도한 길이/공백/범위·property 입력을 concrete 비교로 오인하지 않도록 제한한다. 선언 range 전체 문법과 실제 resolver 결과 지원은 별도 잔여다.
- **비교 검증:** Maven prerelease와 정식 alias의 8건은 수정 전 OSV에서 전부 UNKNOWN으로 실패했다. 수정 후 OSV 영향 판정→fixed 선택 및 GHSA 비교 결과가 일치한다. Windows/Java 25에서 `.\gradlew.bat test --tests '*MavenAdvisoryComparisonTest' --tests '*Osv*Test' --tests '*GitHubAdvisoryRangeTest'` 99건 통과·실패/skip 0. 로그 `build/roadmap-maven-before.log`, `build/roadmap-maven-after.log`. 회귀 입력은 자체 합성이며 실제 공지 대규모 oracle 검증을 뜻하지 않는다. 커밋 제목 `fix: use native maven ordering for advisory versions`.
- **도입 권리/배포:** [공식 3.9.15 dependency/license 문서](https://maven.apache.org/ref/3.9.15/maven-artifact/dependencies.html)와 실제 Gradle 수신 JAR의 Apache-2.0 LICENSE 및 ASF NOTICE를 확인했다(2026-09-10). JAR은 수정 없이 포함하며 원문을 `META-INF/licenses/maven-artifact-LICENSE.txt`와 `maven-artifact-NOTICE.txt`에도 동봉하고 두 THIRD_PARTY_LICENSES 고지 및 OSS version manifest를 갱신했다. ComparableVersion 소스의 imports가 JDK만 사용함을 확인하고 transitive=false로 Maven 빌드용 의존성은 추가하지 않았다. 이 라이브러리 허가는 취약점 DB/아티팩트 내용의 재배포 허가와 별개다. 21번의 BOM/profile/shading·사내 패치본·의존 경로 증거는 미완료이며 UI 변경은 없다.

- **Maven 도입 빌드/배포 검사:** `.\gradlew.bat build verifyProdJar` 성공, 전체 1,172건 중 1,163건 통과·9건 skip·실패/오류 0. 로그 `build/roadmap-maven-build.log`. 생성 운영 JAR에서 `BOOT-INF/lib/maven-artifact-3.9.15.jar`와 원문 LICENSE/NOTICE 자산을 확인했고 생성 OSS manifest의 mavenArtifact 값은 3.9.15다. 기존 외부 환경/저장소/모델/대형 heap 의존 skip 9건은 통과로 계산하지 않았다.

### 22. PyPI 이름·PEP 440·설치 환경 의미 — P1 · [지원 범위별 필수]

- 현재·대상: [PythonManifestParser](src/main/java/com/salkcoding/oswl/service/ingest/parser/PythonManifestParser.java)의 선언·lock·설치 메타데이터 연결.
- [ ] 수정: 이름 정규화와 PEP 440 epoch/pre/post/dev/local·wildcard/compatible 범위를 적용한다. marker/extras·Python/플랫폼·wheel/sdist/direct URL·dist-info를 보존하고 선택된 환경의 설치 버전을 확인한다.
- 선행: 4단계 공통 계약.
- DoD: -_. 이름 변형, epoch/dev/post, marker로 미설치된 의존성, local 패치본을 올바르게 구분한다. requirements 범위를 배포 버전으로 사용하지 않는다. [Python 버전 명세](https://packaging.python.org/en/latest/specifications/version-specifiers/).

- **2026-09-10 PEP 440 비교:** 자체 `Pep440VersionComparator`를 OSV PyPI ECOSYSTEM 영향 범위·공통 fixed 선택과 GHSA PIP 비교에 연결했다. epoch/zero padding/pre·post·dev/local 순서, alias 정규화, 큰 정수를 처리하고 잘못된 문법은 비교 불능으로 남긴다. [PyPA version scheme](https://packaging.python.org/en/latest/specifications/version-specifiers/)을 기준으로 작성했으며 dependency specifier/환경 marker 해석과는 별개다.
- **PyPI 회귀/oracle:** 초기 공통 판정 8건은 전부 UNKNOWN으로 실패했다. 로컬 Python 3.14의 pip vendored packaging 26.2로 자체 버전 38개의 교차 비교 1,444개를 생성해 Java 결과와 모두 일치함을 확인했다. 무효 버전 9건과 공통 영향/fixed/GHSA 경로 8건도 검증했다. 기존 PyPI 미지원 검사는 실제 지원에 맞춰 PyPI AFFECTED 및 미지원 NuGet UNKNOWN을 별도로 확인하도록 보완했다. Windows/Java 25에서 `.\gradlew.bat test --tests '*Pep440VersionComparatorTest' --tests '*PyPiAdvisoryComparisonTest' --tests '*Osv*Test' --tests '*GitHubAdvisoryRangeTest'` 1,552건 통과·실패/skip 0. 로그 `build/roadmap-pypi-before.log`, `build/roadmap-pypi-after.log`. 커밋 제목 `fix: share pep440 ordering across advisory paths`.
- **oracle 권리/잔여:** packaging의 로컬 원문 LICENSE/APACHE/BSD와 [공식 LICENSE](https://github.com/pypa/packaging/blob/main/LICENSE)를 확인했다(2026-09-10). Python 도구는 검증에만 사용했으며 해당 구현 코드를 복제하거나 새 runtime 의존성을 추가하지 않았다. `src/test/resources/version-oracles/README.md`에 도구 버전·재생성 방법·출처를 기록했다. 기대값은 자체 입력의 계산 결과이며 취약점 데이터 재배포가 아니다. 실제 공지 대규모 검증, PEP 503 이름 정규화/설치 환경·marker·specifier·local patch 근거, enumerated version의 정규화는 잔여이며 UI 변경은 없다.

- **PEP 440 도입 전체 검사:** `.\gradlew.bat build verifyProdJar` 성공, 전체 2,633건 중 2,624건 통과·9건 skip·실패/오류 0. 로그 `build/roadmap-pypi-build.log`. 1,444개 oracle 조합이 각각 parameterized test로 집계되므로 테스트 수 증가를 신규 기능 수나 실데이터 coverage 증가로 해석하지 않는다. 기존 외부 환경/저장소/모델/대형 heap 조건 skip은 통과 근거에서 제외한다.

- **2026-09-10 PyPI versions 목록:** 명시적 OSV versions 목록에서 PEP 440 정규화상 같은 PyPI 버전(1.0/1.0.0, RC 대소문자, rev/post alias)을 인식한다. local과 public 버전은 별도로 비교하며 Maven/다른 SemVer 빌드의 비교 동률을 동일 아티팩트로 확대하지 않는다. PyPI 목록의 해석 불능 버전은 UNKNOWN 근거로 유지한다. fixed 후보가 목록의 동등 버전과 충돌하면 제안하지 않는다. 근거는 위 PyPA version scheme의 정규화와 OSV versions/ranges 합집합 계약이다.
- **목록 회귀:** 수정 전 6건 중 4건 실패를 확인했다. local/post 구분, 정규화 alias, malformed 및 fixed 충돌 검사를 포함한 `.\gradlew.bat test --tests '*Osv*Test' --tests '*PyPiAdvisoryComparisonTest'` 79건 통과·실패/skip 0. Windows/Java 25, 로그 `build/roadmap-pypi-enumerated-before.log`, `build/roadmap-pypi-enumerated-after.log`. 커밋 제목 `fix: match normalized pypi versions in advisory lists`. 자체 합성 입력이며 새 외부 데이터/라이브러리와 UI 변경은 없다. 기관 local patch의 실제 코드 동등성이나 package-name 정규화 검증을 완료했다는 뜻은 아니다.

- **2026-09-10 PyPI 이름 매칭:** [PyPA 이름 정규화 계약](https://packaging.python.org/en/latest/specifications/name-normalization/)(확인 2026-09-10)에 따라 공통 `AdvisoryPackageNames`로 ASCII 이름 검증·소문자화·연속 점/밑줄/하이픈 통합을 적용했다. GHSA 요청/응답 대조, OSV fixed 선택 및 bulk wanted 매칭에서 사용한다. bulk는 canonical→원래 요청명 index를 bucket당 한 번 만들고 결과는 원래 componentKey로 기록한다. 기존 DB/스냅샷 키를 변경하지 않으므로 이전 키 형식의 자동 migration을 주장하지 않는다.
- **이름 회귀:** Friendly_Bard/friendly.bard/FRIENDLY--BARD의 bulk 매칭 3건은 수정 전 전부 실패했다. 수정 후 원래 snapshot key 보존·fixed 선택 및 GHSA의 canonical 요청과 alias 응답을 확인했다. Windows/Java 25에서 `.\gradlew.bat test --tests '*PyPiAdvisoryComparisonTest' --tests '*Osv*Test' --tests '*GitHubAdvisoryRangeTest'` 110건 통과·실패/skip 0. 로그 `build/roadmap-pypi-names-before.log`, `build/roadmap-pypi-names-after.log`. 커밋 제목 `fix: normalize pypi names for advisory matching`. 자체 합성 입력이며 새 외부 데이터/라이브러리와 UI 변경 없음. 기존 다른 별칭으로 저장된 offline key의 재조회 통합, inventory 중복·registry origin 및 설치 환경 판별은 잔여다.

### 23. Go checksum 기록을 실제 module graph와 분리 — P1 · [코드 확인/지원 범위별 필수]

- 현재·대상: [GoManifestParser.parseGoSum](src/main/java/com/salkcoding/oswl/service/ingest/parser/GoManifestParser.java)은 이름으로 중복 제거해 첫 버전을 선택한다.
- [ ] 수정: go.sum은 checksum 이력/후보로 한정하고 격리된 go list -m -json all 등의 선택 결과를 연결한다. replace/exclude/go.work, /v2 path·pseudo-version·toolchain·GOOS/GOARCH/build tags를 보존한다.
- 선행: 4단계 공통 계약. 심볼 도달성의 확대는 57번.
- DoD: go.sum의 오래된 여러 버전과 실제 build list가 다른 사례에서 현행 패키지를 정확히 선택한다. 해석 실패나 checksum-only 입력은 제한 상태다. [Go modules](https://go.dev/ref/mod).

- **2026-09-11 Go 버전 순서 연결:** [Go modules 공식 versions/pseudo-versions 규칙](https://go.dev/ref/mod#versions)을 근거로 기존 자체 SemVer 비교기에 Go 모듈의 `v` 접두사 처리를 결합했다. OSV Go SEMVER/ECOSYSTEM 범위와 공통 fixed 선택, GHSA GO 영향/수정 후보 검증에 같은 비교기를 연결했다. prerelease/pseudo-version은 SemVer 순서로 비교하며 build metadata는 우선순위에 영향을 주지 않는다. 브랜치 이름·축약 버전·비정상 SemVer는 UNKNOWN/비교 실패로 남긴다.
- **Go 범위 회귀:** prefix 유무, pseudo-version, rc, 정식, incompatible 및 두 자리 minor 8건은 수정 전 모두 실패했다. 수정 후 OSV 영향→fixed 및 GHSA 영향→fixed 선택, 잘못된 concrete version 5건을 포함한 `test --tests '*GoAdvisoryComparisonTest' --tests '*Osv*Test' --tests '*GitHubAdvisoryRangeTest'` 141건 통과·실패/skip 0. Windows/Java 25, 로그 `build/roadmap-go-version-before.log`, `build/roadmap-go-version-after.log`. 커밋 제목 `fix: share go module version ordering across advisories`. 공식 규칙에서 도출한 자체 합성 입력이며 외부 코드/데이터/라이브러리를 동봉하지 않았다. 설치된 Go 실행기가 없어 native 실행 oracle 및 실제 Go 공지 대조는 아직 미검증이다. pseudo-version의 실제 commit/timestamp/ancestry 검증, go.sum/build list 구분, 모듈 경로 major 호환, 표준 라이브러리 toolchain 버전과 명시적 versions 목록 alias는 잔여다.

- **2026-09-11 Go 목록 identity 보완:** 명시적 OSV versions에서도 Go 모듈의 `v` 접두사 유무를 동일 버전으로 비교한다. 입력의 SemVer 문법은 검증하고 잘못된 목록 버전은 UNKNOWN 근거로 유지한다. 단순 우선순위 동률로 build metadata까지 동일 아티팩트로 확대하지 않는다. 공통 fixed 선택기는 이 목록 판정으로 취약 버전 alias와 충돌하는 후보를 보류한다.
- **Go 목록 회귀:** 양방향 접두사 alias, build 구별, 비영향, malformed 목록과 fixed 충돌의 신규 6건 중 수정 전 4건 실패를 확인했다. `test --tests '*GoAdvisoryComparisonTest' --tests '*Osv*Test' --tests '*GitHubAdvisoryRangeTest'` 147건 통과·실패/skip 0. Windows/Java 25, 로그 `build/roadmap-go-list-before.log`, `build/roadmap-go-list-after.log`. 커밋 제목 `fix: match go version prefixes in advisory lists`. 자체 합성 자료로 외부 데이터/의존성 및 UI 변경 없음. 실제 Go 공지/native oracle와 module graph·원천 아티팩트 일치 검증은 잔여다.

### 24. Cargo alias·feature·target·source 식별 — P1 · [지원 범위별 필수]

- 2026-09-11 누적 검증: `3759b59`까지의 EPSS·Go·Rust 변경을 포함해 Windows/Java 25에서 `.\gradlew.bat build verifyProdJar` 성공. 전체 2,805건 중 2,796건 통과·기존 환경 의존 skip 9건·실패/오류 0. 운영 JAR의 local 전용 controller 제외 검사도 통과했다. 로그 `build/roadmap-advisory-regression-build.log`. UI 브라우저 검증 및 실환경/데이터 권한 확인을 대신하지 않으며 1.0.6 전체 완료를 뜻하지 않는다.
- 2026-09-11 부분 보강: GHSA의 Rust 확정 버전 비교와 수정 후보 검증에 기존 strict SemVer 비교기를 연결했다. OSV `crates.io`의 `SEMVER` 경계와 prerelease 숫자 순서·정식 버전·build metadata 결과를 대조하고, 잘못된 확정 버전·지원하지 않는 범위·다른 취약 구간에 포함된 수정 후보는 보류한다. `CargoAdvisoryComparisonTest`, `GoAdvisoryComparisonTest`, `Osv*Test`, `GitHubAdvisoryRangeTest` 161건 통과(실패/오류/skip 0). [Cargo 버전 규칙](https://doc.rust-lang.org/cargo/reference/specifying-dependencies.html)은 참고만 했으며 외부 코드/데이터나 의존성을 추가하지 않았다. Cargo 의존성 선택의 prerelease 제외 규칙을 advisory의 명시적 경계 비교로 일반화하지 않는다. native Cargo 실행 환경은 없으며 RustSec 실제 데이터 대조, lock/metadata source·alias·feature·target·checksum 수집은 미완료다.
- 현재·대상: [CargoManifestParser](src/main/java/com/salkcoding/oswl/service/ingest/parser/CargoManifestParser.java)의 선언 alias/package·source 처리.
- [ ] 수정: Cargo.lock/cargo metadata에서 실제 crate name/version/source/checksum을 확보한다. registry/git/path, 활성 feature/target, 중복 버전을 연결하고 patched/unaffected와 yanked/unmaintained 경고를 구분한다.
- 선행: 4단계 공통 계약. RustSec record별 라이선스는 2번.
- DoD: renamed dependency·동일 crate 여러 버전·비활성 target·git crate를 검증한다. Git revision을 crates.io 버전으로 강제하지 않는다. [Cargo resolver](https://doc.rust-lang.org/cargo/reference/resolver.html).

### 25. NuGet TFM·RID·정규화 버전과 publish 결과 — P1 · [지원 범위별 필수]

- 현재·대상: [NugetManifestParser](src/main/java/com/salkcoding/oswl/service/ingest/parser/NugetManifestParser.java), PackageReference/lock/assets 입력.
- [ ] 수정: packages.lock.json/project.assets.json과 publish 산출물에서 TFM/RID별 selected version·transitive graph를 수집한다. 이름 대소문자/정규화/prerelease/floating 규칙과 Central Package Management를 처리하고 SDK/runtime을 별도 식별한다.
- 선행: 4단계 공통 계약.
- DoD: TFM/RID별 선택 차이, 정규화 버전, 선언과 publish 차이를 검증한다. Microsoft 제품 CPE와 개별 NuGet 패키지를 혼동하지 않는다. [NuGet 버전](https://learn.microsoft.com/en-us/nuget/concepts/package-versioning).

### 26. RubyGems·Bundler platform/source 매칭 — P1 · [지원 범위별 필수]

- 현재·대상: [RubyLockParser](src/main/java/com/salkcoding/oswl/service/ingest/parser/RubyLockParser.java), Gemfile.lock 및 실제 bundle.
- [ ] 수정: Gem::Version/요구 범위와 플랫폼 선택을 적용한다. version/platform/source/Git revision·vendor 패치를 보존하고 GHSA/OSV의 원천을 연결한다. RubySec 전체 추가는 역사적 OSVDB 조건 확인 전 보류한다.
- 선행: 4단계 공통 계약, 1번 및 부록 A.
- DoD: ruby/jruby·플랫폼 gem·prerelease·Git gem·사내 패치본에서 확정/후보를 구분한다. [RubyGems 관례](https://guides.rubygems.org/patterns/).

### 27. Composer 설치 reference·alias·가상 패키지 — P1 · [지원 범위별 필수]

- 현재·대상: [ComposerLockParser](src/main/java/com/salkcoding/oswl/service/ingest/parser/ComposerLockParser.java), composer.lock 및 설치 메타데이터.
- [ ] 수정: source/dist reference·provide/replace·branch alias·dev stability·PHP/extensions를 연결한다. 가상 패키지 제공자를 취약 패키지로 오인하지 않도록 하고 빌드/배포의 dev dependency 노출을 구분한다.
- 선행: 4단계 공통 계약. FriendsOfPHP 보강은 1번 승인과 품질 대조 후.
- DoD: replace/provide·dev alias·개발 의존성 제외/포함 사례와 Composer 범위 경계를 검증한다. [Composer 버전](https://getcomposer.org/doc/articles/versions.md).

### 28. Conda·pixi·explicit의 PyPI 추정 승격 방지 — P1 · [코드 확인/지원 범위별 필수]

- 현재·대상: [CondaLockParser](src/main/java/com/salkcoding/oswl/service/ingest/parser/CondaLockParser.java), [CondaPypiMappingService](src/main/java/com/salkcoding/oswl/service/ingest/CondaPypiMappingService.java)는 이름 매핑 후 같은 버전을 PyPI로 조회한다.
- [ ] 수정: channel/subdir/name/version/build/build-number/digest와 recipe·patch 원천을 보존한다. PyPI 대응은 후보로 두고 패치·소스 동일성 증거로 승격한다. 이름 매핑의 다대일/중복/비정상 값을 검사한다.
- 선행: 4단계 공통 계약. 내장 mapping의 원본 revision/라이선스 확인은 1~2번.
- DoD: 채널/빌드별 backport·동명 패키지·Python 외 native library 사례에서 같은 PyPI 버전이라는 이유만으로 확정하지 않는다. [Conda spec](https://docs.conda.io/projects/conda/en/stable/user-guide/concepts/pkg-specs.html).

### 29. Conan·vcpkg·vendored·submodule의 원천 증거 — P1 · [코드 확인/지원 범위별 필수]

- 현재·대상: [NativeManifestParser](src/main/java/com/salkcoding/oswl/service/ingest/parser/NativeManifestParser.java), [ConanLockParser](src/main/java/com/salkcoding/oswl/service/ingest/parser/ConanLockParser.java)의 version>=·baseline·revision.
- [ ] 수정: Conan lock/rrev/prev/package ID·profile/옵션, vcpkg 실제 port version/baseline/triplet/feature·버전 scheme을 보존한다. vendored/submodule은 repo·commit·digest/patch로 식별하고 CPE 이름 추정은 후보로 둔다.
- 선행: 4단계 공통 계약, 12·14번. submodule 일반 CPE 제외의 보수적 처리는 유지.
- DoD: baseline/최소 버전을 설치 버전으로 오인하지 않고 Git graph 없는 입력은 UNKNOWN이다. [Conan revisions](https://docs.conan.io/2/tutorial/versioning/revisions.html), [vcpkg versioning](https://learn.microsoft.com/en-us/vcpkg/users/versioning).

### 30. CocoaPods 버전과 실제 source revision 연결 — P1 · [코드 확인/지원 범위별 필수]

- 현재·대상: [CocoaPodsSpecsClient](src/main/java/com/salkcoding/oswl/client/CocoaPodsSpecsClient.java)의 source.git와 enrichment의 pod version→SwiftURL 연결. 기존 Specs snapshot 기능은 유지한다.
- [ ] 수정: Podfile.lock·subspec·spec checksum·source.tag/commit/HTTP artifact를 보존한다. source revision 동일성이 입증된 경우만 SwiftURL/Git advisory에 연결한다. SwiftPM Package.resolved는 별도 지원 범위를 검증한 뒤 추가한다.
- 선행: 4단계 공통 계약, 12번의 Git range 처리.
- DoD: pod 버전과 저장소 tag가 다른 사례·subspec·HTTP 배포에서 추정 매칭을 확정하지 않는다. live/Specs snapshot 출처와 결과가 일치한다. [podspec](https://guides.cocoapods.org/syntax/podspec.html).

### 31. Debian·Ubuntu·Alpine과 OCI 실제 패키지 판정 — P1 · [지원 범위별 필수]

- 현재·대상: 현재 OS package 조회·OCI 검사·Alpine 전용 비교기는 유지하되 배포판/원천 패키지/패치 상태를 검증한다.
- [ ] 수정: 배포판/release/source·binary package/architecture/epoch/revision과 dpkg·apk 비교를 적용한다. vendor backport·not affected/deferred/ignored/EOL을 분리한다. OCI image digest와 최종 rootfs를 기준으로 삭제된 하위 layer와 실행 노출을 구분한다.
- 선행: 4단계 공통 계약, 1번의 distro 데이터 조건. RPM은 NEVRA·rpm/vendor errata를 별도 구현한 후 지원.
- DoD: 낮은 upstream 버전의 backport 완료 패키지를 일반 NVD 범위만으로 오탐하지 않는다. 실제 취약/정상/미지원 distro 이미지를 검증한다. [backport 근거](https://access.redhat.com/security/updates/backporting).

## 5단계 — 로컬 데이터·망분리 번들·운영 신뢰

### 32. 원천별 수집 파이프라인·경로·완전성 관리 — P0 · [코드 확인/설계]

- 현재·대상: [VdbBuilderCli](src/main/java/com/salkcoding/oswl/vdb/VdbBuilderCli.java), [OsvBulkSource.resolveBucket](src/main/java/com/salkcoding/oswl/vdb/OsvBulkSource.java), 기존 client/checkpoint 경로.
- [ ] 수정: OSV 상위 Alpine/Debian/Ubuntu dump를 가져와 정확한 distro ecosystem으로 필터링한다. 소스별 수정 cursor·페이지·retry/rate limit·철회·손상 격리·수집 checkpoint를 관리한다. 코드에 남은 버전 접미사 bucket과 공식 안내를 실제 대조한다.
- 선행: 1~2·5·12~13번.
- DoD: 404/부분 수집/손상 행이 정상 clean DB를 만들지 않는다. upstream 오류와 데이터 coverage가 표시된다. 대용량 dump의 실제 다운로드/형식 검증을 기록한다. [OSV 배포 안내](https://google.github.io/osv.dev/data/).

- **2026-09-10 EPSS 경로 일관성:** live 클라이언트도 오프라인과 동일하게 유한한 0~1 점수만 반환한다. 손상 점수만 제외하고 다른 정상 응답 행은 유지한다. bulk 생성기는 잘못된 점수나 잘린 데이터 행을 건너뛰어 부분 성공으로 처리하지 않고 IOException으로 수집 실패를 전달한다. 확률 범위는 [FIRST 공식 EPSS 설명](https://www.first.org/epss/)의 계약에 따른다.
- **점수 경로 회귀:** mock HTTP live 응답과 실제 임시 gzip/cache bulk 입력의 오류 12건 중 수정 전 11건 실패를 확인했다. 수정 후 0/0.5/1 정상 경계값과 보강 서비스 검사를 포함한 `test --tests '*Epss*Test' --tests '*Vdb*Test' --tests '*VulnerabilityEnrichmentServiceTest'` 48건 통과·실패/skip 0. Windows/Java 25, 로그 `build/roadmap-epss-paths-before.log`, `build/roadmap-epss-paths-after.log`. 커밋 제목 `fix: validate epss probabilities across collection paths`. 자체 합성 입력이며 새 외부 데이터/라이브러리와 UI 변경 없음. EPSS 실제 자료 재배포 조건은 부록 A의 미확인 상태를 유지한다. bulk 헤더/원 기준일, 중복 CVE 충돌, live 요청 ID 대조·페이지/부분 실패 상태 전파는 잔여다.

- **2026-09-11 EPSS bulk 근거 검증:** [FIRST 데이터 안내](https://www.first.org/epss/data)의 일별 CSV URL에서 앞 두 줄만 메모리로 읽어 `score_date:2026-09-10T12:00:22Z`와 `cve,epss,percentile` 헤더를 확인했다. 실제 점수 행을 fixture/배포 자료로 도입하지 않았다. 파서는 헤더와 3개 열을 검증하고, score_date의 날짜/offset timestamp를 완전히 파싱한다. 누락·손상·미래·서로 다른 기준일과 점수가 없는 데이터셋은 수집 실패이며 오늘 날짜로 대체하지 않는다. 현재 일별 수집 경로의 계약이며 날짜 주석이 없는 초기 EPSS v1 역사 파일 지원을 의미하지 않는다.
- **bulk 근거 회귀:** 날짜/헤더/빈 파일 오류 8건은 수정 전 모두 실패했다. 정상 날짜·UTC Z·offset timestamp의 원 기준일 보존과 기존 점수/보강 검사까지 `test --tests '*Epss*Test' --tests '*Vdb*Test' --tests '*VulnerabilityEnrichmentServiceTest'` 59건 통과·실패/skip 0. Windows/Java 25, 로그 `build/roadmap-epss-evidence-before.log`, `build/roadmap-epss-evidence-after.log`. 커밋 제목 `fix: require dated complete epss input`. 자체 합성 gzip 입력이며 새 외부 데이터/의존성 및 UI 변경 없음. FIRST 안내는 bulk CSV 사용 방식과 필드 의미의 근거이며 고객 재배포 조건의 미확인 상태는 유지한다. 중복 CVE 점수 충돌, 전체 데이터 건수/원천 서명 및 live 부분 실패 상태 전파는 잔여다.

- **2026-09-11 EPSS 중복 충돌/요청 대조:** live 결과는 요청한 CVE ID와 일치하는 행만 반영한다. 같은 CVE의 서로 다른 점수나 잘못된 점수가 있으면 이번 응답에서 해당 ID를 미확인으로 유지하며 나중 행으로 다시 확정하지 않는다. 동일한 점수의 중복은 하나로 합친다. bulk도 다른 중복 점수를 마지막 값으로 덮어쓰지 않고 수집 실패로 처리한다.
- **충돌 회귀:** 순서를 뒤집은 충돌·잘못된 값 뒤 정상 값/반대 순서·동일 중복과 미요청 ID 검사 8건 중 수정 전 7건 실패를 확인했다. mock HTTP 및 실제 임시 gzip/cache 경로와 기존 보강 테스트를 포함해 `test --tests '*Epss*Test' --tests '*VulnerabilityEnrichmentServiceTest'` 67건 통과·실패/skip 0. Windows/Java 25, 로그 `build/roadmap-epss-conflicts-before.log`, `build/roadmap-epss-conflicts-after.log`. 커밋 제목 `fix: preserve uncertainty for conflicting epss scores`. 자체 합성 입력, 외부 자료/의존성 및 UI 변경 없음. 오프라인 번들 반입의 중복 의미, live API 전체 실패 상태/점수 기준일 전파와 저장된 과거 점수 정정은 잔여다.

- **2026-09-11 EPSS 요청 누락 수정:** 보강 서비스는 전체 CVE 목록을 전달하지만 EpssClient가 첫 50개만 남기던 제한을 제거했다. 온라인은 요청 ID를 중복 제거한 뒤 50개 묶음으로 조회하며 한 묶음 실패가 다른 묶음의 정상 결과를 버리지 않는다. 오프라인은 HTTP 요청 크기 제한 없이 전체 ID를 저장소에 전달한다. 온라인 처리 중 interrupt가 있으면 추가 묶음을 시작하지 않고 이미 확인한 점수만 반환한다. 실패/미응답 점수를 0으로 만들지 않는다.
- **묶음 회귀:** 51개 ID의 온라인 정상·첫 묶음 실패·오프라인 전체 조회 3건은 수정 전 전부 실패했다. 수정 후 mock HTTP 및 snapshot adapter와 기존 보강 검사를 포함한 `test --tests '*Epss*Test' --tests '*VulnerabilityEnrichmentServiceTest'` 70건 통과·실패/skip 0. Windows/Java 25, 로그 `build/roadmap-epss-batches-before.log`, `build/roadmap-epss-batches-after.log`. 커밋 제목 `fix: query epss scores beyond the first batch`. 자체 합성 입력, 외부 데이터/라이브러리 및 UI 변경 없음. 실제 공급자 rate limit/전체 경과 예산과 요청별 timeout, 누락/실패 상태의 명시적 영속 전파는 잔여다.

### 33. 조회 캐시와 전체 advisory 로컬 판정 구분 — P1 · [지원 범위별 필수]

- 현재·대상: [AirgappedSnapshotService](src/main/java/com/salkcoding/oswl/service/snapshot/AirgappedSnapshotService.java)의 기존 조회 결과 export는 전체 원천 DB가 아니다.
- [ ] 수정: 캐시 팩에는 조회한 package/version 범위를, 데이터셋 팩에는 원본 affected range·비교 규칙·식별 metadata·지원 범위를 담는다. 첫 생태계/배포판에서 검증 후 확장하고 내부 package 목록 반출이 금지된 기관에는 범위 팩을 제공한다.
- 선행: 1~2·10~15·32번.
- DoD: 한 번도 조회하지 않은 취약/정상 버전을 로컬 데이터셋 범위 안에서 판정한다. 캐시 밖·팩 미지원은 UNKNOWN이며, wanted list 전송은 기관 정책에 따른다.

### 34. 번들 schema·전 파일·의미 검증 강화 — P0 · [코드 확인]

- 현재·대상: [AirgappedSnapshotService](src/main/java/com/salkcoding/oswl/service/snapshot/AirgappedSnapshotService.java)의 malformed meta→legacy, 누락 hash/OSV 필수 필드 처리 경로.
- [ ] 수정: formatVersion allowlist·필수 필드·파일 목록/digest/크기/count·중복/참조/range·고지 manifest를 검증한다. 누락 vulns와 빈 배열을 구분하고 legacy는 명시적 변환/종료 기간으로 이행한다.
- 선행: 2·12~13·32번.
- DoD: malformed meta/미래 schema/미등록 파일/누락 hash·필드/전부 거부된 REPLACE를 거부한다. 기존 정상 자료를 보존하고 실패 이유를 제공한다.

- **2026-09-10 취약점 목록 반입 검증:** OSV/GHSA/NVD 스냅샷의 누락·null·배열 아닌 vulns와 잘못된 목록 원소/공지 ID 누락을 가져오기 오류로 처리한다. 기존처럼 무시하거나 빈 목록으로 저장하지 않는다. 컴포넌트 identity 누락과 boolean 아닌 삭제 표시도 거부한다. 명시적 빈 배열, 미확인 상태 전용 레코드 및 정상 삭제 형식은 유지한다. 예외를 반입 트랜잭션 밖으로 전달해 실패 시 기존 데이터를 보존한다.
- **반입 회귀:** 수정 전 6개 오류 입력 모두 거부되지 않았다. 수정 후 실제 ZIP→Spring 서비스→H2 저장소 경로에서 세 소스의 잘못된 목록 거부/기존 payload 보존, 누락 목록 거부, 명시적 빈 목록·unresolved 레코드 허용을 검증했다. `.\gradlew.bat test --tests '*SnapshotImportTransactionTest' --tests '*CocoaPodsSnapshotTest' --tests '*Osv*Test'` 105건 통과·실패/skip 0. Windows/Java 25, 로그 `build/roadmap-snapshot-vulns-before.log`, `build/roadmap-snapshot-vulns-after.log`. 커밋 제목 `fix: reject malformed vulnerability snapshot records`. 자체 합성 자료이며 외부 데이터/라이브러리 및 UI 변경 없음. 전체 manifest/schema/고지 검증과 다른 소스의 의미 검증, 이미 저장된 손상 데이터 정정은 잔여다.

- **2026-09-10 저장된 취약점 읽기 검증:** OSV/GHSA/NVD 저장 payload 읽기를 공통 경로로 연결하고 null 목록, null 원소와 공지 ID가 없는 원소를 포함하는 레코드는 정상 조회 결과에서 제외한다. 명시적인 빈 목록과 구별하며 OSV 클라이언트에서는 해당 키가 미확인으로 남고 다른 정상 키는 계속 처리한다. 기존 DB 값을 임의로 고치거나 삭제하지 않는다. 손상 목록 안의 정상 원소를 별도 finding으로 복구하고 미완료 근거와 함께 전달하는 세밀한 복구는 잔여다.
- **읽기 회귀와 전체 빌드:** 직접 H2에 저장한 손상 payload 5종 중 수정 전 3종 실패를 확인했다. 세 소스 조회 및 OSV 오프라인 client의 미확인/정상 빈 목록 구별을 포함한 `test --tests '*SnapshotImportTransactionTest' --tests '*CocoaPodsSnapshotTest' --tests '*Osv*Test'` 110건 통과·실패/skip 0. 이후 `build verifyProdJar` 성공: 전체 2,686건 중 2,677건 통과, 기존 환경 의존 skip 9건, 실패/error 0. 운영 JAR에 local 전용 controller가 없는 것도 확인했다. Windows/Java 25, 로그 `build/roadmap-snapshot-read-before.log`, `build/roadmap-snapshot-read-after.log`, `build/roadmap-snapshot-read-build.log`. 커밋 제목 `fix: validate stored vulnerability lists before lookup`. 합성 입력으로 외부 자료/의존성 및 UI 변경 없음. 전체 목표 완료나 실제 공급자 통합 검증을 의미하지 않는다.

- **2026-09-10 메타데이터 downgrade/해시 누락 차단:** meta.json 파싱 실패나 객체 아닌 root는 구형 형식으로 처리하지 않고 반입을 거부한다. 명시적 formatVersion은 정수 1 또는 현재 2만 허용하며 null/문자열/소수/0/미래 버전은 거부한다. v2는 files 객체와 모든 실제 반입 데이터 파일의 64자리 SHA-256을 요구하고 기존 checksum 대조를 수행한다. 메타데이터 부재/버전 미지정 구형 객체 및 명시적 v1은 기존 호환 경로를 유지하므로 별도 legacy 이행/종료 정책은 아직 필요하다.
- **메타데이터 회귀:** 잘못된 JSON/root/version과 manifest/해시 누락 11건은 수정 전 모두 실패했다. 수정 후 실제 ZIP→서비스 경로에서 반입 거부와 기존 source 보존을 확인했다. `test --tests '*Snapshot*Test' --tests '*CocoaPodsSnapshotTest' --tests '*Vdb*Test'` 32건 중 31건 통과, 기존 대용량 환경 의존 skip 1건, 실패/error 0. Windows/Java 25, 로그 `build/roadmap-snapshot-meta-before.log`, `build/roadmap-snapshot-meta-after.log`. 커밋 제목 `fix: prevent snapshot metadata integrity downgrades`. 자체 합성 자료, 외부 의존성/데이터 및 UI 변경 없음. 미등록 ZIP 항목, 선언만 있고 없는 파일, lines/records 대조, 서명 신뢰 및 고지 manifest 검증은 잔여다.

- **2026-09-10 v2 파일 목록 대조:** ZIP 원본 파일 이름을 staging 동안 보존해 v2의 미등록 파일 및 하위 경로 파일을 거부한다. manifest 파일 집합과 실제 반입 데이터 파일 집합을 동일하게 요구하므로 선언만 있고 없는 파일도 오류다. 일부 소스만 담는 delta 번들은 실제 포함 파일만 선언해야 한다. 구형 번들의 기존 경로 호환 동작은 유지하며 빈 디렉터리는 데이터 파일로 세지 않는다.
- **파일 목록 회귀:** 누락 선언 파일·미등록 파일·하위 경로 파일의 ZIP→서비스 반입 3건은 수정 전 모두 실패했다. 수정 후 기존 source 보존과 거부를 확인했다. `test --tests '*Snapshot*Test' --tests '*CocoaPodsSnapshotTest' --tests '*Vdb*Test'` 35건 중 34건 통과, 기존 대용량 환경 의존 skip 1건, 실패/error 0. Windows/Java 25, 로그 `build/roadmap-snapshot-files-before.log`, `build/roadmap-snapshot-files-after.log`. 커밋 제목 `fix: enforce snapshot manifest file inventory`. 자체 합성 입력이며 외부 자료/라이브러리와 UI 변경 없음. 파일별 lines/records 대조, ZIP central directory 완전성, 서명과 고지 manifest는 잔여다.

- **2026-09-10 v2 행 수 검증:** manifest의 파일별 lines를 필수 비음수 정수(int 범위)로 검증한다. 기존 행 길이 사전 검사에서 물리적 행 수를 함께 세어 선언과 다르면 쓰기 트랜잭션 전에 거부한다. 끝의 개행은 추가 빈 행으로 세지 않으며 빈 파일은 0이다. 기존 앱/CLI 생성기는 이미 lines를 출력한다. 해시만 기록하던 정상 테스트 fixture에는 실제 행 수를 추가했으며 파일 목록/해시 오류 테스트가 여전히 해당 오류까지 도달하도록 보완했다.
- **행 수 회귀:** null·음수·소수·문자열·정수 overflow·실제보다 작거나 큰 값 7건은 수정 전 전부 실패했다. 수정 후 실제 ZIP 반입 거부와 기존 source 보존을 확인했고 정상 export/import 검사도 통과했다. `test --tests '*Snapshot*Test' --tests '*CocoaPodsSnapshotTest' --tests '*Vdb*Test'` 42건 중 41건 통과, 기존 대용량 환경 의존 skip 1건, 실패/error 0. Windows/Java 25, 로그 `build/roadmap-snapshot-lines-before.log`, `build/roadmap-snapshot-lines-after.log`. 커밋 제목 `fix: validate snapshot file line counts`. 자체 합성 자료이며 외부 의존성/데이터 및 UI 변경 없음. source.records는 delta의 전체 상태 건수와 파일 행 수를 구별해야 하며, 중복/삭제/부분 소스의 레코드 수 의미 검증은 잔여다.

- **2026-09-10 EPSS 값 검증:** [FIRST 공식 EPSS 설명](https://www.first.org/epss/)(확인 2026-09-10)의 0~1 확률 계약에 따라 반입 score를 유한 숫자·범위 내 값으로 제한한다. 점수 누락/형식 오류, identity 누락과 잘못된 삭제 표시도 반입 실패로 전달해 기존 source를 보존한다. 저장된 NaN/Infinity/범위 밖 값은 조회에서 제외하며 0으로 바꾸지 않는다. 정상 경계값 0/1은 유지한다.
- **EPSS 회귀 및 누적 빌드:** 실제 ZIP/H2 반입과 직접 저장 payload 조회의 오류 11건은 수정 전 전부 실패했다. 수정 후 정상 0/0.5/1 왕복 검사를 포함한 `test --tests '*Snapshot*Test' --tests '*CocoaPodsSnapshotTest' --tests '*Vdb*Test'` 67건 중 66건 통과, 기존 대용량 skip 1건, 실패/error 0. 이후 `build verifyProdJar` 성공: 전체 2,732건 중 2,723건 통과·기존 환경 의존 skip 9건·실패/error 0, 운영 JAR local controller 제외 확인. Windows/Java 25, 로그 `build/roadmap-snapshot-epss-before.log`, `build/roadmap-snapshot-epss-after.log`, `build/roadmap-snapshot-integrity-build.log`. 커밋 제목 `fix: validate offline epss probabilities`. 자체 합성 입력이며 외부 데이터/라이브러리·UI 변경 없음. 공식 설명은 점수 의미의 근거이고 데이터 재배포 허가를 뜻하지 않으며 부록 A의 이용조건 미확인 상태를 유지한다. live/CLI EPSS 파서 및 CVE identity 전체 문법 검증은 잔여다.

### 35. 오프라인 서명·신뢰 루트·이전 세대 방어 — P0 · [설계]

- 현재·대상: checksum은 파일과 hash를 함께 바꾼 위조를 막지 못한다. 최초 trust root와 signer scope가 필요하다.
- [ ] 수정: manifest 서명과 사전 배포 trust root, signer/builder/데이터 범위·키 폐기/교체·단조 revision·만료/미래 시각을 검증한다. 사내 키 또는 필요한 검증 재료를 동봉한 bundle을 지원하고 첫 반입 파일의 키를 자동 신뢰하지 않는다.
- 선행: 1~2·34번.
- DoD: 위조/미신뢰·폐기 signer/rollback을 거부하고 외부 OIDC·투명성 로그에 접속하지 않아도 검증한다. 서명과 데이터 이용권은 별도 확인이다. [TUF 참고](https://theupdateframework.github.io/specification/latest/), [Sigstore bundle](https://docs.sigstore.dev/about/bundle/).

### 36. 재포장·부분 갱신으로 freshness가 바뀌지 않게 수정 — P0 · [코드 확인]

- **2026-09-11 KEV 동일 기준일 membership 충돌:** 같은 dateReleased에서 CVE 구성원이 달라진 온라인 응답은 이전 목록을 교체하지 않고 충돌로 보존한다. 단순 배열 순서 변경은 허용한다. 충돌 이후 원래 목록을 다시 받아도 같은 기준일에서는 absence를 확정하지 않으며 기존 positive 기록은 유지한다. 이 상태는 메모리 내 배포일에 연결되며 프로세스 재시작 후 보존·서명·원문 전체 필드 충돌은 여전히 잔여다.
- **검증:** 변경/순서 변경 2건 중 수정 전 membership 변경 1건 실패를 재현했다. 수정 후 원래 목록 재수신까지 포함해 Windows/Java 25의 `test --tests '*KevLookupStatusTest' --tests '*VulnerabilityEnrichmentServiceTest' --tests '*ComponentDetailServiceTest'` 108건 통과·실패/오류/skip 0. 로그 `build/roadmap-kev-release-conflict-before.log`, `build/roadmap-kev-release-conflict-after.log`. 커밋 제목 `fix: preserve conflicting kev membership for a release`. 자체 합성 응답이며 새 외부 자료/라이브러리·UI 변경 없음. 더 나중 배포일에 대한 내용 신뢰성은 별도 원천 검증 과제로 남는다.

- **2026-09-11 KEV 배포 기준일과 메모리 내 rollback 방어:** 온라인 목록 상태에 실제 `dateReleased`를 다운로드 시점과 별도로 보존한다. 미등재를 확정하려면 기존 1일 로드 유효기간과 배포일의 경고 임계값(공통 설정 `oswl.airgapped.staleness-warn-days`, 기본 7일)을 모두 만족해야 한다. 배포일은 UTC 날짜로 비교하며 오래된 positive 기록은 보존한다. 이미 수용한 배포일보다 이전 응답은 목록을 교체하지 않고 조회 미확인으로 남긴다. 동시 refresh는 직렬화하고 조회는 불변 상태를 읽는다. 이 방어는 프로세스 메모리 범위이며 재시작 후 high-water mark·동일 배포일의 상충 내용·서명 검증은 잔여다.
- **검증:** 오늘/7일/8일/40일 배포 목록 4건 중 수정 전 2건 실패했다. 이전 배포일 응답의 membership 덮어쓰기 거부도 추가했고, 기존 정상 metadata fixture는 현재 배포일을 제공하도록 보완했다. Windows/Java 25의 `test --tests '*KevLookupStatusTest' --tests '*VulnerabilityEnrichmentServiceTest' --tests '*ComponentDetailServiceTest'` 106건 통과·실패/오류/skip 0. 로그 `build/roadmap-kev-release-date-before.log`, `build/roadmap-kev-release-date-after.log`. 커밋 제목 `fix: evaluate kev coverage using the catalog release date`. 앞서 확인한 공식 dateReleased 계약에서 도출한 자체 합성 입력이며 새로운 외부 데이터/코드/라이브러리·UI 변경은 없다.

- **2026-09-11 KEV 온라인 metadata 검증:** [CISA 공식 JSON schema](https://raw.githubusercontent.com/cisagov/kev-data/main/known_exploited_vulnerabilities_schema.json)(확인 2026-09-11)에 따라 전체 count와 실제 배열 길이, 비어 있지 않은 catalogVersion, 파싱 가능한 미래가 아닌 dateReleased를 확인한 뒤 목록을 교체한다. CVE ID는 스키마의 4~19자리 순번 범위를 따르고 중복 ID도 불완전 응답으로 남긴다. 잘못된 metadata는 기존 positive 목록을 유지하고 absence를 확정하지 않는다. dateReleased의 오래됨/이전 revision rollback·원문 서명까지 확인한 것은 아니며 해당 검증은 잔여다.
- **검증·권리:** 새 metadata 9건 중 수정 전 8건 실패, 수정 후 Windows/Java 25에서 `test --tests '*KevLookupStatusTest' --tests '*VulnerabilityEnrichmentServiceTest' --tests '*ComponentDetailServiceTest'` 101건 통과·실패/오류/skip 0. 로그 `build/roadmap-kev-metadata-before.log`, `build/roadmap-kev-metadata-after.log`. 커밋 제목 `fix: validate kev catalog metadata before replacing evidence`. [공식 저장소 LICENSE](https://raw.githubusercontent.com/cisagov/kev-data/main/LICENSE)는 KEV database를 CC0 1.0으로 제공하지만 제3자 링크의 자료와 CISA 로고/DHS Seal·보증은 포함하지 않는다. 이번에는 공식 형식에 따른 자체 합성 응답만 사용했고 원문 데이터·로고·제3자 자료를 추가 배포하지 않았다. UI 변경 없음.

- **2026-09-11 KEV nullable 누적 회귀:** `0efe6df`의 조회 미확인 보존 변경을 포함해 Windows/Java 25의 `.\gradlew.bat build verifyProdJar` 성공. 전체 2,959건 중 2,950건 통과·기존 환경 의존 skip 9건·실패/오류 0, 운영 JAR local controller 제외 검사 통과. 로그 `build/roadmap-kev-nullability-build.log`. 커밋 제목 `docs: record kev uncertainty regression build`. 주요 `CveDto`와 scan archive DTO의 KEV 필드가 nullable Boolean인 것도 코드로 확인했다. 실제 화면의 null 표시, 실환경 공급자 완전성·서명, PostgreSQL 검증은 계속 잔여이며 이번 빌드로 대체하지 않는다.

- **2026-09-11 KEV 미확인 저장 경계:** 기존 nullable `Cve.kevListed`까지 null을 전달하도록 KEV 조회/`setThreatIntel`/스캔 보강/구성요소 상세 갱신을 연결했다. `listingStatus`는 아직 로드하지 않았거나 마지막 성공 로드가 1일을 넘겼거나 offline source 기준일이 미확인이면 미등재를 false로 확정하지 않는다. 기존 목록에서 확인한 등재 기록은 보존한다. 잘못된 목록 항목이 있는 온라인 응답은 기존 목록을 교체하지 않으며 조회 근거는 미완료로 둔다. 목록과 로드 시점은 한 상태로 교체해 독자가 서로 다른 세대를 조합하지 않도록 했다. 기존 boolean `isListed`는 호환용으로 남겼고 두 실제 저장 소비자는 nullable 조회를 사용한다. DB 열은 이미 nullable이므로 스키마 변경은 없다.
- **검증:** 신규 API 연결 후 미로드/만료/정상·오래된 offline 목록/잘못된 HTTP 응답/기존 positive 보존/null 엔티티 저장 검사를 추가했다. Windows/Java 25에서 `test --tests '*KevLookupStatusTest' --tests '*VulnerabilityEnrichmentServiceTest' --tests '*ComponentDetailServiceTest'` 92건 통과·실패/오류/skip 0. 로그 `build/roadmap-kev-status-after.log`. 커밋 제목 `fix: preserve unknown kev membership through threat intel updates`. 자체 합성 입력이며 새 외부 자료/라이브러리·화면 코드 변경은 없다. 실제 feed의 dateReleased/count 완전성·서명·행별 원출처 시점, 철회된 과거 positive의 별도 이력과 UI에서 null 표시의 종단 검증은 잔여다. 로드 시점이 원천 데이터 기준일을 증명한다고 주장하지 않는다.

- **2026-09-11 deps.dev/EPSS 누적 검증:** `4b12898`까지 포함해 Windows/Java 25에서 `.\gradlew.bat build verifyProdJar` 성공. 전체 2,953건 중 2,944건 통과·기존 환경 의존 skip 9건·실패/오류 0, 운영 JAR local controller 제외 검사 통과. 로그 `build/roadmap-metadata-freshness-build.log`. 커밋 제목 `docs: record offline metadata freshness regression build`. 별도 UI/실제 PostgreSQL/라이브 공급자 검증은 수행하지 않았다. KEV 미확인과 미등재의 구분, deps.dev 공지 상세 기준일, source별 지원 계약은 잔여다.

- **2026-09-11 EPSS 오프라인 freshness 연결:** EPSS source의 기준일이 미상·미래·경고 임계값 초과면 현재 점수 조회에서 제외한다. 0으로 대체하지 않으며 snapshot 원본은 보존한다. 기존 스캔 `applyThreatIntel` 경로가 반환 map에서 누락된 점수를 null로 전달함을 코드로 확인했다. 원천별 날짜·모델 revision의 화면 전달과 모든 재평가 경로의 종단 검증은 잔여다.
- **회귀 검증:** 실제 H2 metadata/entry→EPSS offline client의 오늘/7일/8일/40일/미래/날짜 미상 6건 중 수정 전 4건 실패했다. 수정 후 현재 점수 제외 및 저장 원본 0.25 보존을 확인했다. Windows/Java 25에서 `test --tests '*SnapshotImportTransactionTest' --tests '*Epss*Test' --tests '*VulnerabilityEnrichmentServiceTest'` 195건 통과·실패/오류/skip 0. 로그 `build/roadmap-epss-freshness-before.log`, `build/roadmap-epss-freshness-after.log`. 커밋 제목 `fix: withhold stale offline epss scores`. 자체 합성 입력이며 새로운 외부 점수 자료/라이브러리·UI 변경은 없다. FIRST 데이터의 실제 이용·재배포 조건은 기존 부록의 미해결 범위를 유지한다.

- **2026-09-11 deps.dev 버전 snapshot freshness 연결:** `depsdev-version`의 저장 기준일을 실제 offline GetVersion 결과에 반영했다. 미상·미래·경고 임계값 초과는 `resolved=false`로 유지하고 latest/default/deprecated/Scorecard를 현재 정보로 반환하지 않는다. 기존 공지 ID와 라이선스 기록은 보존한다. 이는 기존 `resolved` 소비자가 버전 메타데이터의 갱신을 건너뛰는 계약과 연결된다. `depsdev-advisory` 상세의 날짜·이력 모델과 라이선스 기준일의 별도 표시는 여전히 잔여다.
- **회귀 검증:** 실제 H2 metadata/entry→deps.dev offline client의 오늘/7일/8일/40일/미래/날짜 미상 6건 중 수정 전 4건 실패했다. 수정 후 resolved/default/latest와 공지 ID·라이선스 보존을 확인했다. Windows/Java 25에서 `test --tests '*SnapshotImportTransactionTest' --tests '*DepsDev*Test' --tests '*VulnerabilityEnrichmentServiceTest'` 156건 통과·실패/오류/skip 0. 로그 `build/roadmap-depsdev-freshness-before.log`, `build/roadmap-depsdev-freshness-after.log`. 커밋 제목 `fix: withhold stale offline dependency version status`. 자체 합성 자료이며 외부 자료/라이브러리·UI 변경 없음. 실제 데이터의 이용·재배포 조건 확인을 이 검사로 대체하지 않는다.

- **2026-09-11 누적 빌드와 CocoaPods 종단 검증:** 최초 전체 검사에서 기존 CocoaPods 종단 검사 1건이 실패했다. 정상 OSV 조회를 검증하는 합성 bundle에 오늘 기준일을 추가하고, GHSA 근거가 없는 bundle은 `OSV=RESOLVED`/`GITHUB_ADVISORY=UNAVAILABLE` 및 전체 분석 미완료를 확인하도록 검사했다. 취약점 ID·심각도·CVSS·게이트 차단·누락 Specs의 미분석 기대값은 유지했다. scan-derived 재내보내기는 기준일이 null인 만큼 조회 미확인과 finding 보존을 검증한다. 임의로 실제 source 날짜를 보충한 것이 아니라 자체 fixture의 명시적 입력 조건을 보완한 것이다.
- **최종 검증:** Windows/Java 25의 `.\gradlew.bat build verifyProdJar` 성공. 전체 2,941건 중 2,932건 통과·기존 환경 의존 skip 9건·실패/오류 0, 운영 JAR local controller 제외 검사 통과. 로그 `build/roadmap-offline-freshness-build.log`, `build/roadmap-offline-freshness-build-after.log`(중간 실패), `build/roadmap-cocoapods-freshness-after.log`, `build/roadmap-offline-freshness-build-final.log`. 커밋 제목 `test: verify partial offline coverage through cocoapods scans`. 외부 자료/라이브러리·UI 변경 없음. 원천별 필수/선택 지원 계약, UI 종단 검증과 실제 PostgreSQL 검증은 계속 잔여다.

- **2026-09-11 NVD 오프라인 freshness 연결:** NVD source adapter의 망분리 조기 반환에도 공통 source 기준일 검사를 적용했다. 날짜 미상·미래·경고 임계값 초과면 `lookupFailed`로 전달하고 과거 finding과 저장 CPE는 보존한다. 오프라인에서 CPE 추론이나 네트워크 조회로 보완하지 않는다. NVD client의 누락 key 설명도 조회 근거 없음으로 바로잡았다. deps.dev 등 나머지 source와 과거 finding의 별도 후보/이력 모델은 계속 잔여다.
- **회귀 검증:** 실제 H2 metadata/entry→NVD client→source adapter에서 오늘/7일/8일/40일/미래/날짜 미상 6건 중 수정 전 4건 실패했다. 수정 후 빈 결과의 미완료, finding 보존 및 CPE 추론 미호출을 확인했다. Windows/Java 25에서 `test --tests '*SnapshotImportTransactionTest' --tests '*Nvd*Test' --tests '*VulnerabilityEnrichmentServiceTest'` 145건 통과·실패/오류/skip 0. 로그 `build/roadmap-nvd-freshness-before.log`, `build/roadmap-nvd-freshness-after.log`. 커밋 제목 `fix: preserve stale nvd snapshot coverage uncertainty`. 자체 합성 자료이며 새 외부 자료/라이브러리·UI 변경 없음. 실제 NVD 데이터의 재배포 권한을 이 검사로 승인한 것은 아니다.

- **2026-09-11 GHSA 오프라인 freshness 연결:** GHSA source의 날짜 누락·미래·경고 임계값 초과도 기존 공통 검사로 확인한다. offline component-key 조회는 과거 finding/충돌 후보를 보존하되 fixed를 보류하고, 단일 패키지 조회 단계는 기존 incomplete 예외 계약을 통해 source adapter의 `lookupFailed`로 전달한다. 빈 snapshot 결과도 완료된 현재 조회로 승격하지 않는다. 정상 날짜 결과와 온라인 경로는 유지한다. NVD/deps.dev 등 나머지 source와 과거 finding의 별도 상태 모델은 잔여다.
- **회귀 검증:** 실제 H2 metadata/entry→GHSA client→source adapter의 오늘/7일/8일/40일/미래/날짜 미상 6건 중 수정 전 4건 실패했다. 수정 후 빈 결과의 미완료, finding 보존, fixed 보류를 확인했다. Windows/Java 25에서 `test --tests '*SnapshotImportTransactionTest' --tests '*GitHubAdvisoryRangeTest' --tests '*FixConflictPersistenceTest' --tests '*VulnerabilityEnrichmentServiceTest'` 181건 통과·실패/오류/skip 0. 로그 `build/roadmap-ghsa-freshness-before.log`, `build/roadmap-ghsa-freshness-after.log`. 커밋 제목 `fix: propagate stale github snapshot lookup uncertainty`. 자체 합성 자료이며 외부 데이터/라이브러리·UI 변경 없음. UI 종단 검증을 완료한 것은 아니다.

- **2026-09-11 OSV 오프라인 조회에 freshness 연결:** source별 저장 기준일을 확인하는 공통 service 검사를 추가하고 OSV snapshot 조회에 연결했다. 기준일 누락·미래 날짜·`oswl.airgapped.staleness-warn-days` 초과(기본 7일)는 `resolved=false`로 반환한다. 빈 결과를 정상 완료로 확정하지 않으며 기존 취약점 기록과 충돌 후보는 보존하고 수정 버전 제안은 보류한다. 경고 임계값 이하의 정상 날짜는 기존 결과를 유지한다. 잘못된 음수 임계값은 신뢰 가능한 freshness로 인정하지 않는다. GHSA/NVD/deps.dev 등 다른 source의 실제 조회 연결과 과거 finding의 별도 이력/후보 모델은 계속 잔여다.
- **회귀 검증:** 실제 저장 metadata→OSV offline client 경로의 오늘/7일/8일/40일/미래/날짜 미상 6건 중 수정 전 4건 실패했다. 수정 후 취약점 없는 결과의 미확인, 기존 finding 보존 및 fixed 보류를 확인했다. 기존 손상 payload 검사는 정상 이웃 결과를 검증할 수 있도록 오늘 날짜의 source metadata를 제공하고 기대 판정은 유지했다. Windows/Java 25에서 `test --tests '*SnapshotImportTransactionTest' --tests '*Osv*Test' --tests '*FixConflictPersistenceTest' --tests '*VulnerabilityEnrichmentServiceTest'` 279건 통과·실패/오류/skip 0. 로그 `build/roadmap-osv-freshness-before.log`, `build/roadmap-osv-freshness-after.log`. 커밋 제목 `fix: preserve uncertainty for stale offline osv results`. 자체 합성 데이터이며 외부 자료/라이브러리·UI 변경 없음. legacy 및 scan-derived 날짜 미상 bundle의 조회도 이제 미확인으로 표시되며 builtAt/importedAt을 원천 날짜로 대체하지 않는다.

- **2026-09-11 저장된 미래 기준일의 조회 검증:** 번들 import 검증 외에 공통 `oldestSourceAsOf()` 조회에서도 미래 날짜를 미확인으로 처리한다. 기존 저장 데이터나 시스템 시각 변경으로 미래 기준일이 생겼을 때 readiness/report의 공통 날짜 근거가 정상 freshness로 쓰이지 않도록 한다. 정상 날짜의 다른 source가 있더라도 최솟값 계산으로 잘못된 source를 숨기지 않는다. 저장된 원문 날짜를 임의 수정하지 않는다.
- **회귀 검증:** 미래 source 단독/정상 source 혼합 2건 모두 수정 전 실패했다. 수정 후 실제 H2 저장→service 조회→readiness DOWN을 확인했다. Windows/Java 25에서 `test --tests '*SnapshotImportTransactionTest' --tests '*SbomExportServiceTest' --tests '*SarifExportServiceTest'` 70건 통과·실패/오류/skip 0. 로그 `build/roadmap-stored-freshness-before.log`, `build/roadmap-stored-freshness-after.log`. 커밋 제목 `fix: reject future stored snapshot freshness dates`. 자체 합성 데이터이며 새 외부 자료/라이브러리·UI 변경은 없다. 원천별 실제 갱신 시점 증거와 모든 API/UI에서의 노후화 판정은 계속 잔여다.

- 현재·대상: 일반 export의 asOf 현재 날짜 설정과 혼합 UPSERT 데이터. CocoaPods의 기존 원 날짜 보존은 유지한다.
- [ ] 수정: 원문 published/modified, source revision·검증된 동기화 checkpoint, collected/built/imported/evaluated 시각을 분리한다. record/source partition별 provenance를 보존하고 stale/시계 이상을 정책에 전달한다.
- 선행: 2·10·32·34번.
- DoD: 오래된 자료 재포장/일부 신규 row 추가가 기존 자료를 최신으로 바꾸지 않는다. 반대로 오래된 advisory라도 정상 전체 동기화를 확인했다면 단순 발표일 때문에 stale로 오인하지 않는다.
- **2026-09-10 캐시 기준일 보완:** `HttpCache`가 새 응답에 Last-Modified가 없을 때 이전 응답의 sidecar 날짜를 재사용하던 문제를 수정했다. 새 캐시는 date/unknown과 본문 SHA-256을 함께 기록하고, 재조회 시 본문 불일치의 날짜를 UNKNOWN으로 처리한다. 본문·metadata를 각각 임시 파일에 완성하고 atomic move하며 metadata를 먼저 공개한다. 두 파일의 혼합/중단 구간은 최신 날짜를 추측하지 않는다. digest는 전송 서명/권리 검증이 아니며 기존 한 줄 날짜 sidecar는 호환 유지하므로 legacy 데이터의 출처 결합 검증을 보장하지 않는다. atomic move 미지원 파일시스템에서는 수집 실패로 처리한다.
- **캐시 회귀:** 자체 loopback HTTP 응답으로 무날짜 갱신→offline 재조회, 날짜 있는 갱신, 본문 교체 후 날짜 불일치, HTTP 503 시 기존 본문/날짜 보존을 검증했다. 수정 전 4건 중 2건 실패를 확인했고 수정 후 4건 및 실제 bulk/CLI 검사 12건이 통과했다. 명령 `.\gradlew.bat test --tests '*HttpCacheConsistencyTest' --tests '*OsvBulkInputIntegrityTest'`, 로그 `build/roadmap-cache-before.log`, `build/roadmap-cache-after.log`. 커밋 제목 `fix: bind cached source dates to response content`. 외부 자료/의존성 도입과 UI 변경은 없다. export/import DB row의 원 기준일 전파, source partition/checkpoint 및 실제 프로세스 강제 종료/다중 프로세스 검증은 잔여다.

- **누적 빌드 검증:** Windows/Java 25의 `.\gradlew.bat build verifyProdJar` 성공. 전체 1,137건 중 1,128건 통과·9건 skip·실패/오류 0, 운영 JAR local controller 제외 검사 통과. 로그 `build/roadmap-cache-build.log`. skip은 외부 저장소/모델/실환경/대형 heap 조건이 필요한 기존 검사이며 완료 근거로 계산하지 않는다.

- **2026-09-10 MERGE 기준일 보존:** 부분 갱신 전에 기존 데이터가 있는 소스의 기준일을 확보한다. 새 번들을 MERGE할 때 이전·새 기준일 중 오래된 날짜를 소스 기준일로 저장하고 어느 한쪽이 미확인이면 null을 유지한다. 모든 잔여 행이 갱신됐다는 증거가 없으므로 일부 행의 최신 날짜로 소스 전체를 최신화하지 않는다. REPLACE는 새 소스 기준일을 적용한다. 전체 기준일 집계도 날짜 미확인인 반입 소스가 있으면 null로 남기며 헬스 응답은 미반입뿐 아니라 일부 기준일 누락도 설명한다.
- **부분 갱신 회귀:** 오래된/미확인/더 이른 날짜의 MERGE와 REPLACE 5건 중 수정 전 2건 실패를 확인했다. 실제 H2 데이터 보존·교체 및 다른 날짜 있는 소스와의 전체 기준일 집계까지 검사했다. `test --tests '*Snapshot*Test' --tests '*CocoaPodsSnapshotTest' --tests '*Vdb*Test'` 47건 중 46건 통과, 기존 대용량 환경 의존 skip 1건, 실패/error 0. 헬스 설명 보완 후 compileJava도 성공. Windows/Java 25, 로그 `build/roadmap-snapshot-merge-date-before.log`, `build/roadmap-snapshot-merge-date-after.log`, `build/roadmap-snapshot-merge-date-compile.log`. 커밋 제목 `fix: preserve conservative freshness across snapshot merges`. 자체 합성 자료, 외부 데이터/의존성 및 화면 코드 변경 없음. 행별 revision/기준일과 검증된 동기화 시점, 동시 반입 세대 잠금, export 원 날짜 전파는 잔여다.

- **2026-09-10 스캔 결과 재포장 기준일:** 일반 export가 스캔 결과를 포장하면서 각 source.asOf를 현재 날짜로 지정하던 로직을 제거했다. scan-derived 행에는 검증된 원천 기준일이 없으므로 asOf를 미확인으로 내보낸다. 직접 저장 원문을 반출하는 CocoaPods Specs의 기존 원 기준일/출처 보존은 유지한다. builtAt은 포장 시점으로 계속 기록하며 데이터 기준일과 혼동하지 않는다. source metadata만으로 개별 스캔 결과의 원천 날짜를 추정해 붙이지 않는다.
- **재포장 회귀:** 오프라인 Podfile 분석→취약점 보강→export→재반입의 기존 실제 서비스/H2 검사에 원천 날짜 미확인 보존 단언을 추가해 수정 전 실패를 확인했다. 수정 후 취약점 결과 유지와 함께 통과했고 CocoaPods 날짜 보존 검사도 통과했다. `test --tests '*Snapshot*Test' --tests '*CocoaPodsSnapshotTest' --tests '*Osv*Test'` 137건 중 136건 통과, 기존 대용량 환경 의존 skip 1건, 실패/error 0. Windows/Java 25, 로그 `build/roadmap-snapshot-export-date-before.log`, `build/roadmap-snapshot-export-date-after.log`. 커밋 제목 `fix: avoid refreshing source dates when exporting scans`. 자체 합성 데이터, 외부 자료/라이브러리 및 화면 코드 변경 없음. 일반 스캔 행의 원천 revision/기준일 영속화와 온라인 데이터셋의 검증된 동기화 시점 전파는 잔여다. 재반입 후 freshness가 UNKNOWN/DOWN인 것은 날짜 근거가 없음을 반영한다.

- **2026-09-10 source 기준일 검증:** 명시한 source.asOf는 비어 있지 않은 날짜 문자열로 파싱하며 서버의 오늘 날짜 이후면 반입을 거부한다. 잘못된 날짜를 조용히 null로 바꾸던 처리를 제거했다. 생략/null은 미확인으로 계속 허용하며, 패키징 시점 builtAt으로 대체하지 않는다. 서버 날짜를 기준으로 하므로 운영 시계가 올바르다는 전제가 필요하다.
- **날짜 반입 회귀:** 형식 오류·존재하지 않는 날짜·미래 날짜·숫자·객체·빈 문자열 6건은 수정 전 전부 실패했다. 수정 후 ZIP 반입 거부와 기존 source 보존을 확인했고 정상 과거 기준일 및 날짜 미확인 검사를 함께 실행했다. `test --tests '*Snapshot*Test' --tests '*CocoaPodsSnapshotTest' --tests '*Vdb*Test'` 53건 중 52건 통과, 기존 대용량 환경 의존 skip 1건, 실패/error 0. Windows/Java 25, 로그 `build/roadmap-snapshot-date-validation-before.log`, `build/roadmap-snapshot-date-validation-after.log`. 커밋 제목 `fix: reject invalid snapshot source dates`. 자체 합성 입력이며 외부 자료/라이브러리 및 UI 변경 없음. source 날짜의 실제 원천 검증과 서명, 이전 세대 rollback 방어 및 행별 기준일 영속화는 잔여다.

### 37. staging 활성화·세대 고정·실패 복구 — P0 · [설계]

- 현재·대상: 기존 REPLACE/MERGE/upsert와 reader가 사용하는 데이터 세대의 트랜잭션 경계.
- [ ] 수정: 검역 staging→전체 검증→영향 preview→원자적 활성 pointer 전환으로 구성한다. scan은 한 revision을 고정하고 import 중단·OOM·강제 종료·실패에서 이전 세대를 유지한다. 비상 rollback은 승인/감사/degraded 상태로 처리한다.
- 선행: 16·32·34~36번.
- DoD: 부분 신세대/구세대 혼합 판정과 실패한 REPLACE의 정상 데이터 삭제가 없다. 재시작 후 활성 세대/잔여 staging·신뢰 revision이 일관되고 복구 가능하다.

### 38. full·delta·철회·인벤토리 재평가 — P1 · [설계]

- 현재·대상: 기존 연속 모니터링과 저장 inventory를 활용한다. 매 갱신마다 전체 소스를 다시 clone할 필요는 없다.
- [ ] 수정: delta base revision/digest·tombstone 사유·순서를 검증하고 주기적 full resync를 지원한다. 패키지→프로젝트 역색인으로 새 advisory/KEV/VEX/철회 영향을 재평가하며 원래 scan은 유지한다. 중복 알림·retry·backlog를 관리한다.
- 선행: 13·16·32~37번.
- DoD: 다른 base delta를 거부하고 한 원천 삭제로 타 원천 발견을 전역 삭제하지 않는다. 신규 KEV에 영향 범위가 없으면 UNKNOWN으로 남긴다. 정기/긴급 반입·재평가 지연을 분리 측정한다.

### 39. 감사 첫 행·동시 기록·retention checkpoint — P0 · [코드 확인/재현 필요]

- 현재·대상: [AuditLogIntegrityService](src/main/java/com/salkcoding/oswl/auth/service/AuditLogIntegrityService.java), [AuditLogService](src/main/java/com/salkcoding/oswl/auth/service/AuditLogService.java)의 첫 해시 행과 보존기간 삭제 경계.
- [ ] 수정: 첫/단일 행 내용 재계산, 동시 writer 순서, 삭제 후 시작 checkpoint를 검증·보완한다. 정상 retention 범위·정책과 검증 기준을 보존하고 사용자/system 이벤트는 기존 AuditLogService를 사용한다.
- 선행: 8·16·37번의 주요 이벤트 계약.
- DoD: 첫/중간 행 변경과 정상 retention을 구분하고 동시 기록으로 체인이 깨지지 않는다. 외부 증거 없는 로컬 체인을 DB 관리자에 대한 변조 불가능 저장소로 표현하지 않는다.
- **2026-09-10 부분 구현:** `AuditLogIntegrityService`가 첫/단일/날짜 필터 첫 hash 행도 내용을 재계산하도록 수정했다. 체인 시작 후 연속 unhashed 행과 그 뒤 연결 단절을 모두 검사하며 같은 행의 내용/연결 중복 오류는 한 번만 센다. endDate만 있는 필터는 이전 행을 생략하지 않으므로 체인 시작 검사를 유지한다. 실제 SHA-256을 사용하는 새 회귀 7건 및 기존 감사 서비스/컨트롤러 13건을 실행해 20건 통과. commit 제목: `fix: verify every hashed audit row and chain gap`. writer 직렬화와 정상 retention checkpoint는 여전히 미완료다.

### 40. 기술정보·시크릿·AI 반출과 보존 정책 — P1 · [설계]

- 현재·대상: 소스/SBOM/private package/저장소 URL/사용자/감사/AI prompt·진단 bundle의 데이터 분류.
- [ ] 수정: 외부 API별 전송 최소 필드와 허용 대상을 정의하고 private package 조회·wanted list·지원 로그 반출을 제어한다. 시크릿은 원문 대신 마스킹/fingerprint로 처리한다. AI는 요약 보조로 제한하고 입력 지시문이 실행·정책 권한을 얻지 못하게 한다.
- 선행: 2~5·10·17번.
- DoD: 금지 데이터가 외부 요청·로그·AI 입력으로 나가지 않고 계정 삭제와 감사 보존 예외가 구분된다. AI 응답이나 reachability 미확인으로 영향/게이트를 확정하지 않는다.

### 41. OsWL 배포물·CLI·룰·모델의 공급망 증거 — P1 · [설계]

- 현재·대상: [ci-cd.yml](.github/workflows/ci-cd.yml), [build.gradle](build.gradle), CLI installer와 선택 AI 자산.
- [ ] 수정: JAR/CLI/image/rule별 digest·서명·SBOM·provenance를 생성하고 action/도구 고정·Gradle dependency verification·릴리즈 권한을 점검한다. 오프라인 검증 도구와 필요한 metadata/룰/모델/신뢰 자료·고지를 함께 배포한다.
- 선행: 1~2·9·35번.
- DoD: 신규 설치 환경에서 외부 다운로드 없이 선택 프로파일의 설치/검증을 완료한다. 모델/룰·scanner 코드와 데이터 라이선스를 분리한다. [SLSA provenance](https://slsa.dev/spec/v1.2/provenance).

## 6단계 — 정확도·일반/망분리·기업 운영의 실제 검증

### 42. 생태계별 정밀도·재현율·미확인 평가 — P1 · [검증 필요]

- 현재·대상: 실제 운영 오탐률/미탐률은 미측정이다. 다른 scanner 출력이나 같은 upstream 결과만으로 정답을 만들 수 없다.
- [ ] 수정: 독립 공급자 근거와 검토자가 있는 affected/unaffected 표본에 경계 합성 사례를 더한다. 생태계·입력 단계별 TP/FP/FN·표본 수·신뢰구간을 기록하고 precision=TP/(TP+FP), recall=TP/(TP+FN), UNKNOWN/실패/수집 누락을 함께 측정한다.
- 선행: 10~31번 중 평가 대상 구현, 1번의 표본 이용 조건. 테스트 파일 작업은 별도 명시적 지시 후.
- DoD: alias 중복을 TP로 부풀리지 않고 UNKNOWN으로 숨긴 precision 개선을 차단한다. 분모 0은 산출 불가로 표시한다. 목표값은 기준선·기관 요구로 합의한 후 검증하며 임의의 99% 보장을 적지 않는다.

### 43. 동일 revision의 온라인·망분리 동등성 — P1 · [출시 필수 검증]

- 현재·대상: 현재 live와 offline matcher/데이터 범위가 다르므로 동일 정확도를 보장하지 않는다.
- [ ] 수정: 같은 inventory/engine/rule/DB/policy revision을 고정해 결과·fixed 제안·coverage·reason·원문 출처를 대조한다. 외부 차단 환경에서 신규 package/version, stale·실패·철회·부재 자료를 검증한다.
- 선행: 5·10~38번 중 지원 프로파일의 필수 기능, 42번 정답 집합.
- DoD: 동일 지원 범위의 설명되지 않는 판정 차이가 없다. 실제 서로 다른 데이터 세대의 차이는 freshness/coverage로 설명한다. 반입 뒤 공개된 취약점·미공개 취약점까지 기존 DB가 탐지한다고 약속하지 않는다.

### 44. PostgreSQL 업그레이드·복구·지원 용량 — P1 · [실환경 필요]

- 현재·대상: 단일 조직 self-hosted를 우선한다. H2 검증은 PostgreSQL 복구/가용성 보증이 아니다.
- [ ] 수정: 프로젝트/컴포넌트/동시 scan/보존기간/최대 export·bundle 예산과 RPO/RTO를 정한다. DB+키+설정+활성 VDB revision+증거 저장소를 함께 백업·복구한다. schema/CLI/bundle preflight와 forward-fix·호환 이행을 검증한다.
- 선행: 16·34~41번; 용량은 46~48번, HA 지원은 50번의 결과 연결.
- DoD: 실제 PostgreSQL 복원 후 권한·암호화 credential·과거 판정·데이터 세대가 맞는다. 미검증 SLO/RPO/RTO/HA를 보장하지 않고, 키 없는 DB 복원을 성공으로 처리하지 않는다.

### 45. 출시 증거·보안 지원·운영 책임 정리 — P1 · [출시 필수]

- 현재·대상: 현재 지원 범위·권리 확인·운영 검증과 별도로 보안 신고/지원 버전/EOL/maintainer·반입 책임을 정리해야 한다.
- [ ] 수정: 3번 지원 목록을 실제 결과로 확정하고 보안 연락/비공개 신고·패치/backport·응답 목표·운영 담당자/반입 주기·지원 종료를 정한다. scan/SBOM/VEX·조치/예외·revision·라이선스·검증 근거를 릴리즈 증거로 연결한다.
- 선행: 필수 P0, 1~3·42~44번과 지원 기능별 DoD.
- DoD: 일반·망분리 설치/반입/계정 폐기/복구 runbook이 실행 근거와 연결된다. 기능 목록을 기업 인증·규정 준수 보장으로 바꾸지 않는다. 새 운영 문서 작성은 후속 구현 범위이며 이번에는 ROADMAP만 수정한다.

### 기존 성능·UI·운영 잔여와 연결

아래는 기존 성능·UI·운영 잔여 작업을 이어서 정리한 목록이다. 검증 근거는 유지하고 번호는 전체 실행 순서에 맞췄다. 과거 테스트 집계는 이번 문서 리팩토링에서 재실행하지 않았다. [아키텍처 근거](docs/ko/Architecture-Optimization.md), [최종 점검](docs/ko/Roadmap-Final-Audit.md), [성능 검증](docs/ko/Performance-Verification.md), [UI·운영 검증](docs/ko/Ui-Operations-Verification.md)를 참조한다. 과거 skip·H2·응답 주입·viewport 검증을 실토큰·PostgreSQL·실기기 검증으로 확대하지 않는다.

### 46. PostgreSQL 대규모 집계·정렬 실행계획 — P2 · 최적화 · [측정 잔여]

- 현재·대상: 전용 PostgreSQL DB가 없어 사용자 결정에 따라 H2 5천/5만 컴포넌트·100프로젝트의 p50/p95, JVM heap 표본, SQL rows/time, HTML bytes와 실행계획을 기록했다. H2 성공은 PostgreSQL 검증을 대체하지 않는다.
- [ ] 수정: PostgreSQL에서 집계·count/filter/sort와 Library risk/라이선스 Formula를 같은 입력으로 측정한다. 실행계획 근거가 있을 때 인덱스/쿼리를 조정한다.
- 선행: 3번의 운영 입력 예산, 44번의 용량/복구 계획.
- DoD: 전용 DB의 실제 실행계획·반복 표본·환경 예산 기록. 재현 명령과 H2 원본: [성능 검증](docs/ko/Performance-Verification.md).

### 47. 대형 Export·Snapshot 운영 예산의 최악 사례 — P2 · 최적화 · [측정 잔여]

- 현재·대상: 로컬 5천/5만 export·archive와 5만5천 Library snapshot은 측정했다. 512 MiB 테스트 JVM은 SBOM에서 OOM이 발생했고, 격리한 2 GiB 예산으로 재측정했다. 전체 결과 목록·StringBuilder·byte[]는 아직 입력에 비례한다.
- [ ] 수정: 실제 최대 CVE payload/의존 경로와 동시 export, 대형 CVE JSON 및 강제 종료 후 temp 파일 회수를 검증한다. ZIP staging의 512 MiB 수용/+1 byte 거부·파일 회수는 128 MiB JVM에서 확인했다. 근거에 따라 응답 streaming 또는 제한을 도입한다.
- 선행: 34~37번 번들 계약, 44번 운영 예산.
- DoD: 운영 입력·동시성 기준의 heap/transaction 상한을 정하고 중단·실패 시 원본 보존과 일반 요청 DB 연결 여유를 재확인한다. 구현 및 로컬 검증 근거: [성능 검증](docs/ko/Performance-Verification.md).

### 48. 외부 보강 지연·프로세스 재시작 부하 검증 — P2 · 검증 · [측정 잔여]

- 현재·대상: loopback Git에서 실제 clone/parse/ingest 20/50/100건을 모두 성공 처리했고 성공·실패·취소, queue wait, Hikari, 일반 요청 p95를 분리 기록했다. 보강은 offline snapshot이며 지연·2사용자 admission·진행/대기 취소는 진입점 latch로 검증했다.
- [ ] 수정: 실제 upstream 지연/장애와 진행 중 clone/ingest의 OS 강제 종료를 별도 시나리오로 측정한다. H2 두 JVM 재시작에서 만료 CLONING job fixture 2건의 종료·취소 보존·중복 scan 없음은 확인했다. PostgreSQL에서 같은 신규 Library의 동시 생성 분기도 검증한다. 로컬 수치를 외부 공급자나 재시작 성능 보증으로 사용하지 않는다.
- 선행: 4~7번 수집/자원 경계, 16번 불변 스캔.
- DoD: 재시작 전후 job/lease·scan 중복·취소 상태와 일반 요청 예산 확인. 원시 분모·단계·fallback 범위를 기록한다. 근거: [성능 검증](docs/ko/Performance-Verification.md).
- **2026-09-10 부분 구현/검증:** 전체 backend 종료 중 유지보수 worker가 닫힌 DB를 조회하는 오류를 발견했다. `ImportJobMaintenanceScheduler`를 SmartLifecycle에 연결해 DB 자원 파괴 전에 작업을 중단하고 최대 10초 동안 정리를 기다린다. 실제 Spring context 종료 회귀 검사는 worker 정리 중 DB 자원이 살아 있음을 확인한다. 테스트의 공통 H2 이름도 context별로 분리해 다른 context의 create-drop이 영향을 주지 않게 했다. 관련 scheduler/store 9건 및 후속 전체 backend 1,103건 중 1,094 통과·실패 0·skip 9. 이전 종료 오류가 후속 실행 로그에서 재발하지 않았다. commit 제목: `fix: stop import maintenance before database shutdown`. 실제 외부 지연·강제 종료·PostgreSQL 재시작 부하는 여전히 잔여다.

### 49. 소스별 분석 상태·gate coverage 및 실제 탐지 검증 — P1 · 정확성/검증 · [코드 확인 + 조건부]

- 현재·대상: 소스별 상태 저장·조회 실패 재시도·미분석 gate 차단 및 coverage 응답은 구현하고 실제 ingest/UI/gate fixture로 검증했다. 아래 실데이터 생태계 조합은 별도 검증한다.
- 검증 범위: 정상 빈 결과와 실패/미지원/미설정을 구분하고, 과거 캐시가 있어도 최근 실패는 gate를 차단한다. Snapshot 왕복의 unresolved 및 CVSS 메트릭 보존도 검증했다. 최종 점검에서 OSV 상세/심각도 유실과 scanner 불완전 상태의 gate 누락을 수정했고, offline ingest→severity gate와 소스 검사 진행 중 차단을 회귀 검사했다.
- 추가 검증: 실제 Debian/Ubuntu/Alpine OCI 패키지·OSV 조회는 완료했다. CVSS v4 실제 유입, C/C++ CPE, CocoaPods/Conda/pixi/explicit의 known-vulnerable/known-clean/unsupported 실데이터 경로 및 Dockerfile 휴리스틱 UI·도움말을 확인한다.
- [ ] 수정: CVSS v4·C/C++ CPE·CocoaPods·Conda/pixi/explicit과 Dockerfile/실제 OCI 구분의 실데이터 검증을 완료한다.
- 선행: 10~31번의 지원 생태계, 42~43번 평가 계약.
- DoD: gate 통과·미분석·분석 완료의 의미가 UI/API/도움말에서 일치. 로컬 악성/빈 응답 fixture를 현재 외부 DB 보증으로 재사용하지 않는다. 신규 데이터 도입 시 재배포 약관을 확인한다.

### 50. 다중 인스턴스 런타임 및 PostgreSQL 리허설 — P1 · 운영/검증 · [조건부]

- 현재·대상: identity/redirect와 관찰된 cron 회차의 중복 판정은 수정하고 순수 함수 4건을 검증했다. 실제 두 JVM의 H2 공유 세션·로그아웃·단일 세션·강제 종료/재시작 및 만료 job fixture 복구는 검증했다. JDBC 세션 활성화 누락도 수정했다. PostgreSQL/LB 검증은 잔여다.
- [ ] 수정: 전용 PostgreSQL/LB에서 로그인 및 로그아웃 cookie 교차 검증, cache invalidation, 단일 세션, OTP 직렬화, failover/job 복구를 확인한다. 원문 CLI 키가 발급 인스턴스 메모리에만 남는 최초 공개 UX도 검증/설계한다.
- 선행: 8번 권한 회수, 16·37번 데이터/스캔 세대, 44번 복구 계획.
- DoD: 로그아웃 cookie 거부, 정상 교차 세션, 회차별 단일 실행과 누락 회차 분석. H2 및 판정 함수 성공으로 대체하지 않는다.

### 51. 기능·화면별 미실행 실패/권한 조합 — P2 · UI/UX · [검증 잔여]

- 현재·대상: 3개 언어 60개 화면 경로, 읽기 전용/비구성원/권한 없음, Reports/Webhooks/Cache 첫 조회 실패와 저장 충돌은 확인했다. Security Center 상세 실패/재시도/포커스, 일괄 중복 클릭·실패·성공, 필터별 0건 및 목록 500/재시도는 확인했다. 모든 필터×HTTP 상태 조합, 조직 팀 권한 조합과 나머지 화면 상태는 전수 검증하지 않았다. 로그인 세션·OTP·비밀번호 변경 OTP의 실제 서버 만료 검사는 통과했다.
- [ ] 수정: [상태 목록](docs/en/Ui-States-Checklist.md)의 구현 표시와 실행 결과를 구분한다. 미실행 조합을 재현하고 실제 결함을 수정한다.
- 선행: 10·17·19번 결과/게이트 상태. 기존 UI 수정은 보존.
- DoD: 각 화면의 loading/empty/failure/403/만료/재시도/연속 클릭을 재현 단계와 함께 기록한다. 기본 렌더링 성공을 모든 상태 통과로 확대하지 않는다.

### 52. 동적 화면 접근성과 실기기 — P2 · UI/UX · [검증 잔여/조건부]

- 현재·대상: 일본어 설정 12개 탭의 390px 너비, Tab/Escape와 심각 axe 위반 0건, 실제 beforeunload는 확인했다. Security Center 일괄 메뉴와 목록/상세 실패 상태 axe, 상세 재시도 후 focus 복귀는 확인했다. 차트 표 전환 및 중첩 검색/상세 dialog의 키보드·포커스·inert 복귀도 최종 점검에서 검증했다. 전체 모바일 페이지와 실기기는 미검증이다.
- [ ] 수정: 미실행 키보드/포커스와 iOS/Android 터치·스크롤·키보드 노출을 실제 기기에서 검증한다.
- 선행: 51번 동적 상태. 전수/실기기 실행 환경 필요.
- DoD: 미실행 키보드/포커스 조합을 검증하고 실제 iOS/Android의 터치 타겟·스크롤·키보드 노출을 확인한다. Chromium viewport 검사를 실기기로 간주하지 않는다.

### 53. 온보딩 실제 공급자 연결 및 설정 왕복 — P2 · UI/UX/검증 · [조건부]

- 현재·대상: 3개 VCS 공급자 403/재시도와 webhook 네트워크 오류 UI는 응답 주입으로 확인했다. 실토큰 연결 성공/만료/권한 부족, custom host 안내, 실제 webhook 저장·전달 실패, 권한별 폼과 재진입 배지는 남았다.
- [ ] 수정: 실토큰 연결·만료·권한 부족·custom host·webhook 저장/전달 실패와 재진입 폼 상태를 검증한다.
- 선행: 4~5·8번 VCS/인증 정책과 실제 공급자 검증 환경.
- DoD: GitHub/GitLab/Bitbucket 서버 검증과 UI 결과 일치, 비밀값 없는 근거, 테스트 설정 복원. 인라인 팀 계정 생성·역할 선택·중복 실패·재시도는 별도 구현/검증을 완료했다.

### 54. 사용자 GGUF 신뢰·승인 정책 — P2 · [조건부/운영 결정]

- 현재·대상: 고정 배포 모델의 새 다운로드·checksum·기동/추론·OS 강제 종료 후 재기동은 실제 1.28 GB 모델로 통과한 기존 기록이 있다. HTTP fixture 실패/취소·기존 파일 보존 및 브라우저 재시도도 확인됐다. 남은 것은 사용자 제공 GGUF의 신뢰 정책이다.
- [ ] 수정: 사용자 모델의 출처·license·digest/승인·보관/폐기·실행 권한 정책을 정하고 불명확한 파일의 취급을 운영 설정과 안내에 반영한다. 이전에 통과한 고정 모델 설치 전체를 새 미완료 기능으로 되돌리지 않는다.
- 선행: 1~2·40~41번. 기존 개발 모델을 삭제하지 않는다.
- DoD: 기관이 승인한 모델만 정한 경계에서 실행되며 실패/거부 이유와 복구 절차가 명확하다. 원천/조건 확인이 없는 파일을 단순 다운로드 성공으로 신뢰하지 않는다.

### 55. 원격 PR CI 검증 — P2 · 검증/운영 · [조건부]

- 현재·대상: 필수 manifest 사전조건과 빈 checkout 음성 대조는 통과했다. 실제 Dgs/Express/Maui/Rails checkout 파싱은 모두 skip 없이 통과했다. 원격 PR 실행은 미검증이다.
- [ ] 수정: 실제 PR에서 필수 backend/UI job·fixture가 성공하고 배포 job은 실행되지 않는지 확인한다.
- 선행: 41번 CI 공급망 계약. 실제 원격 PR 실행 환경 필요.
- DoD: 실제 fixture의 파싱 결과와 원격 backend/UI 잡 성공, PR에서 배포가 실행되지 않음을 확인한다. 이 검증만을 위해 푸시하지 않는다.

## 7단계 — 기반 검증 이후의 조건부 확장

### 56. 전문 분석기 adapter·산출물/소스 교차 검증 — P3 · [조건부 확장]

- 현재·대상: 기존 OCI·시크릿/IaC·사용자 룰을 재구현하지 않는다. 더 넓은 SCA/SAST/구성 검사에 전문 OSS 도구를 비교한다.
- [ ] 수정: Trivy/Grype/OSV-Scanner 등 한 도구부터 고정 engine/DB/rule·자원·원본 출력 계약으로 연결한다. source SBOM과 artifact/rootfs의 누락·추가·버전 차이를 보여주고 도구 코드/DB/룰/이미지 권리를 각각 확인한다.
- 선행: 1~2·6·10~17·41~43번.
- DoD: 추가 탐지 이득·오탐·미탐·실행 비용을 측정한다. 같은 원천의 중복 결과를 독립 정탐으로 세지 않는다. [Trivy offline](https://trivy.dev/docs/latest/guide/advanced/air-gap/), [Grype](https://oss.anchore.com/docs/guides/vulnerability/getting-started/).

### 57. 도달성·VEX·수정 조치 검증 — P3 · [조건부 확장]

- 현재·대상: 버전 영향 판정과 악용 가능성, 기존 VEX/예외 기능을 연결하되 분석 한계를 보존한다.
- [ ] 수정: Go 심볼 분석 등 검증 가능한 범위부터 적용하고 reflection/dynamic/native·입력 누락은 UNKNOWN으로 남긴다. 외부 VEX는 발행자·artifact·근거·만료/철회를 검증한다. 업그레이드 분기/전이 경로·라이선스 변화·재빌드/재스캔을 조치 이력에 연결한다.
- 선행: 10~19·38·42번. 업데이트 PR 자동 생성은 별도 선택 기능.
- DoD: not_affected/미도달만으로 전역 취약점을 삭제하지 않는다. 실제 재빌드·검증 증거가 있을 때 해결 확인하며 AI 요약만으로 승인하지 않는다. [Go vuln](https://go.dev/doc/security/vuln/), [CycloneDX VEX](https://cyclonedx.org/capabilities/vex/).

### 58. 서비스 identity·키 rotation·복구 — P3 · [조건부 확장]

- 현재·대상: 9번의 단기 CLI 보완과 [EncryptionService](src/main/java/com/salkcoding/oswl/auth/security/EncryptionService.java)의 단일 키 형식을 후속 확장한다.
- [ ] 수정: 프로젝트 범위 machine identity·짧은 credential·CI federation/폐쇄망 장기키 정책을 제공한다. 암호문 envelope keyId/algorithm/version, 구키 읽기·신키 쓰기·재개 가능한 재암호화·검증 후 폐기를 구현한다.
- 선행: 8~9·39·44번. 기관 KMS/Vault/HSM·키 보관 정책 확정.
- DoD: 회수·회전·중단/복구 후 credential이 의도한 범위에서만 유효하다. 외부 KMS를 필수 의존성으로 만들지 않는다. [현재 백업/키 한계](docs/en/Backup-And-Restore.md).

### 59. 독립 감사 checkpoint·SIEM/WORM 전달 — P3 · [조건부 확장]

- 현재·대상: 39번 로컬 체인의 한계를 넘어 독립 변조 탐지가 필요한 기관용이다.
- [ ] 수정: 외부 키 서명 checkpoint와 독립 보관소를 연결하고 outbox/retry/ACK/backlog 경보를 구현한다. 운영자·감사/키 보관자 권한을 분리하고 retention/법적 보존 예외를 관리한다.
- 선행: 39~40·44번, 실제 기관 보관소.
- DoD: 꼬리 삭제·전체 DB 재작성·전송 실패와 정상 보존 삭제를 구분한다. 외부 checkpoint 없는 프로파일에 동일 보증을 표시하지 않는다.

### 60. 국가·공급자 데이터와 내부 advisory — P3 · [조건부 확장]

- 현재·대상: JVN/MyJVN, KISA/C-TAS, 공급자 CSAF·상용 피드는 부록 A의 미승인 후보로 유지한다.
- [ ] 수정: API/상용 이용/변형/재배포/로컬 보관/식별자 변경 조건을 확인한 후 adapter를 추가한다. 자동 연계가 허용되지 않으면 출처·제품·버전·승인 증거가 있는 내부 advisory를 제공한다.
- 선행: 1~2·10~14·32~38번, 기관의 실제 수요/이용 자격.
- DoD: 번역·국가별 공지를 중복 CVE로 세지 않고 제품 식별/상태를 보존한다. 공공 공개만으로 CC0를 가정하지 않는다. [MyJVN](https://jvndb.jvn.jp/apis/index.html), [변경 안내](https://jvndb.jvn.jp/nav/sys_announce.html), [C-TAS](https://krcert.or.kr/kr/subPage.do?menuNo=205013).

### 61. 표준 교환·언어·시간·규정 대응 증거 — P3 · [조건부 확장]

- 현재·대상: 기존 CycloneDX/SBOM/VEX/SARIF, 영어/한국어/일본어는 유지하고 신규 표준/기관 요구만 확장한다.
- [ ] 수정: SPDX/CSAF 등 채택 버전·lossy 변환·schema/round-trip을 검증한다. UTC/offset·locale·DST·CSV/Unicode 경로·Windows/Linux의 동일 판정과 원문/번역 구분을 유지한다. SBOM·조치·예외·지원 이력의 규정별 증거 묶음을 설계한다.
- 선행: 2·10·16·42~43·45번. 규정 적용/제출 자동화는 기관별 조건 확인 후.
- DoD: locale/시간대가 정책 결과를 바꾸지 않고 표준 변환의 필드 손실이 드러난다. 제품 설치를 법적 인증으로 표현하지 않는다. [CSAF](https://docs.oasis-open.org/csaf/csaf/v2.0/csaf-v2.0.html), [KISA 가이드](https://www.kisa.or.kr/2060204/form?page=1&postSeq=15), [EU CRA](https://digital-strategy.ec.europa.eu/en/policies/cra-summary).

### 62. 멀티테넌시와 대규모 배포의 별도 제품 결정 — P3 · [조건부 확장]

- 현재·대상: 현재 [Organization](src/main/java/com/salkcoding/oswl/domain/entity/org/Organization.java)은 단일 조직 전제다. 팀/프로젝트 ACL은 고객 간 tenant 격리 보장이 아니다.
- [ ] 수정: 수요가 확인되면 tenant context를 API/repository/job/cache/export/search/snapshot/AI/log 전체에 전파하고 private package 정보 격리를 검증한다. 그 전에는 고객별 인스턴스·DB·파일·키를 분리한다.
- 선행: 3·8·16·40·44번 및 별도 기능 요청. Kubernetes 전환은 실측/운영 근거 후 판단.
- DoD: 교차 tenant 읽기/쓰기·작업/캐시/리포트 누출을 검증한 범위만 지원한다. 조직 테이블 추가만으로 격리 완료를 선언하지 않는다.

### 63. PostgreSQL 파티셔닝·아카이브 자동화 — P3 · 최적화/운영 · [조건부]

- 현재·대상: 수동 archive/export를 유지한다. PostgreSQL 병목 실측, 운영 보존 기간과 복구 정책이 없어 파티셔닝·자동 삭제/보관을 적용하지 않았다. 기능 구현 요청과 별개로 필요한 운영 조건이며 완료로 처리하지 않는다.
- [ ] 수정: 3단계 계측에서 삭제/조회/보존 비용 문제가 확인될 때 파티셔닝을 검토한다. 자동화는 export 성공 및 복구 가능성 확인, 보존 정책, 감사 기록을 전제로 한다.
- 선행: 46~47·44번 실측, 기관 보존/복구 정책.
- DoD: 보존 대상 오삭제 없음, export 실패 시 삭제 차단, partition 운영/복구/rollback 절차 검증. 측정 근거 없이 DB 구조를 먼저 바꾸지 않음.

## 부록 A — 데이터 권한 조사 근거와 미확인 범위

**2026-09-07 조사 결과를 보존한 참고 근거**다. 실행 상태는 1~2번에서 관리한다. 아래 '확인/정정'은 이전 조사·고지 보완의 기록이며 이번 리팩토링에서 새 법률 검토나 데이터 도입을 완료한 뜻이 아니다. 채택 버전·반입 시점의 조건은 구현 착수 시 다시 확인한다.

아래는 공식 자료에서 확인한 **기술·배포 설계용 검토 결과**다. 상용 온프레미스 제공, 고객에게 DB 전달, 보고서 공유, 공개 SaaS 표시, 사내 미러는 서로 다른 이용 형태다. **온프레미스가 항상 망분리인 것도 아니며, 사내 사용 허용이 고객 재배포 허용과 같은 것도 아니다.** 라이선스, 서비스 약관/API 접근 조건, 저작권·데이터베이스권, 공급자/개별 record 조건을 나누어 기록해야 한다. 공개 조회 가능성만으로 권한을 추정하지 않는다.

- **OSV 집계 데이터:** OSV 서비스나 포맷의 개방성과 원천 데이터의 권리는 별개다. [공식 source 목록](https://google.github.io/osv.dev/data/)을 출발점으로 각 record의 출처를 추적한다. 생태계 단위로 모두 CC0 또는 CC-BY라고 고정하지 않는다. 망분리용 정규화 bundle에서도 출처·고지·변경 정보를 보존해야 하며 현재 단순 `SnapshotVuln` 필드만으로 충분하다고 인증할 수 없다.
- **GitHub Advisory Database / GHSA:** [CC-BY 4.0](https://github.com/github/advisory-database/blob/main/LICENSE.md). 조건을 지켜 상용 사용·변형·재배포할 수 있다. creator/공급된 고지, source·license URL, 변형 표시를 보존한다. GitHub API 인증·요율 제한·서비스 약관은 데이터 라이선스와 별도다.
- **PyPA와 Go 데이터:** [PyPA advisory DB](https://github.com/pypa/advisory-database/blob/main/LICENSE)는 CC-BY 4.0. [Go vuln DB LICENSE](https://github.com/golang/vulndb/blob/master/LICENSE)는 `/data/` CC-BY 4.0와 코드 BSD 조건을 구분한다. Go 분석 도구의 코드 라이선스로 advisory DB를 재고지하지 않는다.
- **RustSec:** [LICENSE](https://github.com/rustsec/advisory-db/blob/main/LICENSE.txt)는 기본 CC0지만 GHSA에서 반입한 데이터는 CC-BY 4.0 예외다. 개별 advisory의 `license`와 `url` 등 provenance를 보존한다. 기존 “crates.io/RustSec는 전부 CC0” 고지를 정정했다.
- **Ubuntu Security Notices:** [CC-BY-SA 4.0](https://github.com/canonical/ubuntu-security-notices/blob/main/LICENSE). 상용 사용 자체가 금지된 것은 아니다. 공유하는 adapted material에 attribution·ShareAlike 등 조건을 적용해야 한다. 단순히 파일을 분리했다고 의무가 사라지지는 않으며, 반대로 이 데이터를 쓴다는 이유만으로 OsWL 애플리케이션 전체를 CC-BY-SA로 바꿔야 한다고 단정하지도 않는다. 정규화·병합 DB의 적용 범위를 검토하고 원천별 라이선스를 보존한다.
- **Debian / Alpine 원천 데이터:** 공식 [Debian tracker](https://security-tracker.debian.org/tracker/), [Alpine secdb](https://secdb.alpinelinux.org/)는 확인했으나 이번 조사에서 **해당 데이터 전체의 재배포 조건을 충분히 확인하지 못했다**. Debian source-host의 라이선스 경로는 접근 challenge, Alpine의 조회한 루트 경로는 명확한 데이터 라이선스를 제공하지 않았다. “무라이선스”나 “사용 불법”으로 단정할 근거도 없다. 코드 저장소/웹사이트의 라이선스를 데이터에 자동 적용하지 말고, 기존 OSV 경유분을 포함해 데이터 원천·이용 목적별 조건 확인을 남긴다. 고객용 데이터 팩의 자동 승인 대상에는 아직 넣지 않는다.
- **NVD / CVE:** NVD 조회 기능이 이미 있다. [NIST 권리 안내](https://www.nist.gov/open/copyright-fair-use-and-licensing-statements-srd-data-software-and-technical-series-publications)는 정부 작성물과 기타 자료를 구분해야 할 근거이지 모든 CVE·외부 설명문의 CC0 허가가 아니다. 이번 NVD별 약관 페이지 조회는 충분한 본문을 반환하지 않아 일괄 재배포 승인을 확정하지 못했다. NVD 출처·비인증/비보증 고지는 추가하되 [CVE 이용 조건](https://www.cve.org/Legal/TermsOfUse)과 실제 반입 필드의 권리를 추가 확인한다. 외부 advisory 전문·첨부 문서를 자동으로 복제하지 않는다.
- **CISA KEV:** 공식 [데이터 저장소 LICENSE](https://github.com/cisagov/kev-data/blob/develop/LICENSE)는 CC0를 명시한다. 로컬 미러·고객 배포 후보로 사용할 수 있으나 외부 링크의 콘텐츠나 CISA/DHS 표장·추천까지 허가한 것은 아니다. 출처·수집일·catalog version은 판정 재현성을 위해 유지한다.
- **FIRST EPSS:** [FAQ](https://www.first.org/epss/faq), [데이터 안내](https://www.first.org/epss/data)는 공개 score 접근과 출처 표시 요청을 설명한다. 공개 CSV/API가 있다는 이유로 별도 score DB의 상용 재배포 권한까지 확정할 수 없고, 이번 조사에서 명시적 SPDX 데이터 라이선스/포괄 재배포 허가를 확인하지 못했다. 학습·관측 원천 데이터와 공개 score를 구별한다. 기존 live/CLI 사용을 곧바로 위법으로 판단하지 않되 고객 번들 재배포 조건은 별도 확인한다. 미승인 배포 프로파일은 EPSS를 제외할 수 있어야 하며, 누락 값을 0으로 대체하지 않는다.
- **deps.dev:** [공식 README](https://github.com/google/deps.dev#readme)는 자체 생성 데이터 CC-BY 4.0와 집계 원천의 조건을 구분하고 API caching을 허용한다. 따라서 `licenses`·`advisoryKeys` 등 모든 필드를 독립적인 CC-BY 결과라고 일괄 취급하지 않는다. [API 이용 약관](https://developers.google.com/terms)은 별도 적용된다. [FAQ](https://docs.deps.dev/faq/)의 BigQuery 제공 사실도 반영해 기존 “bulk 경로가 없다” 단정을 제거했다. API 캐시를 모든 패키지를 지원하는 온프레미스 deps.dev 서버라고 부르지 않는다.
- **CocoaPods Specs:** [Specs 저장소 안내](https://github.com/CocoaPods/Specs#readme)는 specification의 MIT 조건을 안내한다. source spec의 상당 부분을 반입·재배포할 때 해당 저작권/허가문을 보존한다. 실제 pod 코드·binary는 각 pod의 별도 라이선스다. metadata의 `license` 값은 패키지 라이선스 판단의 근거이지 그 콘텐츠 전체를 재배포할 권한 보증은 아니다.
- **번들에 이미 포함된 Conda 이름 매핑:** [CondaPypiMappingService](src/main/java/com/salkcoding/oswl/service/ingest/CondaPypiMappingService.java)가 밝힌 출처는 `regro/cf-graph-countyfair`의 JSON이며 현재 [conda-forge-bot-data](https://github.com/conda-forge/conda-forge-bot-data)로 연결된다. 별도 Grayskull 프로그램의 Apache 라이선스로 고지하면 안 된다. upstream `License`의 Columbia University BSD 계열·Tick-my-feedstocks/Rever BSD 3-Clause·Doctr MIT 고지를 [배포 리소스](src/main/resources/META-INF/licenses/conda-forge-bot-data-LICENSE.txt)에 그대로 추가했다. 이는 현재 upstream 고지를 보완한 것이며, 기존 JSON의 원본 commit과 추가 원천 데이터 조건까지 확정한 것은 아니다. 원본 revision 복원 또는 provenance를 검증한 snapshot으로 교체하는 후속 작업이 필요하다.

CC-BY 계열은 단순히 “OSV 제공”이라고 적거나 CVE ID만 보존하는 것으로 모든 고지 의무를 충족했다고 단정할 수 없다. 실제로 공급된 권리자·저작권·면책 고지, license/source 링크, 수정 여부를 전달 가능한 형태로 유지한다. CC-BY-SA에서는 적응·공유 방식에 따른 동일조건변경허락도 검토한다. 이용자에게 라이선스상 권리를 제한하는 추가 조건을 부과하지 않는다. [CC-BY 4.0](https://creativecommons.org/licenses/by/4.0/), [CC-BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/).

### 추가 도입 후보와 권한 조건

우선은 권한·품질을 확인한 GHSA/PyPA/Go/RustSec 원본과 OSV 레코드의 출처 보존을 강화한다. KEV는 우선순위용으로 유지한다. Ubuntu는 ShareAlike 조건을 반영한 데이터 팩 후보로, EPSS·NVD 내 제3자 필드·Debian/Alpine의 고객 재배포는 확인 과제로 관리한다. 이 문서의 분류가 현재 코드에 도입 제한을 구현했다는 의미는 아니다.

PHP 보강 후보 [FriendsOfPHP/security-advisories LICENSE](https://github.com/FriendsOfPHP/security-advisories/blob/master/LICENSE)는 **Unlicense**를 명시한다. 버전 고정·출처·내용 정확도와 기존 GHSA 중복을 검증한 후 선택적으로 사용할 수 있다. PHP 정탐률 향상을 검증하지 않고 데이터 수만 더하지 않는다.

Ruby 보강 후보 [ruby-advisory-db LICENSE](https://github.com/rubysec/ruby-advisory-db/blob/master/LICENSE.txt)는 자체 기여의 public-domain 조건 외에 역사적 **OSVDB** 수집분의 별도 권리를 명시한다. 여기서 OSVDB는 Google OSV와 다른 프로젝트다. 전체 DB를 일괄 public domain으로 반입하지 말고 레코드별 원천·허용 조건을 검증하거나 해당 출처를 제외하는 근거를 남긴다. 검증되지 않은 필터로 재배포 승인을 주장하지 않는다.

Red Hat/SUSE 공급자 보안 데이터, JVN/JVN iPedia, KISA 등 국가별 정보, 상용 피드는 후속 후보이며 이번에 포괄 반입을 승인하지 않는다. 각 API 이용/상용 재배포/변형/보관/로컬 미러 조건과 데이터 품질을 먼저 확인한다. 공공기관 공개자료라는 이유만으로 CC0를 가정하지 않는다. 국가별 발표·번역은 보조 출처로 연결하되 원문 ID와 의미를 보존하고, 동일 CVE의 번역 문서를 독립 취약점으로 세지 않는다.

Trivy/Grype/Syft/OSV-Scanner 같은 엔진을 추가할 때는 **실행 도구의 코드 라이선스와 도구가 내려받는 DB·룰·템플릿·컨테이너 이미지의 라이선스가 다르다**는 전제로 검토한다. 제3자 scanner 결과를 합쳐도 같은 advisory에서 파생되면 독립 검증이 아니다. 오프라인 DB의 이용권, 출처 export 가능성, 판정 설명, 업데이트 서명·도구 공급망까지 확인한 후 adapter를 채택한다.

## 부록 B — 판정 수정 근거와 라이브러리 후보

- **11번 실제 진단:** SimpleVersionComparator 소스를 JShell에 읽어 파일 생성 없이 비교했다. `1.0.0+1` 대 `1.0.0+2`는 -1, `1.0.0-alpha` 대 `1.0.0`은 IllegalArgumentException, `1.9.0` 대 `1.10.0`은 -1이었다. 앞의 두 결과는 [SemVer](https://semver.org/) 우선순위와 맞지 않는다. 운영 오탐률 측정은 아니다.
- **12번 정적 추론:** `introduced=0, fixed=1.0.0, introduced=2.0.0, fixed=3.0.0`에서 현재 마지막 introduced 재사용은 첫 구간의 0.5.0을 놓칠 수 있다. versions 목록 밖이지만 range 안인 버전, last_affected 경계 자체, limit와 Git ancestry도 검증 대상이다. 이 합성 사례는 메서드 전체를 실행한 재현 결과로 표시하지 않는다.
- **13번 현행 개선 유지:** live OsvClient의 현재 패키지 필터·복잡한 fixed 제안 보류, GHSA GraphQL 오류/hasNextPage 및 NVD totalResults 초과의 실패 처리는 이미 있다. '상세 정보를 언제나 잃는다', '페이지 누락을 늘 정상 성공 처리한다'는 과거 설명으로 되돌리지 않는다.
- **31~32번 배포판 구분:** bulk 저장 경로는 상위 생태계, 매칭 identity는 정확한 distro/release다. dump 경로 수정으로 배포판 의미까지 합치지 않는다. 실제 bulk 다운로드는 이전 조사에서 수행하지 않았다.
- **엔진 후보:** [deps.dev util/semver](https://github.com/google/deps.dev/tree/main/util/semver)는 다중 생태계 로컬 helper 후보이며 [저장소 코드 Apache-2.0](https://github.com/google/deps.dev/blob/main/LICENSE)와 데이터 조건은 별개다. [node-semver ISC](https://github.com/npm/node-semver/blob/main/LICENSE), [packaging Apache/BSD 선택 조건](https://github.com/pypa/packaging/blob/main/LICENSE), [NuGet.Client Apache-2.0](https://github.com/NuGet/NuGet.Client/blob/dev/LICENSE.txt), [composer/semver MIT](https://github.com/composer/semver/blob/main/LICENSE)를 검토했다. 이번에 도입하지 않았으며 특정 버전·transitive dependency·원문 NOTICE를 재검토한 후 선택한다. 라이브러리 채택만으로 native 규칙 적합성을 보장하지 않는다.

## 완료 기록과 이번 문서 검증

항목 완료 근거의 최소 형식은 **항목 번호 / 구현 commit / 실행 환경·명령 / 통과·실패·skip / 권한·원문 근거 / 운영 잔여**다. 코드 수정만 끝났으면 실환경 DoD를 완료로 바꾸지 않는다. 큰 조사 보고서를 다시 붙이지 않고 새 근거는 해당 항목 또는 위 부록에 반영한다.

초기 문서 재구성 작업은 기존 목록과 두 조사 본문을 실행 항목으로 재구성하고, 중복 설명·과거 세션의 구현 승인 문구·오래된 행 번호 참조를 정리한 것이다. 당시 검증은 로컬 링크·항목 번호/선행 참조·diff·인코딩 확인으로 한정했다. 이후 구현과 검증은 각 항목의 날짜별 진행 기록을 따른다.
