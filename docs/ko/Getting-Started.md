# 시작하기

이 가이드는 OsWL 설치, 설정 마법사 실행, 그리고 첫 번째 프로젝트 스캔 완료까지 안내합니다.

---

## 시스템 요구사항

| 구성 요소 | 요구사항 |
|---|---|
| **JDK** | 25 이상 |
| **빌드 도구** | Gradle Wrapper (포함 — `./gradlew`) |
| **데이터베이스** | H2 파일 모드 (로컬/개발) 또는 PostgreSQL 15 이상 (운영) |
| **OS** | Linux, macOS, Windows |
| **메모리** | 최소 512MB, 1GB 이상 권장 |

> Node.js나 npm은 필요 없습니다 — Tailwind CSS 독립 실행형 바이너리가 첫 번째 빌드 시 Gradle에 의해 자동으로 다운로드됩니다.

---

## 설치

### 1. 저장소 클론

```bash
git clone https://github.com/SalkCoding/Oswl.git
cd Oswl
```

### 2. 프로파일 선택

OsWL은 두 가지 Spring 프로파일을 제공합니다:

| 프로파일 | 데이터베이스 | 용도 |
|---|---|---|
| `local` *(기본값)* | H2 파일 (`./oswl-db.mv.db`) | 개발 및 평가 |
| `prod` | PostgreSQL | 운영 배포 |

### 3. 애플리케이션 시작

**로컬 (H2, 설정 없음):**

```bash
./gradlew bootRun
```

**운영 (PostgreSQL):**

```bash
export SPRING_PROFILES_ACTIVE=prod
export DB_URL=jdbc:postgresql://localhost:5432/oswl
export DB_USERNAME=oswl
export DB_PASSWORD=changeme
export OSWL_ENCRYPTION_KEY=$(openssl rand -base64 32)

./gradlew bootRun
```

> ⚠️ **`OSWL_ENCRYPTION_KEY`** — 저장된 VCS 액세스 토큰을 암호화하는 데 사용되는 32바이트 Base64 키입니다. `local` 모드에서는 더미 키가 자동으로 사용됩니다. `prod` 환경에서는 반드시 안정적이고 비밀스러운 값을 설정해야 합니다. 분실 시 기존에 저장된 VCS 자격증명을 복구할 수 없습니다.

애플리케이션은 기본적으로 포트 **8080**에서 시작됩니다.

---

## 설정 마법사

최초 실행 시(빈 데이터베이스), OsWL은 모든 요청을 `http://localhost:8080/setup`으로 리다이렉트합니다.

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

1. `http://localhost:8080/login`으로 이동합니다.
2. 설정 마법사에서 생성한 이메일과 비밀번호를 입력합니다.
3. **이중 인증(2FA)**이 활성화된 경우(관리자 설정 가능), 이메일로 발송된 6자리 OTP를 입력하라는 메시지가 표시됩니다.
   * `local` 모드에서는 OTP가 서버 로그에 표시됩니다: `*** OTP CODE: NNNNNN ***`
   * 개발용 단축키: 테스트 프로파일 사용 시 `000000`이 허용됩니다.
4. 임시 비밀번호로 첫 로그인 시, OsWL이 즉시 비밀번호 변경을 강제합니다.

---

## 테스트 데이터 시드 (로컬 전용)

로그인 후 다음을 호출하세요:

```
GET http://localhost:8080/data/test
```

이 엔드포인트(**`local` 프로파일에서만** 사용 가능):

* 기존의 모든 프로젝트, 스캔, 라이브러리, CVE를 삭제합니다.
* Maven과 npm 에코시스템의 여러 프로젝트, 다양한 심각도의 수십 개 CVE, 혼합된 라이선스 상태, 트렌드 시각화를 위한 다수의 히스토리 스캔이 포함된 풍부한 현실적 데이터셋으로 DB를 재구성합니다.

테스트 API 키도 다음에서 확인할 수 있습니다:

```
GET http://localhost:8080/data/test-api-key
```

---

## 다음 단계

* [첫 번째 VCS 저장소 연결](Quick-Import.md)
* [CLI로 스캔 제출](CLI-Integration.md)
* [보안 센터 탐색](Security-Center.md)
