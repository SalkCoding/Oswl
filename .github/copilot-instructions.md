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

## Build and Test
- **Gradle:** Use `gradlew` for execution (e.g., `./gradlew build`, `./gradlew bootRun`, `./gradlew test`).

## Conventions
- **Database:** Use standard PostgreSQL dialects and conventions. Configure connections securely via `application.yaml`.
- **Dependencies:** If adding a frontend library, default to using CDN links or webjars in the templates unless requested to bundle them otherwise.
