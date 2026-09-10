# 백업 및 복구

실무에서 가장 흔한 사고는 PostgreSQL 손실이 아니라 **DB 백업은 멀쩡한데 `OSWL_ENCRYPTION_KEY`만 잃어버리는 경우**입니다. DB에 저장된 모든 VCS 접근 토큰, AI 공급자 API 키, Jira API 토큰, SMTP 메일 비밀번호는 이 키로 암호화되어 있습니다. 키를 잃으면 DB는 완벽히 복원되지만 그 안의 시크릿은 전부 영구적으로 복호화할 수 없게 됩니다 — 모든 VCS 연결, AI 공급자, Jira 연동을 처음부터 다시 설정해야 합니다.

이 문서는 [운영 배포](Production-Deployment-Checklist.md)의 운영자용 짝입니다 — 배포 방법은 그쪽을 먼저 읽고, 이 문서는 백업과 "복구가 실제로 되는지" 검증하는 것에만 집중합니다.

---

## 백업 대상

| 항목 | 위치 | 중요한 이유 |
|---|---|---|
| PostgreSQL 데이터베이스 | `deploy/docker/compose.prod.yml`의 `db-data-prod` 볼륨, 또는 관리형 PostgreSQL 인스턴스 | 프로젝트·스캔·발견 사항·사용자·암호화된 시크릿 등 모든 애플리케이션 데이터. |
| `OSWL_ENCRYPTION_KEY` | 주입 방식에 따라 다름(`.env.prod`, 시크릿 매니저 등) | DB에 저장된 모든 VCS 토큰/AI API 키/Jira 토큰/SMTP 비밀번호를 복호화합니다. **이게 없으면 위 DB 백업은 이런 시크릿이 필요한 용도로는 쓸모없습니다.** |
| 오프라인 스냅샷 저장소 | `OSWL_AIRGAPPED_IMPORT_DIR` (폐쇄망 모드 사용 시) | 복구 후 재임포트는 이것 없이도 가능하지만, 임포트 이력이 사라져 번들을 다시 받고 검증해야 합니다. |
| 임베디드 AI 모델 디렉터리 | `OSWL_EMBEDDED_AI_DIR` (기본값 `embedded-ai/`) | 재다운로드 가능([내장 AI](Embedded-AI.md) 참고) — 폐쇄망이라 재다운로드가 안 될 때만 백업하세요. |
| 설정 파일 | `.env.prod`, `deploy/docker/compose.prod.yml`, `application-prod.yaml` 오버라이드 | 이게 없으면 데이터는 멀쩡해도 인스턴스가 실제로 어떻게 설정되어 있었는지(SMTP 호스트, HSTS 설정, 기능 플래그 등) 알 수 없습니다. |

그 외(`OSWL_LOG_DIR`의 파일 로그, Quick Import 클론 임시 디렉터리)는 소모성이므로 백업하지 마세요.

---

## PostgreSQL 백업

```bash
# 스키마 + 데이터, 커스텀 포맷(병렬 복원 지원, plain SQL보다 작음)
pg_dump -Fc -h <host> -U <user> -d <database> -f oswl-$(date +%Y%m%d).dump
```

**권장 주기:** 매일 밤 전체 덤프, 30일 보관, 덤프 사이 시점 복구가 필요하면 PostgreSQL WAL 아카이빙도 함께. 덤프는 DB 호스트와 독립된 곳(오브젝트 스토리지, 다른 가용 영역)에 보관하세요 — 백업이 백업 대상 바로 옆에 있으면 그 대상을 앗아가는 사고에서 같이 사라집니다.

`OSWL_ENCRYPTION_KEY`는 **`pg_dump` 결과물과 별도의** 시크릿 매니저에 백업하세요 — 이 시크릿들을 저장 시 암호화하는 이유 자체가, 같은 백업 덩어리에 키와 암호문을 같이 두면 무의미해집니다.

---

## 복구 절차

1. **새 PostgreSQL 인스턴스를 준비**(또는 대상을 비우고) 덤프를 복원합니다:
   ```bash
   pg_restore -h <host> -U <user> -d <database> --clean --if-exists oswl-20260730.dump
   ```
2. **백업 당시와 동일한 `OSWL_ENCRYPTION_KEY`를 주입**합니다 — 다른 키(심지어 유효해 보이는 새로 생성한 키라도)를 쓰면 저장된 모든 시크릿이 복호화 불가능해지며, 데이터 손상과 구분되지 않습니다.
3. **복원된 DB를 대상으로 앱을 기동**합니다(`SPRING_PROFILES_ACTIVE=prod`, `ddl-auto: validate` — 복원된 스키마가 실행 버전과 이미 일치해야 합니다. 백업 시점보다 최신 버전으로 복구한다면 기동 전에 `src/main/resources/db/`의 미적용 수동 마이그레이션 스크립트를 먼저 적용하세요).
4. **아래 검증 스크립트를 실행**해 복구가 "프로세스가 떴다" 수준을 넘어 실제로 쓸 수 있는지 확인합니다.

```bash
OSWL_VERIFY_EMAIL=you@example.com \
OSWL_VERIFY_PASSWORD='...' \
OSWL_VERIFY_PROJECT_ID=1 \
./scripts/ops/verify-restore.sh https://your-instance.example.com
```

이 스크립트는 대화형입니다(실제 로그인과 동일하게 이메일 OTP 코드 입력을 기다립니다) 아래를 확인합니다:

| 확인 항목 | 무엇을 증명하는가 |
|---|---|
| `GET /actuator/health` → 200 | 복원된 DB와 주입된 키로 앱이 정상 기동했습니다. |
| 로그인 + OTP | 복원된 `users` 테이블에 대해 인증·세션 인프라가 동작합니다. |
| `GET /api/settings/vcs` → 200 | **`OSWL_ENCRYPTION_KEY`가 올바릅니다** — 저장된 VCS 토큰 최소 1건이 오류 없이 복호화되었습니다. 키가 틀리면 여기서 즉시 500으로 드러나며, 몇 주 뒤에야 발견되는 은근한 버그가 되지 않습니다. |
| `GET /projects/{id}/scan-history` → 200 | 스캔 이력이 복원되어 조회 가능합니다(복원된 데이터에 실제 존재하는 프로젝트 ID를 `OSWL_VERIFY_PROJECT_ID`로 설정해야 함). |
| `GET /api/admin/audit-logs` → 200 | 감사 로그가 복원되어 조회 가능합니다(`SYSTEM_ADMIN` 계정 필요). |

이 스크립트는 실제 정기 **리허설**로 실행하세요(예: 분기별로 임시 환경에서) — 작성된 이후 한 번도 실행해 본 적 없는 복구 절차는 검증된 절차가 아닙니다.

---

## 키 교체(rotation)

현재 **재암호화 배치 작업은 없습니다.** 오늘 `OSWL_ENCRYPTION_KEY`를 교체하면 기존에 암호화된 모든 값(VCS 토큰, AI API 키, Jira 토큰, SMTP 비밀번호)이 복호화 불가능해집니다 — 실무적인 교체 절차는: 키를 교체한 뒤, 각 설정 화면(VCS 연결, AI 공급자 키, Jira 연동, SMTP 자격 증명)에서 시크릿을 다시 입력해 새 키로 재암호화되도록 하는 것입니다. 제대로 된 교체 도구(구 키로 복호화 → 신 키로 재암호화, 영향받는 모든 테이블에 대해 in-place로)는 별도 백로그 항목으로 추적 중입니다 — 이 도구가 나오기 전까지는 `OSWL_ENCRYPTION_KEY`를 사실상 한 번 정하면 영구적인 값으로 취급하고, 그에 맞게 보호하세요(시크릿 매니저에, 저장소의 `.env` 파일이 아니라).
