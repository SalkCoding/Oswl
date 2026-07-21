# OsWL 개선 플랜 (2026-07-20 기준)

> 이 문서는 다른 AI 에이전트에게 그대로 전달하기 위한 작업 지시서다.
> 각 항목은 **내용 → 이유 → 수정 방향 → AI 작업 지시문** 순으로 구성된다.
> 우선순위: **P0** = 버그(즉시), **P1** = 사용자 요청 사항, **P2** = 품질/최적화, **P3** = 엔터프라이즈 고도화(장기).
>
> **상태 갱신 (2026-07-21)**: 1차 실행 스웜 완료 — **A-1~A-4, B-2, B-3, B-4, F절 전체(L1 제외: uncertain/설계 의도로 보류,
> L14 제외: 전담 패스 필요), G-1~G-3 모두 수정 완료** (대상 테스트 268건 통과, 미커밋).
> **남은 항목: B-1(모델별 가격표 — B-2 완료로 착수 가능), B-5(구조 리팩터링 4단계), L14(예외 메시지 108곳 i18n),
> G-4(EAGER→LAZY 단독 커밋), C-1~C-3, D-1~D-4, E-1~E-6.**
>
> (2026-07-20) 전체 코드 감사의 HIGH 8건 + MED 14건은 별도 스웜으로 수정 완료 → 커밋 654137b.

---

## A. 검증된 버그 / 즉시 수정 (P0)

### A-1. `AiUsageRecorderService.record()`의 `@Transactional`이 실제로 동작하지 않음

- **내용**: `src/main/java/com/salkcoding/oswl/service/ai/AiUsageRecorderService.java:48`의 `record()`는
  (1) package-private 메서드이고 (2) 같은 클래스의 public 메서드(`recordFromOpenAiUsage`, `recordFromAnthropicUsage`)에서
  자기호출(self-invocation)로 불린다. Spring 프록시 기반 `@Transactional`은 이 두 경우 모두 적용되지 않는다.
- **이유**: 지금은 `JpaRepository.save()` 자체 트랜잭션 덕에 저장은 되지만, B-2(FIFO 100개 유지)를 구현하면
  "save + 오래된 행 delete"가 하나의 트랜잭션이어야 하므로 실제 데이터 정합성 버그가 된다.
- **수정 방향**: `@Transactional`을 외부 진입점인 `recordFromOpenAiUsage` / `recordFromAnthropicUsage` (public)로 옮기고,
  `record()`의 `@Transactional`은 제거한다.
- **AI 작업 지시문**:
  ```
  AiUsageRecorderService.java에서 record() 메서드의 @Transactional 어노테이션을 제거하고,
  public 진입점인 recordFromOpenAiUsage()와 recordFromAnthropicUsage()에 @Transactional을 붙여라.
  record()는 private으로 바꿔라. 동작 변경 없이 트랜잭션 경계만 교정하는 작업이다.
  기존 테스트는 수정하지 마라.
  ```

### A-2. `AiUsageEventRepository.sumForDate()`의 `Object[]` 반환 검증 필요 (통계 0 표시 가능성)

- **내용**: `sumForDate()`는 단일 행 다중 컬럼 집계를 `Object[]`로 반환한다. Spring Data JPA에서 이 패턴은
  결과가 `Object[]{ Object[] }`로 중첩 래핑되는 케이스가 있어, `AiUsageStatsService.longAt(sums, 0)`이
  `Number`가 아닌 내부 배열을 만나 **항상 0을 반환**할 수 있다.
- **이유**: 오늘 사용량/비용 카드가 실제 사용량과 무관하게 0으로 표시되는 조용한 버그가 될 수 있다.
- **수정 방향**: 인터페이스 기반 projection 또는 DTO 생성자 표현식(`select new com.salkcoding.oswl.dto...`)으로 교체해
  타입 안전하게 만든다. `longAt`/`decimalAt` 헬퍼는 제거한다.
- **AI 작업 지시문**:
  ```
  AiUsageEventRepository.sumForDate()가 실제로 어떤 형태의 결과를 반환하는지 로컬 프로필(H2)에서 확인하고,
  Object[] 언패킹 대신 JPQL 생성자 표현식(select new ...) 기반 DTO(예: AiUsageSumsDto record —
  promptTokens, completionTokens, totalTokens, estimatedCostUsd 필드)로 교체하라.
  dailyTotalsSince()도 동일하게 DTO projection으로 바꿔라.
  AiUsageStatsService의 longAt/decimalAt 헬퍼를 제거하고 DTO 필드를 직접 사용하라.
  ```

### A-3. `pricingDisclaimer` 하드코딩 영어 문자열 (i18n 위반)

- **내용**: `AiUsageStatsService.java:78-79`에서 disclaimer 문구가 영어로 하드코딩되어 있다.
  이 프로젝트는 en/ko `messages.properties` 이중 언어 체계를 갖고 있다.
- **수정 방향**: 백엔드에서 키만 내려주고(`pricingDisclaimerKey`) 프론트에서 번역하거나,
  `MessageSource`로 로케일에 맞는 문자열을 내려준다. 기존 quick-import의 `_qiI18n` 패턴과 동일한 접근을 쓴다.
- **AI 작업 지시문**:
  ```
  AiUsageStatsService의 pricingDisclaimer 하드코딩 문자열을 제거하라.
  messages.properties와 messages_ko.properties에 ai.usage.pricingDisclaimer 키를 추가하고,
  AI 사용량 통계를 렌더링하는 템플릿(settings/tabs/ai.html)에서 해당 키를 사용해 표시하라.
  응답 DTO에서 pricingDisclaimer 필드는 제거하되 API 스펙 문서(controller/spec)도 함께 갱신하라.
  ```

### A-4. `LocalDate.now()` 타임존 미지정 — 사용량 일자 집계가 서버 TZ에 종속

- **내용**: `AiUsageRecorderService`, `AiUsageStatsService`가 `LocalDate.now()`를 시스템 기본 TZ로 호출한다.
  서버가 UTC로 배포되면 KST 기준 자정 전후 9시간 동안 사용량이 "어제" 날짜로 기록된다.
- **수정 방향**: `Clock` 빈을 주입받아 사용하고, 애플리케이션 표준 타임존을 설정(`application.yaml`)으로 명시한다.
- **AI 작업 지시문**:
  ```
  com.salkcoding.oswl 설정 클래스에 Clock 빈(@Bean Clock clock() { return Clock.system(ZoneId.of("Asia/Seoul")); },
  zone은 application.yaml의 oswl.timezone 프로퍼티로 외부화)을 추가하고,
  AiUsageRecorderService와 AiUsageStatsService의 LocalDate.now() 호출을 LocalDate.now(clock)으로 교체하라.
  다른 서비스의 now() 호출은 건드리지 마라(범위 최소화).
  ```

---

## B. 사용자 지적 사항 (P1) — 검증 결과 포함

### B-1. AI 가격표가 부정확하고 provider 단위 고정값임 (미해결 확인됨)

- **내용**: `AiUsageRecorderService`는 provider당 입력/출력 단가 1쌍만 갖는다(@Value 기본값:
  OpenAI 2.50/10.00, Anthropic 3.00/15.00, Gemini 1.25/5.00). 실제로는 **모델별로 가격이 다르고**,
  기본값이 2026년 7월 현재 시세와 어긋난다.
- **2026-07 실측 가격 (공식 문서로 재검증 필수)**:
  | Provider | 모델 | 입력/1M | 출력/1M |
  |---|---|---|---|
  | Anthropic | Claude Opus 4.8 | $5.00 | $25.00 |
  | Anthropic | Claude Sonnet 4.6 | $3.00 | $15.00 |
  | Anthropic | Claude Sonnet 5 (인트로가, ~2026-08-31) | $2.00 | $10.00 |
  | Anthropic | Claude Haiku 4.5 | $1.00 | $5.00 |
  | OpenAI | GPT-5.6 Sol | $5.00 | $30.00 |
  | OpenAI | GPT-5.6 Terra | $2.50 | $15.00 |
  | OpenAI | GPT-5.6 Luna | $1.00 | $6.00 |
  | Google | Gemini 3.1 Pro | $2.00 | $12.00 |
  | Google | Gemini 3.5 Flash | $1.50 | $9.00 |
  | Google | Gemini 3 Flash | $0.50 | $3.00 |
- **이유**: 비용 추정치가 관리자 의사결정(일일 호출 상한, provider 선택)의 근거인데 모델 무관 고정 단가면 수 배 오차가 난다.
- **수정 방향**: `modelName` 기반 가격 매핑으로 전환한다.
  1. `ai_model_pricing` 테이블(또는 최소한 yaml 맵 구조) 신설: `provider, model_pattern(prefix match), input_per_1m, output_per_1m, effective_from`
  2. 시드 데이터로 위 표를 넣되, 작업 시점에 공식 가격 페이지 3곳을 WebFetch로 확인해 갱신:
     - https://platform.claude.com/docs/en/about-claude/pricing
     - https://developers.openai.com/api/docs/pricing
     - https://ai.google.dev/gemini-api/docs/pricing
  3. `estimateCost()`는 `modelName` prefix 매칭 → 실패 시 provider 기본값 fallback + 로그 경고
  4. 관리자 설정 UI(설정 → AI 탭)에서 가격표 조회/수정 가능하게
- **AI 작업 지시문**:
  ```
  AI 사용 비용 추정을 provider 고정 단가에서 모델별 단가로 전환하라.

  1. 먼저 Anthropic/OpenAI/Google 공식 가격 문서를 조회해 2026년 현재 모델별 1M 토큰당 입력/출력 단가를 확정하라.
  2. domain/entity에 AiModelPricing 엔티티(provider enum, modelPattern, inputPer1m, outputPer1m — BigDecimal)와
     repository를 추가하고, 앱 시작 시 테이블이 비어 있으면 최신 시세로 시드하는 초기화 로직을 넣어라.
  3. AiUsageRecorderService.estimateCost()를 수정: modelName이 modelPattern으로 시작하는 행 중 가장 긴 패턴을
     선택해 단가를 적용하고, 매칭 실패 시 기존 @Value provider 기본값으로 fallback하며 warn 로그를 남겨라.
  4. 설정 → AI 탭(templates/settings/tabs/ai.html)에 가격표 목록/수정 UI를 추가하고
     SYSTEM_ADMIN 권한의 REST 엔드포인트(controller + spec 인터페이스 분리 규칙 준수)를 만들어라.
  5. en/ko messages.properties에 신규 라벨을 모두 추가하라. 기존 테스트는 수정하지 마라.
  ```

### B-2. `findTop10ByUsageDateOrderByCreatedAtDesc` 성능 + 이벤트 무한 증가 (미해결 확인됨)

- **내용**: `AiUsageEventRepository.findTop10ByUsageDateOrderByCreatedAtDesc`가 여전히 존재하고
  `AiUsageStatsService:45`에서 사용 중. `ai_usage_event` 테이블은 삭제 로직이 없어 무한 증가한다.
- **정확한 진단**: Top10 쿼리 자체는 `(usage_date, created_at)` 인덱스만 있으면 느려지지 않는다.
  진짜 문제는 (1) 인덱스가 없다는 점, (2) `sumForDate`/`dailyTotalsSince` 집계가 테이블 전체 성장에 비례해 느려지는 점,
  (3) 무한 증가하는 원시 이벤트 테이블 자체다.
- **수정 방향 (사용자 결정 사항)**: 이벤트는 **최대 100개만 FIFO로 유지**하고, 10개씩 × 10페이지 페이지네이션 UI/API를 만든다.
  단, 일별 요약(`dailyTotalsSince`, 7일 차트)은 원시 이벤트가 아니라 **집계 테이블(`AiDailyUsage` — 이미 존재)**에서 읽도록
  전환해야 100개 컷 이후에도 과거 일자 통계가 보존된다.
- **AI 작업 지시문**:
  ```
  ai_usage_event 저장/조회 구조를 다음과 같이 재설계하라.

  1. 기록 경로: AiUsageRecorderService.record()에서 save 후 총 행 수가 100을 넘으면
     created_at 오래된 순으로 초과분을 삭제하라(FIFO). save+delete는 하나의 @Transactional 경계 안에서 실행하라
     (주의: 이 클래스는 self-invocation 문제가 있으므로 public 진입점에 트랜잭션을 걸 것).
     동시성은 단순하게 가져가라 — 삭제 쿼리는 "delete where id in (select id ... order by created_at asc limit 초과분)" 형태.
  2. 동시에, 일별 합계는 원시 이벤트가 아닌 AiDailyUsage 집계 테이블에 upsert로 누적하라
     (usage_date + provider 단위: call_count, prompt_tokens, completion_tokens, total_tokens, estimated_cost_usd).
     AiUsageStatsService.dailyTotalsSince와 sumForDate를 AiDailyUsageRepository 기반으로 교체하라.
     이렇게 해야 이벤트 100개 컷 이후에도 7일 차트와 오늘 합계가 정확하다.
  3. 조회 API: GET /api/settings/ai/usage/events?page=0&size=10 형태의 Pageable 엔드포인트를 추가하라
     (controller/spec 분리, SYSTEM_ADMIN 권한, 최신순 정렬). findTop10... 메서드는 삭제하라.
  4. UI: 설정 → AI 탭의 최근 호출 목록을 10개/페이지 페이지네이션(최대 10페이지)으로 바꿔라.
     기존 quick-import repo browser의 페이지네이션 마크업 패턴을 재사용하라.
  5. ai_usage_event(usage_date, created_at) 복합 인덱스를 엔티티 @Table(indexes=...)에 추가하라.
  6. en/ko messages.properties 라벨 추가. 기존 테스트 수정 금지.
  ```

### B-3. quick-import.js 데드코드 — **대부분 이미 정리됨** (소량 잔존)

- **검증 결과**: 922줄 전체를 훑고 함수별 템플릿 사용처를 대조한 결과, "굉장히 많은" 수준의 데드코드는
  현재 없다. 확인된 잔존물:
  - `providerBrandColor()` (quick-import.js:871) — 템플릿에서 사용처 0건. **삭제 대상.**
  - `_qiI18n` 키 중 JS/HTML 어디에서도 참조되지 않는 키가 소수 있음(`importing`, `startFailed`,
    `loadReposFailed`, `title`, `viewSecurityCenter`, `importAnother`, `tryAgain` 등은 개별 검증 필요).
- **AI 작업 지시문**:
  ```
  static/js/projects/quick-import.js와 templates/projects/quick-import.html에서 데드코드를 제거하라.
  1. providerBrandColor() 함수를 삭제하라(템플릿 사용처 없음 — 삭제 전 grep으로 재확인).
  2. quick-import.html의 _qiI18n 객체의 각 키에 대해 quick-import.js와 quick-import.html 양쪽에서
     참조 여부를 grep으로 전수 조사하고, 어디서도 안 쓰는 키를 _qiI18n에서 제거하라.
     단, messages.properties의 키 자체는 다른 템플릿이 쓸 수 있으니 프로퍼티 파일은 건드리지 마라.
  3. 제거 후 로컬에서 quick import 화면이 렌더링되는지(콘솔 에러 0) 확인하라.
  ```

### B-4. `_qiI18n`이 존재하는 이유 — **버그 아님, 설계임** (설명 + 소규모 개선만)

- **설명**: `messages.properties`는 서버 사이드 리소스라 정적 JS 파일(`quick-import.js`)에서 직접 읽을 수 없다.
  `quick-import.html:516`의 `<script th:inline="javascript">` 블록이 Thymeleaf 표현식
  `/*[[#{quickImport.xxx}]]*/`로 **messages.properties 값을 렌더링 시점에 JS 객체로 주입하는 브릿지**다.
  즉 다국어가 이중으로 존재하는 게 아니라, 단일 소스(messages.properties)를 JS로 전달하는 통로다.
  `/*[[...]]*/ 'English text'` 뒤의 영문 문자열은 Thymeleaf natural template 문법상의 정적 프리뷰 기본값일 뿐이다.
- **잔여 개선점**: quick-import.js 안의 `_qi().xxx || 'English fallback'` 패턴이 fallback 문자열을
  한 번 더 중복시킨다(3중 정의). 브릿지가 항상 주입된다는 전제면 JS 쪽 fallback은 제거 가능하다.
- **AI 작업 지시문**:
  ```
  quick-import.js에서 _qi().key || '영문 fallback' 패턴의 하드코딩 영문 fallback 문자열을 제거하라.
  _qiFmt/_qiResolveError는 유지하되, _qi() 헬퍼가 키 부재 시 키 이름 자체를 반환하도록 바꿔
  (return (_qiI18n && _qiI18n[key]) ?? key 형태의 접근자) 누락 키를 화면에서 즉시 발견 가능하게 하라.
  이 패턴을 다른 페이지에서도 쓸 수 있도록 static/js/oswl-i18n.js 공통 유틸로 추출하는 것까지가 범위다.
  ```

### B-5. 프로젝트 전체 구조 정리 (유효한 지적 — 단계적 접근 필요)

- **현황 수치**: Java 300파일/29,164줄, 템플릿 50개/13,494줄. 문제 파일:
  - `DependencyManifestParserService.java` **1,611줄** — 전 에코시스템 매니페스트 파싱이 한 클래스에
  - `QuickImportService.java` **1,202줄** — clone/parse/scan/enrich 전 단계가 한 클래스에
  - `VulnerabilityEnrichmentService.java` **977줄**
  - `templates/settings/tabs/admin.html` **1,415줄**, `ai.html` **991줄** — 인라인 스크립트 포함 거대 템플릿
- **판단**: 프론트/백엔드/인프라 **리포 분리는 비권장**. Thymeleaf SSR 구조라 프론트가 백엔드 템플릿 엔진에
  결합되어 있어 분리 시 얻는 것이 없고 배포만 복잡해진다. 대신 **리포 내부 모듈화**가 맞다.
- **수정 방향** (한 번에 하지 말고 PR 단위로 분할):
  1. **서비스 분할**: `DependencyManifestParserService` → 에코시스템별 파서(`parser/MavenManifestParser`,
     `GradleManifestParser`, `NpmManifestParser`…) + 공통 인터페이스 + 디스패처.
     `QuickImportService` → phase별 협력 객체(`CloneStep`, `ParseStep`, `ScanStep`, `EnrichStep`)로 분해.
  2. **템플릿 분할**: `admin.html`/`ai.html`을 기능 단위 fragment로 쪼개고, 인라인 `<script>`를
     `static/js/settings/*.js`로 추출(단, `_qiI18n` 같은 서버 주입 브릿지는 템플릿에 남긴다).
  3. **패키지 정리**: `service/` 평면 패키지(40+ 클래스)를 도메인별 하위 패키지(`service/scan`, `service/license`,
     `service/vcs`, `service/ai` — ai는 이미 존재)로 재배치.
  4. **인프라 정리**: `db/*.sql` 수동 스크립트를 Flyway 마이그레이션으로 흡수(C-1 참조).
- **AI 작업 지시문** (각 단계를 별도 세션/PR로):
  ```
  [1단계] DependencyManifestParserService(1,611줄)를 리팩터링하라.
  service/parser/ 패키지를 만들고 ManifestParser 인터페이스(supports(ecosystem), parse(...))를 정의한 뒤
  Maven/Gradle/Npm/기타 에코시스템별 구현 클래스로 로직을 이동하라. 기존 public 메서드 시그니처는
  파사드로 유지해 호출부(QuickImportService 등) 변경을 최소화하라. 동작 변경 금지, 순수 이동 리팩터링.
  완료 후 ./gradlew compileJava와 관련 테스트(DependencyManifestParser*, QuickImport*)만 실행해 green 확인.

  [2단계] QuickImportService(1,202줄)를 phase별 협력 객체로 분해하라(동일 원칙).

  [3단계] templates/settings/tabs/admin.html(1,415줄)의 인라인 스크립트를
  static/js/settings/admin/*.js로 추출하고 템플릿을 기능 fragment로 분할하라.
  서버 i18n 주입이 필요한 문자열은 quick-import의 _qiI18n 패턴을 따라 최소한의 브릿지 객체만 인라인으로 남겨라.

  [4단계] service/ 평면 패키지를 service/scan, service/license, service/vcs로 재배치하라.
  IDE 수준의 안전한 move-refactoring만 수행하고 클래스 내용은 변경하지 마라.
  ```

---

## C. 최적화 / 기술 부채 (P2)

### C-1. DB 스키마 마이그레이션 도구(Flyway) 부재

- **내용**: prod는 `ddl-auto: validate`인데 마이그레이션 도구가 없어 스키마 변경을 수동 SQL
  (`db/ai_embedded_config.sql` 같은 파일)로 배포해야 한다. local은 `ddl-auto: update`라 환경 간 스키마 드리프트 위험.
- **이유**: 엔터프라이즈 배포에서 "업그레이드 경로"는 필수다. 수동 SQL은 순서/누락 사고의 온상.
- **AI 작업 지시문**:
  ```
  Flyway를 도입하라. build.gradle에 flyway-core(+flyway-database-postgresql)를 추가하고,
  현재 엔티티 스키마 전체를 V1__baseline.sql로 생성하라(로컬 H2로 앱을 띄워 생성된 DDL을 기준으로 하되
  PostgreSQL 문법으로 작성, H2 PostgreSQL 호환 모드에서 동일 적용 가능해야 함).
  기존 db/*.sql 수동 스크립트 내용을 V2 이후 버전으로 흡수하고 원본 파일은 삭제하라.
  application-prod.yaml은 ddl-auto: validate 유지 + flyway 활성화,
  application-local.yaml은 기존 DB 파일과의 충돌을 피하기 위해 baseline-on-migrate: true를 설정하라.
  ```

### C-2. 외부 API 클라이언트(OSV, deps.dev, GitHub) 캐싱/재시도 정책 점검

- **내용**: 스캔/enrichment마다 외부 API를 호출한다. 동일 패키지 버전의 CVE/메타데이터는 단기간 불변인데
  캐시 계층이 있는지 불명확하고, 레이트리밋 대응이 클라이언트별로 제각각일 가능성이 높다.
- **이유**: 대량 프로젝트 스캔 시 응답 시간과 외부 API 쿼터가 병목이 된다.
- **AI 작업 지시문**:
  ```
  client/ 패키지의 OsvClient, DepsDevClient, GitHub 클라이언트를 감사하라.
  (1) 각 클라이언트의 타임아웃/재시도/레이트리밋 처리 현황을 표로 정리하고,
  (2) 패키지 좌표+버전 단위 응답에 Spring Cache(@Cacheable + Caffeine, TTL 24h) 적용을 제안/구현하라.
  (3) 429/5xx에 지수 백오프 재시도(최대 3회)를 공통 유틸로 통일하라.
  먼저 감사 결과를 보고하고 승인 후 구현할 것.
  ```

### C-3. 인메모리 상태의 수평 확장 불가 지점 식별

- **내용**: 단일 세션 강제, quick-import 작업 큐(슬롯 3개), SSE 스트림 등이 인메모리로 보인다.
  서버 재시작 시 진행 중 import가 유실되고(프론트에 `sessionExpired` 처리 존재), 다중 인스턴스 배포가 불가능하다.
- **이유**: 엔터프라이즈는 HA(고가용성)를 요구한다. 최소한 "무엇이 인메모리인지" 목록화가 선행돼야 한다.
- **AI 작업 지시문**:
  ```
  코드베이스에서 인메모리 상태(static/필드 컬렉션, ConcurrentHashMap, 세션 레지스트리, 작업 큐)를 전수 조사해
  "다중 인스턴스 배포 시 깨지는 지점" 목록을 만들어라. 각 항목에 대해 DB 테이블/Redis 이전 난이도를
  상/중/하로 평가한 보고서만 작성하라. 구현은 하지 마라.
  ```

---

## D. 사용성 / 기능 고도화 (P2)

### D-1. 정기 재스캔 + 신규 CVE 자동 감지 (연속 모니터링)

- **내용**: 현재 스케줄러는 `TrashCleanupScheduler`, `DeferExpiryScheduler` 2개뿐. 스캔은 사용자가 수동으로
  돌릴 때만 실행되므로, **스캔 이후 공개된 CVE는 다음 수동 스캔까지 아무도 모른다.**
- **이유**: SCA 제품의 핵심 가치는 "새 CVE가 뜨면 알려주는 것"이다. 이게 없으면 일회성 스캐너에 그친다.
  경쟁 제품(Snyk, Dependency-Track)의 기본 기능.
- **수정 방향**: 이미 저장된 `ScanComponent` 목록에 대해 매일 새벽 OSV를 재조회하는 스케줄러 →
  신규 CVE 발견 시 프로젝트 담당자에게 이메일 알림 + 대시보드 배지.
- **AI 작업 지시문**:
  ```
  scheduler/ 패키지에 VulnerabilityRefreshScheduler를 추가하라.
  (1) 매일 새벽 3시(cron, oswl.rescan.cron으로 외부화) 활성 프로젝트의 최신 ScanResult에 속한
      고유 (ecosystem, name, version) 좌표를 배치로 OSV에 재조회한다. 기존 VulnerabilityEnrichmentService 재사용.
  (2) 직전 상태 대비 새로 발견된 CVE만 diff로 추출해, 프로젝트 멤버에게 기존 Mail 인프라로 요약 메일을 보낸다
      (프로젝트당 1통, CVE 목록 요약). 알림 on/off는 프로젝트 설정에 boolean 컬럼으로 추가.
  (3) AuditLogService로 시스템 이벤트를 기록한다.
  (4) 관리자 설정에서 수동 "지금 재스캔" 트리거 버튼을 제공한다.
  en/ko 메시지, controller/spec 분리, @PreAuthorize 규칙을 준수하라.
  ```

### D-2. 알림 채널 확장 (Slack/Teams/Webhook)

- **내용**: 알림 수단이 이메일뿐이다. 코드베이스에 webhook/Slack 연동이 전혀 없음(grep 확인).
- **이유**: 엔터프라이즈 보안팀의 실제 워크플로는 메신저/SIEM 중심이다. D-1의 가치를 배가시킨다.
- **AI 작업 지시문**:
  ```
  범용 아웃바운드 Webhook 알림 기능을 추가하라.
  설정 → 알림 탭(신규)에서 URL + 시크릿(HMAC-SHA256 서명 헤더) + 이벤트 타입(신규 CVE, 스캔 완료, 정책 위반)을
  등록/테스트 발송할 수 있게 하라. 페이로드는 JSON 표준 스키마(event, project, severity, items[])로 설계하고
  Slack Incoming Webhook 호환 포맷 변환 옵션을 제공하라. 시크릿은 기존 OSWL_ENCRYPTION_KEY 암호화 유틸로 저장하라.
  발송 실패는 3회 재시도 후 감사 로그에 WARN 기록. D-1과 이벤트 발행 지점을 공유하도록 내부 이벤트
  (ApplicationEventPublisher) 기반으로 구현하라.
  ```

### D-3. 리포트 내보내기 (경영/감사용 PDF·정기 리포트)

- **내용**: 현재 CSV 내보내기(BOM 처리까지 완료)는 있으나, 경영진/감사 제출용 요약 리포트가 없다.
- **이유**: 엔터프라이즈 구매 결정권자가 보는 것은 대시보드가 아니라 월간 리포트다. 컴플라이언스 감사 대응에도 필수.
- **AI 작업 지시문**:
  ```
  프로젝트 단위 보안 요약 리포트(HTML → 인쇄용 CSS) 기능을 추가하라.
  security-center 데이터(심각도 분포, 상위 CVE, 라이선스 위반, 위험 추이 차트)를 단일 인쇄 친화 페이지
  /projects/{id}/report로 렌더링하고 "인쇄/PDF 저장" 버튼을 제공하라(서버사이드 PDF 라이브러리 도입은 하지 말 것 —
  브라우저 인쇄로 충분). 차트는 Chart.js 렌더 후 인쇄 시 깨지지 않도록 고정 크기로. en/ko 지원.
  ```

### D-4. 온보딩/빈 화면(Empty state) 개선

- **내용**: 사용성 테스트 피드백 반영이 v1.0.3에서 진행됐지만, 신규 설치 직후 "데이터 없는 상태"에서
  다음 행동을 안내하는 장치(빈 프로젝트 화면 → Quick Import 유도, CLI 연동 가이드 링크)가 체계적인지 재점검 필요.
- **AI 작업 지시문**:
  ```
  주요 목록 화면(projects, scan-history, security-center, license, risk-trend)의 빈 상태 UI를 감사하라.
  각 화면에 대해 (a) 빈 상태 일러스트/문구가 있는지, (b) 다음 행동 CTA(Quick Import, CLI 가이드 링크)가 있는지
  표로 정리하고, 없는 화면에 기존 owl 일러스트 자산(_owl-error.html 패턴 참고)을 재활용한
  빈 상태 fragment를 추가하라. en/ko 문구 포함.
  ```

---

## E. 엔터프라이즈 시장 대응 (P3)

### E-1. 표준 SBOM 수출입 (CycloneDX / SPDX JSON)

- **내용**: SPDX 라이선스 분류(`SpdxLicenseRegistry`)와 라이선스 페이지 일부에 sbom 관련 코드가 있으나,
  **CycloneDX/SPDX 표준 포맷의 SBOM 파일 export/import**가 있는지 불명확하다.
- **이유**: 미국 행정명령 14028, EU CRA 등으로 SBOM 제출이 조달 요건이 됐다. 엔터프라이즈 SCA에서
  CycloneDX export는 체크박스 기능(없으면 검토 탈락)이다. Dependency-Track 등과의 상호운용도 이걸로 판가름난다.
- **AI 작업 지시문**:
  ```
  먼저 LicenseController/LicenseService의 기존 sbom 관련 코드가 무엇을 하는지 파악해 보고하라.
  그 다음 CycloneDX 1.6 JSON export를 구현하라: GET /api/projects/{id}/sbom?format=cyclonedx 로
  최신 ScanResult의 컴포넌트(purl, 버전, 라이선스, 알려진 취약점 참조 포함)를 표준 스키마로 직렬화한다.
  cyclonedx-core-java 라이브러리를 build.gradle에 추가해 사용하라(직접 JSON 조립 금지 — 스키마 검증이 목적).
  두 번째 단계로 CycloneDX SBOM "가져오기"(파일 업로드 → 프로젝트 생성 + 스캔)를 quick-import 옆에 추가하라.
  ```

### E-2. SSO (OIDC 우선, SAML 차후)

- **내용**: 인증이 자체 이메일 OTP 2FA뿐이다.
- **이유**: 엔터프라이즈 보안팀의 1순위 질문이 "Okta/Entra ID 붙나요?"다. SSO 없으면 계정 수명주기 관리가
  안 돼서 보안 제품이 오히려 보안 감사 지적사항이 되는 역설이 생긴다.
- **AI 작업 지시문**:
  ```
  spring-boot-starter-oauth2-client 기반 OIDC 로그인을 추가하라.
  (1) 관리자 설정에서 IdP(issuer-uri, client-id/secret — secret은 암호화 저장)를 등록하는 UI/API,
  (2) OIDC 로그인 성공 시 email 클레임으로 기존 사용자 매칭(자동 프로비저닝은 관리자 옵션),
  (3) OIDC 사용자는 OTP 단계 생략(IdP가 MFA 담당), 로컬 계정과 공존,
  (4) 기존 단일 세션 강제/신뢰 기기 로직과의 상호작용을 SecurityConfig에서 명확히 분리.
  기존 auth 모듈 구조(auth/config, auth/security, auth/service)를 따르라. 큰 작업이므로 설계 문서를 먼저 쓰고 승인받아라.
  ```

### E-3. 정책 게이트의 CI/CD 통합 강화

- **내용**: CLI 스캔(`docs/CLI-Integration.md`)과 라이선스/CLI 키 정책은 있으나, CI 파이프라인에서
  "심각도 임계값 초과 시 빌드 실패" 같은 게이트 계약이 1급 기능인지 확인 필요.
- **이유**: 엔터프라이즈에서 SCA의 실사용처는 대시보드가 아니라 PR 차단이다. exit code 계약 + 머신리더블
  출력이 있어야 Jenkins/GitHub Actions에 물릴 수 있다.
- **AI 작업 지시문**:
  ```
  스캔 API/CLI 흐름을 감사하고 정책 게이트를 추가하라.
  (1) 프로젝트별 게이트 정책(차단 기준: CVSS 임계값, 심각도 개수, 금지 라이선스)을 설정하는 엔티티+UI,
  (2) 스캔 제출 응답에 gate: PASS/FAIL과 위반 목록을 포함,
  (3) docs/CLI-Integration.md에 exit code 규약(0=pass, 1=gate fail, 2=error)과
      GitHub Actions/Jenkins 예제 스니펫을 en/ko로 추가하라.
  ```

### E-4. 운영 가시성 (Actuator + 메트릭)

- **내용**: Spring Actuator 미사용(grep 확인). 헬스체크/메트릭 엔드포인트가 없다.
- **이유**: 엔터프라이즈 운영팀은 로드밸런서 헬스체크와 Prometheus 스크레이프를 전제한다. 도입 비용 대비 효과가 가장 큰 항목.
- **AI 작업 지시문**:
  ```
  spring-boot-starter-actuator와 micrometer-registry-prometheus를 추가하라.
  /actuator/health(비인증 허용), /actuator/prometheus(SYSTEM_ADMIN 또는 별도 토큰)만 노출하고 나머지는 차단.
  SecurityConfig에 명시적 매처를 추가하고, 스캔 처리 시간/외부 API 호출 실패율/AI 호출 수를
  커스텀 Micrometer 메트릭으로 계측하라. application-prod.yaml에 설정 예시를 주석으로 문서화하라.
  ```

### E-5. 컨테이너 배포 자산 (Dockerfile + compose)

- **내용**: 배포 자산(Dockerfile, docker-compose, Helm)이 리포에 없는 것으로 보인다.
- **이유**: 엔터프라이즈 PoC는 "docker compose up 한 줄"로 시작한다. 설치 마찰이 곧 이탈이다.
- **AI 작업 지시문**:
  ```
  멀티스테이지 Dockerfile(gradle build → JRE 25 slim 런타임, non-root 유저)과
  docker-compose.yaml(app + postgres, OSWL_ENCRYPTION_KEY 등 필수 env 명시, 볼륨 영속화)을 작성하라.
  docs/에 Deployment.md(en/ko)를 추가해 프로덕션 체크리스트(암호화 키 생성, SMTP, DB 백업)를 문서화하라.
  임베디드 AI(embedded-ai/ 디렉터리의 로컬 모델 바이너리)는 선택 마운트 볼륨으로 처리하라.
  ```

### E-6. 감사 로그 보존 정책 + 외부 반출

- **내용**: `AuditLogService`는 잘 쓰이고 있으나 보존 기간/용량 정책과 외부 반출(CSV/syslog) 기능 확인 필요.
- **이유**: ISO 27001/ISMS 감사는 "감사 로그를 N년 보존하고 반출 가능한가"를 묻는다.
- **AI 작업 지시문**:
  ```
  감사 로그에 (1) 보존 기간 설정(기본 365일, 관리자 설정 가능)과 초과분을 삭제하는 스케줄러,
  (2) 기간 필터 CSV 내보내기(기존 CSV BOM 처리 유틸 재사용)를 추가하라.
  삭제 전 마지막 내보내기 시점을 기록해 "내보내지 않은 로그 삭제 임박" 경고를 관리자 화면에 표시하라.
  ```

---

## F. 코드 감사 LOW 백로그 (2026-07-20 감사 확정분, 미수정)

> 전체 감사(백엔드 296클래스 + 프론트 전체)에서 HIGH/MED는 수정 완료. 아래 LOW는 코드 근거가 확인된
> 미수정 잔여분이다. `(uncertain)` 표기는 감사자가 확정 버그가 아닐 수 있다고 본 항목.
> 한 항목 = 한 수정 단위로 다른 AI에게 개별 전달 가능하도록 file:line + 수정 방향을 유지한다.

### F-1. 백엔드 LOW 16건

| # | 위치 | 내용 | 수정 방향 |
|---|---|---|---|
| L1 | `EmbeddedAiService.java:150-195` | `start()`가 synchronized로 모델당 최대 90초 대기 → 그동안 `status()`/`stop()` 블록(설정 UI 타임아웃 가능) (uncertain) | 시작을 비동기화하거나 상태 조회를 락 밖으로 분리 |
| L2 | `EmbeddedAiService.java:120-125,221-226` | 외부/이전 실행 llama-server가 running으로 보이지만 `stop()`은 자식만 종료 → Stop 눌러도 running 표시 (uncertain) | 포트 점유 프로세스 식별 or "외부 인스턴스" 상태 별도 표기 |
| L3 | `ScanIngestService.java:148-158` | `findOrCreateLibrary` 동시 ingest race → unique 제약 위반 500 | 제약 위반 catch 후 재조회(1회 재시도) |
| L4 | `VulnerabilityEnrichmentService.java:101-166` | enrich 전체(외부 API+AI 수 분)가 단일 트랜잭션 → 커넥션 장기 점유 | 단계별 트랜잭션 분리(외부 호출은 트랜잭션 밖) |
| L5 | `AiSettingController.java:128-131,165-178` | activate race 시 active 2개 → `findByActiveTrue()` 500 | active 유니크 부분 인덱스 or 낙관적 락 + 정합 복구 |
| L6 | `GlobalExceptionHandler.java:71-79` | `IllegalArgumentException`→404 매핑이라 검증 실패도 404 | 검증성 예외는 400으로 분리(전용 예외 타입) |
| L7 | `BulkStatusRequest.java:10` + `SecurityCenterController.java:53` | `ids` `@NotNull`/`@Valid` 부재 → `{"ids":null}` 500 | Bean Validation 추가 |
| L8 | `LicensePolicyService.java:101-111` | `@PostConstruct @Transactional`은 프록시 미적용 → seeding 부분 커밋 가능 | `ApplicationRunner` or `TransactionTemplate`로 전환 |
| L9 | `AiResponseSanitizer.java:49` 등 | `replace("\\n","\n")`이 정상 리터럴도 변환 + `aiLicenseSummary`/배치 CVE 경로 sanitizer 미적용 (uncertain) | sanitizer 적용 지점 통일, 변환 규칙 재검토 |
| L10 | `UserManagementService.java:136-154` | `handleLoginFailure` read-increment race → 잠금 경계 부정확 | DB 원자적 increment 쿼리로 교체 |
| L11 | `GitHubService.java:120,139`, `QuickImportService.java:528,548` | repo 목록 `per_page=100` 페이지네이션 없음 | Link 헤더 따라 전체 페이지 순회(상한 두기) |
| L12 | `ScanHistoryService.java:44-63` | 스캔 행당 count/find N+1 | 집계 쿼리 일괄 조회로 교체 |
| L13 | `QuickImportService.java:1133-1149` | SSE로 평문 apiToken 반복 전송(폴링 1회 마스킹 정책과 불일치) (uncertain) | SSE도 첫 전송 후 마스킹으로 정책 통일 |
| L14 | 백엔드 예외 메시지 108곳 | 영어 하드코딩 예외 메시지가 JSON으로 노출(ko UI에 영어) | errorKey 기반 응답으로 점진 전환(quick-import 패턴 재사용) |
| L15 | `ScanVersionDiffAnalyzer.java:106-112` | 동일 이름 2버전 존재 시 `(a,b)->a`로 유실 → diff 부정확 | (name,version) 복합 키로 비교 |
| L16 | `VulnerabilityEnrichmentService.java:935-940` | `aggSec`가 severity null 포함 → `RiskTrendService` 기준과 불일치, AI 인사이트 델타 왜곡 (uncertain) | null 제외로 기준 통일 |

### F-2. 프론트엔드 LOW 6건

| # | 위치 | 내용 | 수정 방향 |
|---|---|---|---|
| FL1 | `settings/index.html:5,14` | plain `<script>`에 `/*[[#{...}]]*/` → 미인라인 → 모든 설정 탭의 403 메시지가 영어 폴백 | `th:inline="javascript"` 추가 |
| FL2 | `fragments/topbar.html:406`, `component-detail/fragments/detail-content.html:701` | `'X-CSRF-TOKEN'` 오타(실제 `X-XSRF-TOKEN`) — 현재는 fetch 래퍼가 흡수 | `oswlJsonHeaders()`로 통일 |
| FL3 | `projects/index.html:643-646` | SSE 지속 실패 시 8초 간격 무한 `location.reload()` 루프 | 실패 횟수 상한 + 401 감지 시 로그인 유도 |
| FL4 | `detail-content.html:705` | 슬라이드아웃 내 AI 재생성 성공 시 전체 리로드 → 패널 상태 소실 | htmx로 `#slideout-content`만 갱신 |
| FL5 | `quick-import.js:78-81` | 패널 미개방에도 페이지 로드 즉시 타이머/fetch/폴링 시작 + `_scanWatcher` 단일 슬롯이라 연속 임포트 시 마지막 것만 감시 | init을 첫 개방 시점으로 지연, watcher Map 관리 |
| FL6 | `projects/index.html:499` + `git-integration.html:83-99` | 여는 버튼 없는 Git Integration 패널이 매 로드마다 `/api/github/status` fetch (+ `static/js/projects/git-integration.js`는 어떤 템플릿도 로드 안 하는 구버전 파일) | include 제거 or lazy-init; 미사용 js 파일 삭제 |
| FL7 | `security-center/index.html:353-355`, `settings/tabs/vcs.html:46-58 외` | `x-cloak` 누락 초기 깜빡임(코스메틱 묶음) | 해당 x-show 요소에 x-cloak 추가 |

---

## G. 성능 최적화 추가 발견 (2026-07-20 스윕, 미수정)

> 감사와 별개로 성능 관점 스윕에서 확인한 항목. F절의 L4(장기 트랜잭션)/L11(페이지네이션)/L12(N+1),
> C-2(외부 API 캐싱), B-2(인덱스)와 함께 성능 트랙을 구성한다.

### G-1. `regenerateRecentInsights`의 전량 로드 후 메모리 정렬
- **내용**: `VulnerabilityEnrichmentService.java:878` — `scanResultRepository.findAll()`로 **모든 프로젝트의 전체 스캔**을
  로드한 뒤 메모리에서 정렬해 15개만 쓴다. 스캔이 쌓일수록 선형으로 느려지고 메모리를 낭비한다.
- **AI 작업 지시문**:
  ```
  ScanResultRepository에 findTop15ByStatusOrderByScannedAtDesc(ScanStatus status) 파생 쿼리를 추가하고
  VulnerabilityEnrichmentService.regenerateRecentInsights의 findAll().stream()... 블록을 그것으로 교체하라.
  scannedAt null 정렬 순서가 기존 로직(nullsLast)과 동일한지 확인하고, 다르면 @Query로 명시하라.
  ```

### G-2. Hibernate `default_batch_fetch_size` 미설정
- **내용**: LAZY 연관/컬렉션 접근 시 1건씩 SELECT가 나간다(전역 N+1 완화 장치 부재).
- **AI 작업 지시문**:
  ```
  application.yaml의 spring.jpa.properties에 hibernate.default_batch_fetch_size: 50을 추가하라.
  변경 후 로컬에서 security-center와 scan-history 페이지를 열어 SQL 로그(H2)에서 IN 절 배치가 적용되는지 확인하라.
  ```

### G-3. HTTP 응답 압축·정적 자원 캐시 헤더 부재
- **내용**: `server.compression` 미설정, 정적 자원(tailwind.css, chart.umd.min.js 등) long-cache 헤더 미설정 —
  매 요청 원본 전송.
- **AI 작업 지시문**:
  ```
  application.yaml에 server.compression.enabled: true (mime-types에 text/html,text/css,application/javascript,
  application/json 포함, min-response-size 2KB)를 추가하고,
  WebMvcConfigurer의 addResourceHandlers로 /css,/js,/img,/icon,/graphic에 cache-control(max-age 7일,
  content 버저닝 있으면 immutable) 설정을 추가하라. 템플릿 링크 캐시버스팅(예: ?v=@buildTimestamp)이
  가능하면 함께 적용하되, 범위가 커지면 헤더 설정까지만 하고 보고하라.
  ```

### G-4. `ScanComponent.library` EAGER 연관
- **내용**: `ScanComponent.java:38`의 `@ManyToOne(fetch = EAGER)` — 컴포넌트 목록 로드 시 항상 Library를 끌고 온다.
  주요 경로는 fetch join을 쓰고 있어 당장 병목은 아니나, 파생 쿼리 추가 시마다 숨은 N+1을 만든다.
- **AI 작업 지시문**:
  ```
  ScanComponent.library를 LAZY로 전환하고, 컴파일 후 ScanComponent를 조회하는 모든 리포지토리 메서드/사용처를
  훑어 LazyInitializationException 가능 지점에 fetch join(또는 @EntityGraph)을 추가하라.
  변경 후 security-center, component-detail, scan-history, version-diff 페이지 로드를 로컬에서 확인하라.
  위험도가 있는 변경이므로 단독 커밋으로 분리하라.
  ```

---

## 부록: 이번 검증에서 확인된 사실 요약

| 의문점 | 검증 결과 |
|---|---|
| AI 가격표 부정확 | **미해결** — provider 고정 단가, 모델별 아님. B-1 참조 |
| findTop10 성능 | **미해결** — 메서드/무한 증가 테이블 그대로. 단 병목의 본질은 집계 쿼리+인덱스 부재. B-2 참조 |
| quick-import.js 데드코드 | **대부분 정리됨** — `providerBrandColor()` 등 소량 잔존. B-3 참조 |
| `_qiI18n` 존재 이유 | **정상 설계** — 서버 i18n을 JS로 주입하는 Thymeleaf 브릿지. 중복 아님. B-4 참조 |
| 프로젝트 구조 엉망 | **유효** — 1,600줄대 god class, 1,400줄대 템플릿 존재. 리포 분리 대신 내부 모듈화 권장. B-5 참조 |

추가로 발견된 버그: A-1(트랜잭션 무효), A-2(통계 0 가능성), A-3(i18n 위반), A-4(타임존).
현재 브랜치 `v1.0.3`에 미커밋 변경 62개 파일이 있음 — 위 작업 착수 전 커밋/정리가 선행돼야 한다.
