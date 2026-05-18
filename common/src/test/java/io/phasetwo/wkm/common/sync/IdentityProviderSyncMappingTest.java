package io.phasetwo.wkm.common.sync;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class IdentityProviderSyncMappingTest {

    @Test
    void samlVariantsMapToSamlProvider() {
        assertThat(IdentityProviderSync.mapProviderId("OktaSAML")).isEqualTo("saml");
        assertThat(IdentityProviderSync.mapProviderId("AzureSAML")).isEqualTo("saml");
        assertThat(IdentityProviderSync.mapProviderId("GenericSAML")).isEqualTo("saml");
        assertThat(IdentityProviderSync.mapProviderId("KeycloakSAML")).isEqualTo("saml");
    }

    @Test
    void oidcVariantsMapToOidcProvider() {
        assertThat(IdentityProviderSync.mapProviderId("GenericOIDC")).isEqualTo("oidc");
        assertThat(IdentityProviderSync.mapProviderId("EntraIdOIDC")).isEqualTo("oidc");
        assertThat(IdentityProviderSync.mapProviderId("AdpOidc")).isEqualTo("oidc");
    }

    @Test
    void wellKnownSocialMapToBuiltInProviders() {
        assertThat(IdentityProviderSync.mapProviderId("GoogleOAuth")).isEqualTo("google");
        assertThat(IdentityProviderSync.mapProviderId("MicrosoftOAuth")).isEqualTo("microsoft");
        assertThat(IdentityProviderSync.mapProviderId("GitHubOAuth")).isEqualTo("github");
        assertThat(IdentityProviderSync.mapProviderId("AppleOAuth")).isEqualTo("apple");
    }

    @Test
    void unsupportedSocialFallsBackToOidcStub() {
        assertThat(IdentityProviderSync.mapProviderId("SlackOAuth")).isEqualTo("oidc");
        assertThat(IdentityProviderSync.mapProviderId("DiscordOAuth")).isEqualTo("oidc");
    }

    @Test
    void specialAndPendingTypesAreSkipped() {
        assertThat(IdentityProviderSync.mapProviderId("Auth0Migration")).isNull();
        assertThat(IdentityProviderSync.mapProviderId("MagicLink")).isNull();
        assertThat(IdentityProviderSync.mapProviderId("Pending")).isNull();
        assertThat(IdentityProviderSync.mapProviderId("TestIdp")).isNull();
        assertThat(IdentityProviderSync.mapProviderId(null)).isNull();
        assertThat(IdentityProviderSync.mapProviderId("ZZZUnknown")).isNull();
    }
}
