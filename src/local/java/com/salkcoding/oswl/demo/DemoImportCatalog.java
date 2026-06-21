package com.salkcoding.oswl.demo;

import java.util.List;

/** Public demo repositories exercised by GET /data/test (local profile). */
public final class DemoImportCatalog {

    private DemoImportCatalog() {}

    public record DemoRepo(String ecosystem, String lockFile, String repoUrl) {}

    public static final List<DemoRepo> REPOS = List.of(
            new DemoRepo("Maven", "pom.xml", "https://github.com/spring-projects/spring-petclinic"),
            new DemoRepo("Gradle", "gradlew + runtimeClasspath", "https://github.com/Netflix/dgs-framework"),
            new DemoRepo("npm", "package-lock.json (v2)", "https://github.com/expressjs/express"),
            new DemoRepo("npm", "package-lock.json (v3)", "https://github.com/koajs/koa"),
            new DemoRepo("Python", "poetry.lock", "https://github.com/Textualize/rich"),
            new DemoRepo("Python", "requirements.txt", "https://github.com/pallets/flask"),
            new DemoRepo("Python", "Pipfile.lock", "https://github.com/pypa/pipenv"),
            new DemoRepo("Cargo", "Cargo.lock", "https://github.com/BurntSushi/ripgrep"),
            new DemoRepo("Go", "go.sum", "https://github.com/gin-gonic/gin"),
            new DemoRepo("NuGet", ".csproj PackageReference", "https://github.com/jasontaylordev/CleanArchitecture"),
            new DemoRepo("Ruby", "Gemfile.lock", "https://github.com/sinatra/sinatra")
    );

    public static List<String> repoUrls() {
        return REPOS.stream().map(DemoRepo::repoUrl).toList();
    }
}
