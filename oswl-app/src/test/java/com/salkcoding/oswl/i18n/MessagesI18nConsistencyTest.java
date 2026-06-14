package com.salkcoding.oswl.i18n;

import org.junit.jupiter.api.Tag;
import com.salkcoding.oswl.testing.TestTags;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Release guard: UI message bundles and AI prompt overlays stay in sync (EN ↔ KO).
 */
@Tag(TestTags.FAST)
@DisplayName("i18n 메시지 번들 일관성")
class MessagesI18nConsistencyTest {

    private static final Pattern MESSAGE_KEY = Pattern.compile("^\\s*([A-Za-z0-9_.-]+)\\s*=");
    private static final Pattern THYMELEAF_KEY = Pattern.compile("#\\{([A-Za-z0-9._|]+)\\}");
    private static final Pattern INLINE_SCRIPT_KEY = Pattern.compile("/\\*\\[\\[#\\{([A-Za-z0-9._]+)\\}\\]\\]\\*/");
    /** MessageFormat placeholders like #{0} in fallback strings are not message keys. */
    private static final Pattern VALID_MESSAGE_KEY = Pattern.compile("^[A-Za-z][A-Za-z0-9._]*$");

    @Test
    @DisplayName("messages.properties ↔ messages_ko.properties 키 집합 동일")
    void messages_enAndKo_haveSameKeys() throws IOException {
        Set<String> en = loadKeys("messages.properties");
        Set<String> ko = loadKeys("messages_ko.properties");

        assertThat(ko).containsExactlyInAnyOrderElementsOf(en);
    }

    @Test
    @DisplayName("ai/prompts.properties ↔ prompts_ko.properties 키 집합 동일")
    void aiPrompts_enAndKo_haveSameKeys() throws IOException {
        Set<String> en = loadKeys("ai/prompts.properties");
        Set<String> ko = loadKeys("ai/prompts_ko.properties");

        assertThat(ko).containsExactlyInAnyOrderElementsOf(en);
    }

    @Test
    @DisplayName("템플릿·Java에서 참조하는 #{...} 키가 messages.properties에 존재")
    void referencedThymeleafKeys_existInMessages() throws IOException {
        Set<String> en = loadKeys("messages.properties");
        Set<String> referenced = collectReferencedMessageKeys();

        assertThat(en)
                .as("Add missing keys to messages.properties and messages_ko.properties")
                .containsAll(referenced);
    }

    private static Set<String> loadKeys(String classpathResource) throws IOException {
        Properties props = new Properties();
        try (var reader = new InputStreamReader(
                new ClassPathResource(classpathResource).getInputStream(), StandardCharsets.UTF_8)) {
            props.load(reader);
        }
        return props.stringPropertyNames();
    }

    private static Set<String> collectReferencedMessageKeys() throws IOException {
        Path root = Path.of("src/main");
        Set<String> keys = new HashSet<>();
        Pattern[] patterns = {THYMELEAF_KEY, INLINE_SCRIPT_KEY};

        try (Stream<Path> paths = Files.walk(root)) {
            paths.filter(p -> {
                        String name = p.toString().replace('\\', '/');
                        return name.endsWith(".html") || name.endsWith(".java");
                    })
                    .forEach(p -> {
                        try {
                            String content = Files.readString(p);
                            for (Pattern pattern : patterns) {
                                Matcher m = pattern.matcher(content);
                                while (m.find()) {
                                    String key = m.group(1);
                                    if (key.contains("|")) {
                                        key = key.substring(0, key.indexOf('|'));
                                    }
                                    if (VALID_MESSAGE_KEY.matcher(key).matches()) {
                                        keys.add(key);
                                    }
                                }
                            }
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
        }
        return keys;
    }
}
