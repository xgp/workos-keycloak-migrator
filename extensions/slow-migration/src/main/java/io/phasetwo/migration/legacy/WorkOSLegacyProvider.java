package io.phasetwo.migration.legacy;

import org.keycloak.models.KeycloakSession;
import org.keycloak.services.resource.RealmResourceProvider;

public class WorkOSLegacyProvider implements RealmResourceProvider {

  private final KeycloakSession session;

  public WorkOSLegacyProvider(KeycloakSession session) {
    this.session = session;
  }

  @Override
  public Object getResource() {
    return new WorkOSLegacyResource(session);
  }

  @Override
  public void close() {}
}
