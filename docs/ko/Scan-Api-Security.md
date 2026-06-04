# CLI 스캔 API 보안 (`POST /api/scan`)

영문 전문: [Scan-Api-Security.md](../Scan-Api-Security.md)

## 요약

- 브라우저 CSRF 토큰은 **CLI 스캔 ingest에 사용하지 않습니다** (의도된 설계).
- 인증: `Authorization: Bearer <project_api_key>` + 본문 `submitterEmail` / `submitterPassword` + `SCAN_SUBMIT` 권한 + 프로젝트 멤버십.
- 실패 시 rate limit 및 감사 로그(`SCAN.API_KEY_FAILURE`, `SCAN.AUTH_FAILURE` 등)가 기록됩니다.

## 본문 비밀번호 — 장기 개선 (#39)

현재 CLI는 API 키와 함께 `submitterPassword`를 JSON 본문으로 보냅니다. 비밀번호는 DB `rawJson`에 저장되지 않지만, TLS 위에서 매 요청마다 전송됩니다. 권장 방향: **scoped scan token** 교환, PAT/OAuth, 또는 mTLS. 상세는 영문 문서의 “Submitter password in JSON body” 절을 참고하세요.
