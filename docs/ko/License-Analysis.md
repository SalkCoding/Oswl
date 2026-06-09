# 라이선스 분석

라이선스 분석 페이지는 최신 스캔에서 감지된 오픈소스 라이선스를 컴포넌트별로 보여주고, 정책과 충돌하는 항목을 표시합니다.

URL: `/projects/{id}/license`

---

## 라이선스 데이터 수집 방법

OsWL은 각 스캔된 라이브러리에 대해 **deps.dev**(Google의 Open Source Insights API)를 조회하여 SPDX 라이선스 표현식을 가져옵니다. 이를 전역 라이선스 정책과 비교하여 컴플라이언스 상태를 결정합니다.

---

## 라이선스 상태

각 컴포넌트에는 다음 네 가지 라이선스 상태 중 하나가 지정됩니다:

| 상태 | 의미 | 배지 색상 |
|---|---|---|
| **PERMITTED** | 정책에 의해 명시적으로 허용된 라이선스 | 초록 |
| **CAUTION** | 검토가 필요한 라이선스 (예: 약한 카피레프트) | 노랑 |
| **RESTRICTED** | 비호환으로 표시된 라이선스 (예: 독점 코드에서 사용된 강한 카피레프트) | 빨강 |
| **UNKNOWN** | SPDX 식별자 미조회 또는 미인식 | 회색 |

프로젝트 대시보드에 표시되는 전체 프로젝트 라이선스 배지는 모든 컴포넌트 중 **가장 심각한** 상태를 반영합니다.

---

## 라이선스 정책

라이선스 정책은 SPDX 식별자를 상태에 매핑한 목록입니다. `LICENSE_POLICY_MANAGE` 권한이 있는 시스템 관리자와 사용자가 정책 항목을 관리할 수 있습니다.

### 정책 항목 편집

1. 아무 프로젝트의 라이선스 분석 페이지를 엽니다.
2. **정책 관리** 클릭 (`LICENSE_POLICY_MANAGE` 필요).
3. SPDX ↔ 상태 매핑을 추가, 편집, 또는 제거합니다.

정책 항목은 **모든** 프로젝트에 전역으로 적용됩니다.

### 주요 SPDX 식별자

| SPDX ID | 라이선스 이름 | 일반적 정책 |
|---|---|---|
| `MIT` | MIT 라이선스 | PERMITTED |
| `Apache-2.0` | Apache 라이선스 2.0 | PERMITTED |
| `BSD-2-Clause` | BSD 2-Clause | PERMITTED |
| `BSD-3-Clause` | BSD 3-Clause | PERMITTED |
| `ISC` | ISC 라이선스 | PERMITTED |
| `LGPL-2.1-only` | GNU LGPL 2.1 | CAUTION |
| `LGPL-3.0-only` | GNU LGPL 3.0 | CAUTION |
| `MPL-2.0` | Mozilla Public License 2.0 | CAUTION |
| `GPL-2.0-only` | GNU GPL 2.0 | RESTRICTED |
| `GPL-3.0-only` | GNU GPL 3.0 | RESTRICTED |
| `AGPL-3.0-only` | GNU AGPL 3.0 | RESTRICTED |
| `SSPL-1.0` | Server Side Public License | RESTRICTED |

---

## 필터링

필터 바를 사용하여 다음 기준으로 라이선스 뷰를 좁힐 수 있습니다:

* **상태** — PERMITTED / CAUTION / RESTRICTED / UNKNOWN
* **에코시스템** — MAVEN / NPM / PYPI / 기타
* **검색** — 컴포넌트 이름 및 SPDX 식별자 전체 텍스트 검색

---

## AI 라이선스 인사이트

AI 제공업체가 설정된 경우, 각 완료된 스캔은 다음을 생성합니다:

* **라이선스 리스크 트렌드 인사이트** — 이전 스캔 대비 라이선스 컴플라이언스를 비교하는 서술 요약.
* **라이브러리별 AI 요약** — 컴포넌트 상세 패널에 표시되는 한 문장 컴플라이언스 리스크 설명.

---

## 버전 상태 이해하기

라이선스 분석 페이지는 deps.dev의 두 가지 추가 신호도 표시합니다:

| 신호 | 설명 |
|---|---|
| **최신 버전 아님** | 더 새로운 안정 버전 존재 — 업그레이드 권장 |
| **지원 중단됨** | 패키지 버전이 공식적으로 지원 중단됨; 지원 중단 사유가 표시됨 |

이 신호들은 정보 제공용이며 라이선스 상태에 영향을 미치지 않습니다.

---

## 보고서 보내기

**`LICENSE_EXPORT`** 권한(또는 시스템 관리자)이 있으면 NOTICE 파일과 SPDX SBOM을 다운로드할 수 있습니다. **프로젝트 멤버십**도 필요합니다. [권한 레이어](Authorization-Layers.md) 참고.
