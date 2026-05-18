package io.phasetwo.migration.webhook;

import org.keycloak.models.KeycloakSession;
import org.keycloak.services.resource.RealmResourceProvider;

public class WorkOSWebhookProvider implements RealmResourceProvider {

  private final KeycloakSession session;
  private final long eventToleranceMillis;

  public WorkOSWebhookProvider(KeycloakSession session, long eventToleranceMillis) {
    this.session = session;
    this.eventToleranceMillis = eventToleranceMillis;
  }

  @Override
  public Object getResource() {
    return new WorkOSWebhookResource(session, eventToleranceMillis);
  }

  @Override
  public void close() {}
}
