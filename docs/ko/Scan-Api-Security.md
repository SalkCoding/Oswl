# CLI 스캔 API 보안

자동 스캔 제출(`POST /api/scan`) 보호 방식을 보안·운영 담당자용으로 정리합니다. 일상 설정은 [CLI 연동](CLI-Integration.md)을 참고하세요.

---

## 브라우저와 CLI의 차이

| 클라이언트 | 인증 | CSRF(브라우저 토큰) |
|------------|------|---------------------|
| **웹 UI** | 세션 쿠키 + 로그인 | 상태 변경 요청에 필요 |
| **CLI / CI** | Bearer API 키 + 제출자 자격 증명 | 미사용(세션 쿠키 없음) |

CSRF 보호는 **쿠키 기반 브라우저 세션**용입니다. CLI는 헤더·본문에 비밀을 직접 넣습니다.

CSRF 예외는 다음만 해당합니다.

- `POST /api/scan`
- `GET /api/scan/ping`

그 외 경로는 UI용 CSRF가 유지됩니다.

---

## 인증(세 단계)

| 단계 | 검증 내용 |
|------|-----------|
| 1 | `Authorization: Bearer` **프로젝트 API 키** |
| 2 | 본문의 제출자 **이메일·비밀번호** |
| 3 | 제출자에게 **`SCAN_SUBMIT`** 권한 및 해당 프로젝트 **`project_members`** 등록 |

역할 템플릿과 프로젝트 멤버십: [권한 레이어](Authorization-Layers.md).

---

## 남용 방지

- 실패 시 **속도 제한**(설정 가능).
- **감사 로그**: `SCAN.API_KEY_FAILURE`, `SCAN.AUTH_FAILURE`, `SCAN.AUTH_RATE_LIMITED`, 성공 시 `SCAN.INGEST`(제출자 **이메일** 기록). `scan_results`에는 `submitted_by_user_id` 컬럼을 저장하지 않습니다.
- CLI 키 발급은 `CLI_KEY.CREATE`로 기록되며, 행위자는 **현재 세션 사용자**입니다(`api_keys`에 발급자 ID 컬럼 없음).

---

## 스캔 상태 조회(브라우저)

`GET /api/scan/{scanId}/status` 는 **웹 세션**과 **프로젝트 멤버십**을 사용합니다.

---

## 운영 권장

- **HTTPS** 필수.
- API 키·제출자 비밀번호는 운영 비밀로 관리, 유출 시 교체.
- 감사 로그에서 스캔 인증 실패 모니터링.
- CI 전용 계정에 `SCAN_SUBMIT` 및 프로젝트 멤버십 부여.

---

## 관련 문서

- [CLI 연동](CLI-Integration.md)
- [권한 레이어](Authorization-Layers.md)
- [프로젝트 접근 제어](Project-Access-Control.md)
