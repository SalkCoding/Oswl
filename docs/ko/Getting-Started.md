# 시작하기

이 가이드는 OsWL 설치, 설정 마법사 실행, 그리고 첫 번째 프로젝트 스캔 완료까지 안내합니다.

---

## 시스템 요구사항

| 구성 요소 | 요구사항 |
|---|---|
| **JDK** | 25 |
| **빌드 도구** | Gradle Wrapper (포함 — `./gradlew`) |
| **데이터베이스** | H2 파일 모드 (로컬/개발) 또는 PostgreSQL 15 이상 (운영) |
| **OS** | Linux, macOS, Windows |
| **메모리** | 스캔 작업량에 따라 다르며, 내장 AI에는 추가 메모리 필요 |

> Node.js나 npm은 필요 없습니다 — Tailwind CSS 독립 실행형 바이너리가 첫 번째 빌드 시 Gradle에 의해 자동으로 다운로드됩니다.

---

## 설치

### 1. 저장소 클론

```bash
git clone https://github.com/SalkCoding/Oswl.git
cd Oswl
```

### 2. 프로필 선택

OsWL은 두 가지 Spring 프로필을 제공합니다:

| 프로필 | 데이터베이스 | 용도 |
|---|---|---|
| `local` *(기본값)* | H2 파일 (`./oswl-db.mv.db`) | 개발 및 평가 |
| `prod` | PostgreSQL | 운영 배포 |

### 3. 애플리케이션 시작

로컬 개발에서는 `./gradlew bootRun`을 실행합니다(PowerShell: `.\gradlew.bat bootRun`). 기본 `local` 프로필은 H2와 고정된 개발용 암호화 키를 사용합니다. 개발 환경 밖에서는 별도의 영구 키를 사용하고, 개발용 키를 운영 환경에 복사하지 마세요.

운영 배포에서는 `./gradlew bootJar verifyProdJar`로 배포용 JAR를 빌드하고 `prod` 프로필로 실행합니다. `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`와 Base64 디코딩 시 32바이트인 영구 `OSWL_ENCRYPTION_KEY`를 설정하세요. 키는 `openssl rand -base64 32`로 한 번 생성한 뒤 재시작과 복원 시에도 동일하게 유지합니다. `prod`는 스키마를 자동 생성하지 않고 검증하므로 PostgreSQL 스키마를 먼저 준비해야 합니다.

배포 절차는 [운영 배포 체크리스트](Production-Deployment-Checklist.md)와 [Docker 실행 안내](../../deploy/README.md)를 참고하세요. `bootRun`과 `java -jar`는 `.env` 파일을 자동으로 읽지 않습니다. 실행 셸에서 환경변수를 설정하거나 Spring 설정 파일을 사용하고, Compose에서는 `--env-file`을 지정하세요.

기본 애플리케이션 포트는 **8080**입니다. 필요한 메모리는 작업량에 따라 달라지며, 로컬 AI에는 모델과 컨텍스트를 위한 추가 메모리가 필요합니다.

## 임베디드 AI 모델 (최초 실행)

현재 기본 모델은 **Qwen3.5-2B Q4_K_M**입니다(1,280,835,840바이트, 약 1.28GB). Hugging Face의 `unsloth/Qwen3.5-2B-GGUF`에서 고정된 리비전의 파일을 내려받아 `embedded-ai/model/Qwen/`에 저장하고 설정된 SHA-256으로 검증합니다. llama.cpp 실행 파일은 별도로 설치해 `embedded-ai/llama/` 또는 `PATH`에 둡니다.

모델이 없고 `OSWL_EMBEDDED_AUTO_DOWNLOAD=true`이면 기동 시 백그라운드에서 미리 다운로드합니다. 이 작업은 사이드카를 시작하거나 AI 제공자를 활성화하지 않습니다. 실행 파일을 설치한 뒤 **설정 → AI**에서 시작하세요. 기동 시 미리 받기를 끄려면 값을 `false`로 설정합니다. 폐쇄망 모드에서는 모델 자동 다운로드가 비활성화됩니다. 모델 탐색·설정·문제 해결은 [내장 AI](Embedded-AI.md)를 참고하세요.

## 에어갭(오프라인) 환경에서 시작하기

폐쇄망 모드는 지원되는 취약점 정보원을 스냅샷으로 전환하는 기능이며 네트워크 방화벽이 아닙니다. VCS·SMTP·웹훅·AI 주소는 환경에 맞게 별도로 설정하세요.

아웃바운드 인터넷 접속이 없는 호스트의 경우:

1. 기동 전 `OSWL_AIRGAPPED_ENABLED=true`를 설정합니다. 취약점/위협 인텔 조회(OSV, deps.dev, EPSS, KEV)는 가져온 오프라인 스냅샷에서 제공되며 아웃바운드 HTTP를 시도하지 않고, 임베디드 모델 자동 다운로드도 건너뜁니다.
2. 인터넷에 연결된 머신에서 스냅샷 번들을 빌드합니다:

   ```bash
   scripts/oswl-vdb/oswl-vdb.sh build --wanted wanted-list.jsonl --out bundle.zip
   ```

   (Windows: `scripts/oswl-vdb/oswl-vdb.ps1`.) 번들을 실제 의존성에 맞추려면, 먼저 연결된 OsWL 인스턴스에서 wanted-list를 내보내세요: `GET /api/admin/snapshot/wanted-list`.
3. `bundle.zip`을 에어갭 호스트로 옮겨 시스템 관리자 권한으로 `POST /api/admin/snapshot/import`(멀티파트 업로드)로 import하거나, `OSWL_AIRGAPPED_IMPORT_DIR`로 디렉터리를 허용 목록에 등록한 뒤 `POST /api/admin/snapshot/import-from-path`를 사용하세요. [관리 — 오프라인 스냅샷 번들](Administration.md)을 참조하세요.
4. 임베디드 AI를 쓰려면 직접 구한 `.gguf` 모델을 `embedded-ai/`에 넣은 뒤 **시작**을 누르세요.

---

## 설정 마법사

최초 실행 시(빈 데이터베이스), OsWL은 일반 애플리케이션 페이지 요청을 `http://localhost:8080/setup`으로 리다이렉트합니다.

마법사에서 수집하는 정보:

| 필드 | 설명 |
|---|---|
| **관리자 이메일** | 시스템 관리자 계정의 로그인 자격증명으로 사용 |
| **비밀번호** | 최소 길이 정책을 충족해야 함 (기본값: 8자) |
| **표시 이름** | UI 및 감사 로그에 표시 |

제출 후 OsWL이 관리자 계정을 생성하고 로그인 페이지로 리다이렉트합니다.

> 로컬 모드에서 초기 상태로 재시작이 필요하면, 서버를 중지하고 `oswl-db.mv.db` (및 `oswl-db.trace.db`가 있으면 함께) 파일을 삭제한 후 재시작하세요.

---

## 첫 로그인

1. `http://localhost:8080/login`에서 설정 마법사로 만든 계정으로 로그인합니다.
2. 이메일 2단계 인증이 필요한 경우 현재 세션에 발급된 6자리 코드를 입력합니다. 코드는 3분간 유효하며, 재발송에는 60초의 대기 시간이 있습니다.
3. 기본 로컬 GreenMail SMTP 설정에서는 수신한 코드가 서버 로그의 `*** OTP CODE: ... ***`에 표시됩니다. 외부 SMTP를 설정했다면 수신자 메일함에서 확인하세요. 고정 코드 `000000`으로 인증을 우회하는 기능은 없습니다.
4. 비밀번호 변경 대상으로 지정된 계정은 새 비밀번호를 설정해야 계속 사용할 수 있습니다.

## 테스트 데이터 시드 (로컬 전용)

설정 마법사를 완료한 뒤 `GET /data/test`를 호출하면 기존 프로젝트·스캔·라이브러리 데이터를 삭제하고 `DemoImportCatalog`에 등록된 공개 저장소의 실제 Quick Import 작업을 큐에 추가합니다. 프로젝트 화면으로 이동한 후에도 가져오기는 비동기로 진행되며 네트워크 연결이 필요합니다. 고정 테스트 계정이나 미리 정해진 취약점 목록을 생성하는 기능은 아닙니다.

이 개발용 엔드포인트는 local 소스 세트의 `local`/`test` 프로필에서 제공하며 운영 JAR에는 포함되지 않습니다. `local`에서는 `/data/**`에 인증 없이 접근할 수 있으므로, 데이터를 초기화하는 이 기능은 격리된 개발 인스턴스에서만 사용하세요.

`GET /data/test-api-key`는 현재 첫 번째 프로젝트에 연결된 API 키를 발급합니다. 프로젝트가 없으면 404를 반환하므로 데모 가져오기로 프로젝트가 생성될 때까지 기다리세요.

## UI/접근성 테스트 하네스 (개발자 전용)

`./gradlew uiTest`는 실제 애플리케이션을 대상으로 기존 Playwright 브라우저 테스트를 실행합니다. 접근성 테스트는 지정된 페이지에서 axe-core 검사를 수행하고, 다른 테스트는 화면 조작과 요청 흐름 등을 확인합니다. 필요한 경우 Chromium을 다운로드하며, `test`/`check`와 별도로 실행합니다.

JUnit HTML 리포트는 `build/reports/tests/uiTest/`, axe 리포트는 `build/reports/axe/`에 저장됩니다. 폐쇄망에서는 브라우저 실행 파일과 빌드 의존성을 미리 준비하고, 필요한 경우 `PLAYWRIGHT_BROWSERS_PATH`를 지정하세요. 브라우저 설치만으로 빌드 의존성까지 준비되지는 않습니다.

## 접근 제어 (권장)

* [권한 레이어](Authorization-Layers.md) — 역할 템플릿 vs 프로젝트 멤버십
* [운영 배포 체크리스트](Production-Deployment-Checklist.md) — `prod`로 실서비스 전환 전에

## 다음 단계

* [첫 번째 VCS 저장소 연결](Quick-Import.md)
* [CLI로 스캔 제출](CLI-Integration.md)
* [보안 센터 탐색](Security-Center.md)
