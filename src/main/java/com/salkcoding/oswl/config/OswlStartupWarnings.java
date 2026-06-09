package com.salkcoding.oswl.config;

import com.salkcoding.oswl.auth.security.EncryptionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Prints all startup configuration warnings in one block (same style as the former
 * {@code ProductionProfileStartupWarning}) so logs stay readable.
 */
@Slf4j
@Component
public class OswlStartupWarnings implements ApplicationListener<ApplicationReadyEvent> {

    private static final String SEPARATOR = "=".repeat(72);

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        var context = event.getApplicationContext();
        Environment env = context.getEnvironment();
        List<String> sections = new ArrayList<>();

        if (!isProd(env)) {
            sections.add(nonProdSection(env));
            if (env.acceptsProfiles(Profiles.of("local", "test"))
                    && !context.containsBean("testDataController")) {
                sections.add(testDataClasspathSection());
            }
        }

        EncryptionService encryption = context.getBean(EncryptionService.class);

        if (isProd(env)) {
            List<String> missing = missingVars(env, "DB_URL", "DB_USERNAME", "DB_PASSWORD", "OSWL_ENCRYPTION_KEY");
            if (encryption.isEphemeralKey()) {
                missing.remove("OSWL_ENCRYPTION_KEY");
            }
            if (!missing.isEmpty()) {
                sections.add(missingEnvSection(
                        "Production (prod) — required environment variables",
                        missing,
                        List.of(
                                "Do not rely on default database credentials.",
                                "Datasource startup may fail until DB_URL / DB_USERNAME / DB_PASSWORD are set.")));
            }
        }

        if (encryption.isEphemeralKey()) {
            sections.add(ephemeralEncryptionSection(isProd(env)));
        }

        if (sections.isEmpty()) {
            return;
        }

        String body = String.join("\n\n", sections);

        log.warn("""
                
                {}
                 OSWL STARTUP WARNINGS — {} issue(s)
                {}
                {}
                
                 Copy .env.example → .env (local) or .env.prod.example → .env.prod (production).
                 See docs/Production-Deployment-Checklist.md
                {}
                """,
                SEPARATOR,
                sections.size(),
                SEPARATOR,
                body,
                SEPARATOR);
    }

    private static String testDataClasspathSection() {
        return """
                [TEST DATA ENDPOINT UNAVAILABLE — /data/test will 404]
                 TestDataController lives in src/local/java and is not on the runtime classpath.
                
                 Fix: stop the app, run "Java: Clean Java Language Server Workspace" (or reload Gradle),
                 then restart — or use ./gradlew bootRun (includes local sources automatically).""";
    }

    private static String nonProdSection(Environment env) {
        String[] profiles = env.getActiveProfiles();
        String profileLabel = profiles.length == 0
                ? "(none — check spring.profiles.active; default may be 'local')"
                : Arrays.stream(profiles).map(String::trim).collect(Collectors.joining(", "));

        return """
                [NOT PRODUCTION PROFILE]
                 Active profile(s): %s
                
                 If this instance is reachable from a network you do not fully trust, restart with:
                   SPRING_PROFILES_ACTIVE=prod
                
                 Without prod you may expose: H2 console, Swagger, /data test endpoints,
                 and other local-only settings.""".formatted(profileLabel);
    }

    private static String missingEnvSection(String title, List<String> missing, List<String> hints) {
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(title).append("]\n");
        sb.append(" Missing or empty:\n");
        for (String name : missing) {
            sb.append("   - ").append(name).append('\n');
        }
        for (String hint : hints) {
            sb.append(" ").append(hint).append('\n');
        }
        return sb.toString().stripTrailing();
    }

    private static String ephemeralEncryptionSection(boolean prod) {
        String context = prod ? "production (prod)" : "local / non-prod";
        return """
                [ENCRYPTION KEY NOT CONFIGURED — %s]
                 OSWL_ENCRYPTION_KEY is unset; using a random in-memory key for this JVM only.
                 VCS tokens and other encrypted secrets will NOT survive a restart.
                
                 Generate: openssl rand -base64 32
                 Set OSWL_ENCRYPTION_KEY in your environment or .env file.""".formatted(context);
    }

    private static List<String> missingVars(Environment env, String... names) {
        List<String> missing = new ArrayList<>();
        for (String name : names) {
            String value = env.getProperty(name);
            if (value == null || value.isBlank()) {
                missing.add(name);
            }
        }
        return missing;
    }

    private static boolean isProd(Environment env) {
        return env.acceptsProfiles(Profiles.of("prod"));
    }

}
