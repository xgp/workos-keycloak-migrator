package io.phasetwo.migration.common.keycloak;

import io.phasetwo.client.OrganizationResource;
import io.phasetwo.client.OrganizationsResource;
import io.phasetwo.client.PhaseTwo;
import io.phasetwo.client.openapi.model.OrganizationRepresentation;
import io.phasetwo.migration.common.AttributeKeys;
import java.util.List;
import java.util.Optional;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.IdentityProviderRepresentation;
import org.keycloak.representations.idm.UserRepresentation;

/** Common idempotency lookups. */
public final class Lookups {

  private Lookups() {}

  public static Optional<UserRepresentation> userByWorkosId(RealmResource realm, String workosId) {
    List<UserRepresentation> hits =
        realm.users().searchByAttributes(AttributeKeys.WORKOS_ID + ":" + workosId);
    if (hits != null && !hits.isEmpty()) return Optional.of(fullUser(realm, hits.get(0).getId()));
    // Some Keycloak versions accept a leading q= prefix
    hits = realm.users().searchByAttributes("q=" + AttributeKeys.WORKOS_ID + ":" + workosId);
    if (hits != null && !hits.isEmpty()) return Optional.of(fullUser(realm, hits.get(0).getId()));
    return Optional.empty();
  }

  public static Optional<UserRepresentation> userByEmail(RealmResource realm, String email) {
    List<UserRepresentation> hits = realm.users().searchByEmail(email, true);
    if (hits != null && !hits.isEmpty()) return Optional.of(fullUser(realm, hits.get(0).getId()));
    return Optional.empty();
  }

  private static UserRepresentation fullUser(RealmResource realm, String id) {
    return realm.users().get(id).toRepresentation();
  }

  public static Optional<OrganizationRepresentation> orgByWorkosId(
      PhaseTwo pt, String realm, String workosId) {
    OrganizationsResource orgs = pt.organizations(realm);
    // PT search supports q=k:v attribute search
    List<OrganizationRepresentation> hits =
        orgs.get(
            Optional.empty(),
            Optional.of(AttributeKeys.WORKOS_ID + ":" + workosId),
            Optional.of(0),
            Optional.of(50));
    if (hits != null) {
      for (OrganizationRepresentation o : hits) {
        if (o.getAttributes() != null) {
          List<String> v = o.getAttributes().get(AttributeKeys.WORKOS_ID);
          if (v != null && v.contains(workosId)) return Optional.of(o);
        }
      }
    }
    return Optional.empty();
  }

  public static Optional<OrganizationRepresentation> orgByName(
      PhaseTwo pt, String realm, String name) {
    List<OrganizationRepresentation> hits =
        pt.organizations(realm).get(Optional.of(name), Optional.of(0), Optional.of(50));
    if (hits != null) {
      for (OrganizationRepresentation o : hits) {
        if (name.equals(o.getName())) return Optional.of(o);
      }
    }
    return Optional.empty();
  }

  public static OrganizationResource orgResource(PhaseTwo pt, String realm, String id) {
    return pt.organizations(realm).organization(id);
  }

  public static Optional<IdentityProviderRepresentation> idpByAlias(
      RealmResource realm, String alias) {
    try {
      IdentityProviderRepresentation rep = realm.identityProviders().get(alias).toRepresentation();
      return Optional.ofNullable(rep);
    } catch (jakarta.ws.rs.NotFoundException e) {
      return Optional.empty();
    }
  }
}
