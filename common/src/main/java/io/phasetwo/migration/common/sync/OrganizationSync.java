package io.phasetwo.migration.common.sync;

import io.phasetwo.client.OrganizationResource;
import io.phasetwo.client.openapi.model.OrganizationDomainRepresentation;
import io.phasetwo.client.openapi.model.OrganizationRepresentation;
import io.phasetwo.migration.common.AttributeKeys;
import io.phasetwo.migration.common.keycloak.Lookups;
import io.phasetwo.migration.common.workos.model.WOrgDomain;
import io.phasetwo.migration.common.workos.model.WOrganization;
import java.time.Instant;
import java.util.*;
import lombok.extern.jbosslog.JBossLog;

@JBossLog
public class OrganizationSync implements EntitySync<WOrganization> {
  private static final String ENTITY = "organization";

  private final SyncContext ctx;

  public OrganizationSync(SyncContext ctx) {
    this.ctx = ctx;
  }

  @Override
  public SyncResult sync(WOrganization o) {
    if (o == null || o.id() == null) {
      return SyncResult.failed(ENTITY, o == null ? null : null, "missing id");
    }

    Optional<OrganizationRepresentation> existing =
        Lookups.orgByWorkosId(ctx.phaseTwo(), ctx.realmName(), o.id());
    if (existing.isEmpty()) {
      existing = Lookups.orgByName(ctx.phaseTwo(), ctx.realmName(), o.name());
      if (existing.isPresent()) {
        String tagged = singleAttr(existing.get(), AttributeKeys.WORKOS_ID);
        if (tagged != null && !tagged.equals(o.id())) {
          log.warnf(
              "conflict: PT org %s has workos.id=%s but WorkOS supplied %s",
              existing.get().getId(), tagged, o.id());
          return SyncResult.skipped(ENTITY, o.id(), "name_conflict_with_different_workos_id");
        }
      }
    }

    OrganizationRepresentation rep = existing.orElseGet(OrganizationRepresentation::new);
    rep.setName(o.name());
    rep.setDisplayName(o.name());
    List<String> domainStrings = new ArrayList<>();
    if (o.domains() != null) {
      for (WOrgDomain d : o.domains()) if (d.domain() != null) domainStrings.add(d.domain());
    }
    rep.setDomains(domainStrings);

    Map<String, List<String>> attrs =
        rep.getAttributes() == null
            ? new LinkedHashMap<>()
            : new LinkedHashMap<>(rep.getAttributes());
    putSingle(attrs, AttributeKeys.WORKOS_ID, o.id());
    putSingle(attrs, AttributeKeys.WORKOS_EXTERNAL_ID, o.externalId());
    putSingle(attrs, AttributeKeys.WORKOS_STRIPE_CUSTOMER_ID, o.stripeCustomerId());
    putSingle(attrs, AttributeKeys.WORKOS_SOURCE, ctx.source());
    if (o.metadata() != null) {
      o.metadata().forEach((k, v) -> putSingle(attrs, AttributeKeys.WORKOS_METADATA_PREFIX + k, v));
    }
    if (!attrs.containsKey(AttributeKeys.WORKOS_MIGRATED_AT)) {
      putSingle(attrs, AttributeKeys.WORKOS_MIGRATED_AT, Instant.now().toString());
    }
    putSingle(attrs, AttributeKeys.WORKOS_LAST_SYNC_AT, Instant.now().toString());
    rep.setAttributes(attrs);

    if (ctx.dryRun()) {
      return existing.isPresent()
          ? SyncResult.skipped(ENTITY, o.id(), "dry_run")
          : SyncResult.skipped(ENTITY, o.id(), "dry_run");
    }

    String ptId;
    SyncAction action;
    if (existing.isPresent()) {
      ptId = existing.get().getId();
      ctx.phaseTwo().organizations(ctx.realmName()).organization(ptId).update(rep);
      action = SyncAction.UPDATED;
    } else {
      ptId = ctx.phaseTwo().organizations(ctx.realmName()).create(rep);
      action = SyncAction.CREATED;
    }

    // Domain verification reconciliation
    boolean domainPartial = reconcileDomains(ptId, o);

    SyncResult result =
        new SyncResult(ENTITY, o.id(), domainPartial ? SyncAction.PARTIAL : action)
            .withKeycloakId(ptId);
    if (domainPartial) result.withReason("domain_verification_not_writable");
    return result;
  }

  private boolean reconcileDomains(String ptOrgId, WOrganization o) {
    if (o.domains() == null || o.domains().isEmpty()) return false;
    OrganizationResource org = ctx.phaseTwo().organizations(ctx.realmName()).organization(ptOrgId);
    List<OrganizationDomainRepresentation> ptDomains;
    try {
      ptDomains = org.domains().get();
    } catch (Exception e) {
      log.warnf("could not load PT domains for org %s: %s", ptOrgId, e.toString());
      return true;
    }

    Map<String, OrganizationDomainRepresentation> byName = new HashMap<>();
    for (OrganizationDomainRepresentation d : ptDomains) byName.put(d.getDomainName(), d);

    boolean partial = false;
    for (WOrgDomain wd : o.domains()) {
      OrganizationDomainRepresentation pd = byName.get(wd.domain());
      if (pd == null) continue;
      if (wd.isVerified() && !Boolean.TRUE.equals(pd.getVerified())) {
        pd.setVerified(true);
        try {
          // PT API does not expose a direct domain mutate beyond verify; re-issuing the
          // org update with the same domain list does not flip verified. We attempt the
          // verify() call which kicks off DNS — fine in prod, may fail in lab.
          org.domains().verify(wd.domain());
        } catch (Exception e) {
          log.debugf("verify() failed for %s/%s: %s", ptOrgId, wd.domain(), e.toString());
          partial = true;
        }
      }
    }
    return partial;
  }

  private static String singleAttr(OrganizationRepresentation o, String key) {
    if (o.getAttributes() == null) return null;
    List<String> v = o.getAttributes().get(key);
    return v == null || v.isEmpty() ? null : v.get(0);
  }

  private static void putSingle(Map<String, List<String>> attrs, String key, String value) {
    if (value == null) return;
    attrs.put(key, List.of(value));
  }
}
