package com.salkcoding.oswl.client;

import org.junit.jupiter.api.Tag;
import com.salkcoding.oswl.testing.TestTags;
import com.salkcoding.oswl.dto.QuickImportRepoDto;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag(TestTags.FAST)
@DisplayName("BitbucketCloudClient HTTP 통합 테스트 (MockWebServer)")
class BitbucketCloudClientHttpTest {

    private MockWebServer server;
    private BitbucketCloudClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        String apiBase = server.url("/2.0/").toString().replaceAll("/$", "");
        client = BitbucketCloudClient.forTesting(apiBase);
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    @DisplayName("Atlassian API Token: email|slug → Basic auth로 repo 목록 조회")
    void listRepositories_atlassianAccountToken_usesBasicAuth() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("""
                    {
                      "values": [{
                        "name": "test",
                        "full_name": "salkcoding/test",
                        "is_private": false,
                        "updated_on": "2026-06-01T00:00:00Z",
                        "mainbranch": {"name": "main"},
                        "links": {"html": {"href": "https://bitbucket.org/salkcoding/test"}}
                      }]
                    }
                    """));

        List<QuickImportRepoDto> repos = client.listRepositories(
                "user@example.com|salkcoding", "ATATT-test-token");

        assertThat(repos).hasSize(1);
        assertThat(repos.get(0).name()).isEqualTo("test");
        assertThat(repos.get(0).fullName()).isEqualTo("salkcoding/test");

        RecordedRequest request = server.takeRequest();
        assertThat(request.getPath()).contains("/repositories/salkcoding");
        String auth = request.getHeader("Authorization");
        assertThat(auth).startsWith("Basic ");
        String decoded = new String(Base64.getDecoder().decode(auth.substring(6)), StandardCharsets.UTF_8);
        assertThat(decoded).isEqualTo("user@example.com:ATATT-test-token");
    }

    @Test
    @DisplayName("Atlassian API Token: validateToken은 repo endpoint로 검증")
    void validateToken_atlassianAccountToken_checksRepositories() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"values\":[]}"));

        assertThatCode(() -> client.validateToken("user@example.com|salkcoding", "ATATT-test-token"))
                .doesNotThrowAnyException();

        RecordedRequest request = server.takeRequest();
        assertThat(request.getPath()).isEqualTo("/2.0/repositories/salkcoding?pagelen=1");
    }

    @Test
    @DisplayName("Atlassian API Token: 401이면 명확한 오류")
    void validateToken_atlassianAccountToken_401throws() {
        server.enqueue(new MockResponse().setResponseCode(401));

        assertThatThrownBy(() -> client.validateToken("user@example.com|salkcoding", "ATATT-bad"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Bitbucket authentication failed");
    }

    @Test
    @DisplayName("Workspace HTTP Token: slug → Bearer auth")
    void listRepositories_workspaceHttpToken_usesBearer() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("""
                    {
                      "values": [{
                        "name": "demo",
                        "full_name": "salkcoding/demo",
                        "is_private": true,
                        "updated_on": "2026-06-01T00:00:00Z",
                        "mainbranch": {"name": "main"},
                        "links": {"html": {"href": "https://bitbucket.org/salkcoding/demo"}}
                      }]
                    }
                    """));

        client.listRepositories("salkcoding", "ATATT-workspace-token");

        RecordedRequest request = server.takeRequest();
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer ATATT-workspace-token");
    }

    @Test
    @DisplayName("cloneCredentials: Atlassian API Token은 x-bitbucket-api-token-auth 사용")
    void cloneCredentials_atlassianApiToken_usesStaticGitUsername() {
        var creds = client.cloneCredentials(
                "user@example.com|salkcoding",
                "ATATT-demo");

        assertThat(creds.username()).isEqualTo("x-bitbucket-api-token-auth");
        assertThat(creds.password()).isEqualTo("ATATT-demo");
    }
}
