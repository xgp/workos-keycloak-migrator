package io.phasetwo.migration.migrator;

import io.phasetwo.migration.common.state.Counters;
import io.phasetwo.migration.common.state.MigrationState;
import io.phasetwo.migration.common.sync.*;
import io.phasetwo.migration.common.workos.WorkOSClient;
import io.phasetwo.migration.common.workos.model.Cursor;
import io.phasetwo.migration.common.workos.model.Page;
import io.phasetwo.migration.common.workos.model.WOrgMembership;
import io.phasetwo.migration.common.workos.model.WRole;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import lombok.extern.jbosslog.JBossLog;

/** Step orchestration for the bulk runner. Each step is idempotent and cursor-driven. */
@JBossLog
public class Steps {
  private final SyncContext ctx;
  private final MigrationState state;
  private final Counters counters;
  private final int pageSize;
  private final int limit;

  public Steps(SyncContext ctx, MigrationState state, Counters counters, int pageSize, int limit) {
    this.ctx = ctx;
    this.state = state;
    this.counters = counters;
    this.pageSize = pageSize;
    this.limit = limit;
  }

  public void runRealmRoles() {
    log.info("== Step: environment roles ==");
    RoleSync sync = new RoleSync(ctx);
    WorkOSClient w = ctx.workos();
    int seen = 0;
    for (WRole r : w.listEnvironmentRoles()) {
      if (limit > 0 && seen >= limit) break;
      try {
        SyncResult result = sync.sync(r);
        counters.record(result);
        log.infof("%s", result);
      } catch (Exception e) {
        log.errorf("role sync failed for %s: %s", r.id(), e.toString());
        counters.record(SyncResult.failed("role", r.id(), e.toString()));
      }
      seen++;
    }
  }

  public void runOrganizations() {
    log.info("== Step: organizations ==");
    OrganizationSync sync = new OrganizationSync(ctx);
    pageThrough(
        "organizations",
        (cursor, lim) -> ctx.workos().listOrganizations(cursor, lim),
        o -> {
          try {
            SyncResult r = sync.sync(o);
            counters.record(r);
            log.infof("%s", r);
          } catch (Exception e) {
            counters.record(SyncResult.failed("organization", o.id(), e.toString()));
            log.errorf("org sync failed: %s", e.toString());
          }
        });
  }

  public void runOrganizationRoles() {
    log.info("== Step: organization roles ==");
    RoleSync sync = new RoleSync(ctx);
    // List orgs (cheap) and per-org pull custom roles
    pageThrough(
        "organization_roles",
        (cursor, lim) -> ctx.workos().listOrganizations(cursor, lim),
        o -> {
          try {
            List<WRole> roles = ctx.workos().listOrganizationRoles(o.id());
            for (WRole r : roles) {
              SyncResult result = sync.syncOrganizationRole(r, o.id());
              counters.record(result);
              log.infof("%s", result);
            }
          } catch (Exception e) {
            log.warnf("listing org roles for %s failed: %s", o.id(), e.toString());
          }
        });
  }

  public void runIdps() {
    log.info("== Step: identity providers ==");
    IdentityProviderSync sync = new IdentityProviderSync(ctx);
    pageThrough(
        "idps",
        (cursor, lim) -> ctx.workos().listConnections(cursor, lim),
        c -> {
          try {
            SyncResult r = sync.sync(c);
            counters.record(r);
            log.infof("%s", r);
          } catch (Exception e) {
            counters.record(SyncResult.failed("identity_provider", c.id(), e.toString()));
            log.errorf("idp sync failed: %s", e.toString());
          }
        });
  }

  public void runDirectories(String serverUrl) {
    log.info("== Step: directory connections (SCIM stubs) ==");
    DirectorySync sync = new DirectorySync(ctx, serverUrl);
    pageThrough(
        "directories",
        (cursor, lim) -> ctx.workos().listDirectories(cursor, lim),
        d -> {
          try {
            SyncResult r = sync.sync(d);
            counters.record(r);
            log.infof("%s", r);
          } catch (Exception e) {
            counters.record(SyncResult.failed("directory", d.id(), e.toString()));
            log.errorf("directory sync failed: %s", e.toString());
          }
        });
  }

  public void runDirectoryUsers() {
    log.info("== Step: directory users (SCIM-managed tagging) ==");
    // Ensure the realm role exists before tagging.
    io.phasetwo.migration.common.keycloak.Roles.ensureScimManagedRole(ctx.realm());
    DirectoryUserSync sync = new DirectoryUserSync(ctx);
    // For each directory, page through directory_users and tag matching KC users.
    pageThrough(
        "directory_users",
        (cursor, lim) -> ctx.workos().listDirectories(cursor, lim),
        dir -> {
          Cursor inner = Cursor.empty();
          while (true) {
            Page<io.phasetwo.migration.common.workos.model.WDirectoryUser> page;
            try {
              page = ctx.workos().listDirectoryUsers(dir.id(), inner, pageSize);
            } catch (Exception e) {
              log.warnf("listing directory_users for %s failed: %s", dir.id(), e.toString());
              return;
            }
            for (var du : page.data()) {
              try {
                SyncResult r = sync.sync(du);
                counters.record(r);
                log.infof("%s", r);
              } catch (Exception e) {
                counters.record(SyncResult.failed("directory_user", du.id(), e.toString()));
                log.errorf("directory_user sync failed: %s", e.toString());
              }
            }
            String next = page.listMetadata() == null ? null : page.listMetadata().after();
            if (next == null || next.isEmpty()) break;
            inner = new Cursor(null, next);
          }
        });
  }

  public void runUsers() {
    log.info("== Step: users ==");
    UserSync sync = new UserSync(ctx);
    pageThrough(
        "users",
        (cursor, lim) -> ctx.workos().listUsers(cursor, lim),
        u -> {
          try {
            SyncResult r = sync.sync(u);
            counters.record(r);
            log.infof("%s", r);
          } catch (Exception e) {
            counters.record(SyncResult.failed("user", u.id(), e.toString()));
            log.errorf("user sync failed: %s", e.toString());
          }
        });
  }

  public void runMemberships() {
    log.info("== Step: organization memberships ==");
    OrganizationMembershipSync sync = new OrganizationMembershipSync(ctx);
    // The WorkOS endpoint requires organization_id or user_id, so iterate per org.
    pageThrough(
        "memberships",
        (cursor, lim) -> ctx.workos().listOrganizations(cursor, lim),
        org -> {
          Cursor membershipCursor = Cursor.empty();
          while (true) {
            Page<WOrgMembership> page;
            try {
              page = ctx.workos().listOrganizationMemberships(org.id(), membershipCursor, pageSize);
            } catch (Exception e) {
              log.warnf("listing memberships for %s failed: %s", org.id(), e.toString());
              return;
            }
            for (WOrgMembership m : page.data()) {
              try {
                SyncResult r = sync.sync(m);
                counters.record(r);
                log.infof("%s", r);
              } catch (Exception e) {
                counters.record(SyncResult.failed("organization_membership", m.id(), e.toString()));
                log.errorf("membership sync failed: %s", e.toString());
              }
            }
            String next = page.listMetadata() == null ? null : page.listMetadata().after();
            if (next == null || next.isEmpty()) break;
            membershipCursor = new Cursor(null, next);
          }
        });
  }

  public Map<String, Map<SyncAction, Integer>> snapshot() {
    return counters.snapshot();
  }

  public void persistCounters() {
    snapshot()
        .forEach(
            (entity, byAction) -> {
              Map<SyncAction, Integer> m = new LinkedHashMap<>(byAction);
              state.writeCounters(entity, m);
            });
  }

  private <T> void pageThrough(
      String entityType,
      BiFunction<Cursor, Integer, Page<T>> fetcher,
      java.util.function.Consumer<T> action) {
    String savedAfter = state.getCursor(entityType);
    Cursor cursor = savedAfter == null ? Cursor.empty() : new Cursor(null, savedAfter);
    int seen = 0;
    while (true) {
      Page<T> page = fetcher.apply(cursor, pageSize);
      for (T item : page.data()) {
        if (limit > 0 && seen >= limit) break;
        action.accept(item);
        seen++;
      }
      String next = page.listMetadata() == null ? null : page.listMetadata().after();
      if (next == null || next.isEmpty()) {
        // completed: clear cursor
        state.setCursor(entityType, null);
        break;
      }
      state.setCursor(entityType, next);
      cursor = new Cursor(null, next);
      if (limit > 0 && seen >= limit) break;
    }
  }
}
