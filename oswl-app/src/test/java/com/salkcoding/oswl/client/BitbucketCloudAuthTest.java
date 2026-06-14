package com.salkcoding.oswl.client;

import org.junit.jupiter.api.Tag;
import com.salkcoding.oswl.testing.TestTags;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag(TestTags.FAST)
@DisplayName("BitbucketCloudAuth 단위 테스트")
class BitbucketCloudAuthTest {

    @Test
    @DisplayName("email|slug → Atlassian Account Basic auth")
    void parse_emailAndSlug_usesBasicAuth() {
        BitbucketCloudAuth auth = BitbucketCloudAuth.parse("user@example.com|salkcoding", "ATATTxxx");
        assertThat(auth.mode()).isEqualTo(BitbucketCloudAuth.Mode.ATLASSIAN_ACCOUNT);
        assertThat(auth.email()).isEqualTo("user@example.com");
        assertThat(auth.workspaceSlug()).isEqualTo("salkcoding");
        assertThat(auth.authHeader("ATATTxxx")).startsWith("Basic ");
        assertThat(auth.cloneUsername("ATATTxxx")).isEqualTo(BitbucketCloudAuth.GIT_API_TOKEN_USERNAME);
    }

    @Test
    @DisplayName("slug + ATATT → Workspace HTTP Bearer auth")
    void parse_slugOnlyAtatt_usesBearerAuth() {
        BitbucketCloudAuth auth = BitbucketCloudAuth.parse("salkcoding", "ATATTxxx");
        assertThat(auth.mode()).isEqualTo(BitbucketCloudAuth.Mode.WORKSPACE_HTTP);
        assertThat(auth.authHeader("ATATTxxx")).isEqualTo("Bearer ATATTxxx");
        assertThat(auth.cloneUsername("ATATTxxx")).isEqualTo("salkcoding");
    }

    @Test
    @DisplayName("email only → Basic auth without workspace slug")
    void parse_emailOnly_atlassianAccount() {
        BitbucketCloudAuth auth = BitbucketCloudAuth.parse("user@example.com", "ATATTxxx");
        assertThat(auth.mode()).isEqualTo(BitbucketCloudAuth.Mode.ATLASSIAN_ACCOUNT);
        assertThat(auth.hasWorkspaceSlug()).isFalse();
        assertThat(auth.authHeader("ATATTxxx")).startsWith("Basic ");
    }

    @Test
    @DisplayName("Atlassian account without email throws on authHeader")
    void authHeader_workspaceHttpWithoutEmail_ok() {
        BitbucketCloudAuth auth = BitbucketCloudAuth.parse("salkcoding", "ATATTxxx");
        assertThat(auth.authHeader("ATATTxxx")).isEqualTo("Bearer ATATTxxx");
    }

    @Test
    @DisplayName("account token without email throws helpful error")
    void authHeader_atlassianWithoutEmail_throws() {
        BitbucketCloudAuth authWithoutEmail =
                new BitbucketCloudAuth(null, null, BitbucketCloudAuth.Mode.ATLASSIAN_ACCOUNT);
        assertThatThrownBy(() -> authWithoutEmail.authHeader("ATATTxxx"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("email");
    }
}
