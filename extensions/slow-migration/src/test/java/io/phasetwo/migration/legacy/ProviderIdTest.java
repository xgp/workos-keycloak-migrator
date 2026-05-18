package io.phasetwo.migration.legacy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ProviderIdTest {

  @Test
  void factoryIdIsRoutingPath() {
    assertThat(new WorkOSLegacyProviderFactory().getId()).isEqualTo("workos-legacy");
  }

  @Test
  void legacyProviderIdMatchesUpstream() {
    // Per the clarification round, the keycloak-user-migration extension registers its
    // user-storage factory under this id.
    assertThat(WorkOSLegacyProviderFactory.LEGACY_PROVIDER_ID)
        .isEqualTo("User migration using a REST client");
  }
}
