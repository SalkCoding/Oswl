# Project Guidelines

## Tech Stack Overview
- **Backend:** Spring Boot (Java 25, Spring WebMVC, Data JPA), Gradle, Lombok
- **Frontend:** HTML, CSS, JS, Thymeleaf (Optional: Tailwind CSS, HTMX, Alpine.js, Graphing libraries)
- **Database:** PostgreSQL

## Project Structure
```text
OsWL/
├── src/
│   ├── main/
│   │   ├── java/com/salkcoding/oswl/   # Backend Java source files
│   │   └── resources/
│   │       ├── application.yaml        # Spring Boot configuration
│   │       ├── static/                 # Static assets (JS, CSS, images)
│   │       └── templates/              # Thymeleaf HTML templates
│   └── test/
│       └── java/com/salkcoding/oswl/   # Backend test files
├── build.gradle                        # Gradle build configuration
└── settings.gradle                     # Gradle project settings
```

## Code Style & Architecture
- **Backend (Spring Boot):**
  - Follow standard layered architecture: `Controller` -> `Service` -> `Repository`.
  - Use constructor injection via Lombok (`@RequiredArgsConstructor`) instead of `@Autowired`.
  - Maintain entities representing PostgreSQL tables, leveraging Spring Data JPA for persistence.
  - Feel free to introduce additional Java libraries if they solve the problem cleanly (specify dependencies via `build.gradle`).
- **Frontend (HTML/CSS/JS/Thymeleaf):**
  - Always design the frontend with backend integration in mind. When creating UI components, account for dynamic behaviors (e.g., lists that will grow dynamically via backend communication) and structure the code to accommodate future data bindings or asynchronous updates.
  - Keep Thymeleaf templates (`.html`) inside `src/main/resources/templates/`.
  - Actively use Thymeleaf standard dialects (e.g., `th:text`, `th:each`, `th:if`) for server-side rendering and dynamic data binding.
  - Place static assets (JS, CSS, Images) in `src/main/resources/static/`.
  - Use Vanilla JS, HTML, and CSS as the baseline. 
  - Mix in HTMX and Alpine.js alongside Tailwind CSS where it naturally fits with Thymeleaf for dynamic behavior, but do not force their use if a simple vanilla approach is sufficient.
  - Visualization/graphing libraries (e.g., Chart.js, D3.js) are permitted and encouraged when managing complex UI or data presentation.

## Code Search Strategy

Use the right tool for each situation:

- **`semble search`** — Use for semantic/exploratory searches: "이 기능 어떻게 구현됐어?", "인증 흐름 어디서 처리해?", 특정 동작을 하는 코드 위치 파악, 관련 코드 전수조사. 자연어 쿼리 또는 심볼명 모두 가능.
  ```
  semble search "authentication flow" ./src
  semble search "ScanService" ./src
  semble find-related src/main/java/.../ScanService.java 42 ./src
  ```
- **`@workspace` / `semantic_search`** — 파일 구조 파악, 특정 파일 내용 확인, 빠른 파일명 검색 등 구조적 탐색에 사용.
- **`grep_search`** — 정확한 문자열, 클래스명, 어노테이션 등 리터럴 매칭이 필요할 때 사용.

**기본 원칙:** 코드 수정이나 새 기능 구현 전에 관련 코드가 이미 존재하는지 먼저 `semble search`로 확인한다. 파일 전체를 읽기 전에 semble로 관련 청크만 먼저 가져온다.

## Build and Test
- **Gradle:** Use `gradlew` for execution (e.g., `./gradlew build`, `./gradlew bootRun`, `./gradlew test`).

## Conventions
- **Database:** Use standard PostgreSQL dialects and conventions. Configure connections securely via `application.yaml`.
- **Dependencies:** If adding a frontend library, default to using CDN links or webjars in the templates unless requested to bundle them otherwise.
