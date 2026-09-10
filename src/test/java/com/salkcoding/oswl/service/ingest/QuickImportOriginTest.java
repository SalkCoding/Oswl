package com.salkcoding.oswl.service.ingest;

import com.salkcoding.oswl.auth.entity.UserVcsConnection;
import com.salkcoding.oswl.auth.enums.VcsProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;

class QuickImportOriginTest {
    private final QuickImportService service = mock(QuickImportService.class, CALLS_REAL_METHODS);

    @ParameterizedTest
    @ValueSource(strings = {"https://gitlab.evil.example/a/b", "https://evil.github.com/a/b",
            "http://github.com/a/b", "https://github.com:8443/a/b", "https://user@github.com/a/b",
            "https://github.com/a/b?token=value", "https://github.com/a/b#fragment",
            "https://github.com/a/../b", "https://github.com/a%2fb/c"})
    void rejectsUnapprovedOrAmbiguousDestination(String url) {
        assertThat(parse(url, List.of())).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://github.com/a/b", "https://GITHUB.com:443/a/b",
            "https://gitlab.com/a/b.git", "https://bitbucket.org/a/b"})
    void acceptsCanonicalPublicOrigins(String url) {
        assertThat(parse(url, List.of())).isNotNull();
    }

    @Test
    void bindsOnlyTheExactActiveConnectionIncludingPort() {
        var connection = connection();
        Object target = parse("https://git.internal:8443/a/b", List.of(connection));
        assertThat(target).isNotNull();
        assertThat((Long) ReflectionTestUtils.getField(target, "connectionId")).isEqualTo(17L);
        assertThat((String) ReflectionTestUtils.invokeMethod(service, "buildCloneUrl", target))
                .isEqualTo("https://git.internal:8443/a/b.git");
        assertThat(parse("https://git.internal/a/b", List.of(connection))).isNull();
        assertThat(parse("https://git.internal:8444/a/b", List.of(connection))).isNull();
        connection.setActive(false);
        assertThat(parse("https://git.internal:8443/a/b", List.of(connection))).isNull();
    }

    @Test
    void refusesToChooseBetweenTwoConnectionsAtTheSameOrigin() {
        var other = connection();
        other.setId(18L);
        assertThat(parse("https://git.internal:8443/a/b", List.of(connection(), other))).isNull();
    }

    @Test
    void cloudImportDoesNotUseAnEnterpriseCredentialOfTheSameProvider() {
        Object target = parse("https://gitlab.com/a/b", List.of(connection()));
        assertThat(target).isNotNull();
        assertThat((Object) ReflectionTestUtils.getField(target, "connectionId")).isNull();
    }

    private Object parse(String url, List<UserVcsConnection> connections) {
        return ReflectionTestUtils.invokeMethod(service, "parseRepoUrl", url, connections);
    }

    private UserVcsConnection connection() {
        return UserVcsConnection.builder().id(17L).provider(VcsProvider.GITLAB)
                .serverUrl("https://git.internal:8443").active(true).build();
    }
}
