# WorkOS → Keycloak Migrator

Three-component project that migrates an identity system from WorkOS into Keycloak (with Phase Two's
organizations & webhooks extensions installed):

| Module | Artifact | Role |
| --- | --- | --- |
| `common/` | `common-0.1.0-SNAPSHOT.jar` | WorkOS client, sync logic, webhook verification, state tracking |
| `migrator/` | `workos-keycloak-migrator.jar` (fat) | Standalone bulk runner |
| `extensions/webhook-listener/` | `workos-webhook-listener.jar` | Keycloak `RealmResourceProvider` at `/realms/{realm}/workos-webhook/{publicId}` |
| `extensions/slow-migration/` | `workos-slow-migration.jar` | `RealmResourceProvider` at `/realms/{realm}/workos-legacy/{username}` for `keycloak-user-migration` |

See `IMPLEMENTATION.md` for the full design contract and `SPEC.md` for the original brief.

## Build

```
mvn -DskipTests package
```

Outputs:
- `migrator/target/workos-keycloak-migrator.jar`
- `extensions/webhook-listener/target/workos-webhook-listener.jar`
- `extensions/slow-migration/target/workos-slow-migration.jar`

## Run the bulk migrator

```
java -jar migrator/target/workos-keycloak-migrator.jar \
  --workos-api-key=$WORKOS_API_KEY \
  --keycloak-url=http://localhost:8080 \
  --keycloak-realm=migrate-target \
  --keycloak-client-id=migrator-cli \
  --keycloak-client-secret=$KC_CLIENT_SECRET \
  --source-label=sandbox
```

Re-running the same command is safe; entities migrated previously will be detected by their
`workos.id` attribute and either updated or left untouched if their content hash hasn't changed.

Useful flags: `--entities=users,organizations`, `--dry-run`, `--restart`, `--limit=10`,
`--slow-migration-active` (skip `UPDATE_PASSWORD` since the slow-migration extension will handle the
password trade-off on first login).

## Local validation harness

The repo ships a docker-compose stack that brings up Postgres + a phasetwo-keycloak image with our
two extensions mounted as providers.

1. Build the project: `mvn -DskipTests package`
2. Drop the `keycloak-user-migration` jar into `extensions/lib/` (download from
   https://github.com/daniel-frak/keycloak-user-migration/releases).
3. `WORKOS_API_KEY=sk_test_... WORKOS_CLIENT_ID=client_... WORKOS_CLIENT_SECRET=... docker compose up -d`
4. `./scripts/bootstrap-realm.sh` — prints the migrator credentials.
5. Run the bulk migrator (see above).
6. Trigger a WorkOS event (e.g. update a user) — observe the webhook hit and the realm state.
7. Reset a user’s password from the Keycloak login screen — observe the slow-migration extension
   verify it against WorkOS via `keycloak-user-migration`.

## Attribute & realm-state conventions

All written keys are prefixed `workos.`. The canonical list is in
`io.phasetwo.wkm.common.AttributeKeys`. Notable entries:

- `workos.id` (user / org / role): WorkOS source-of-truth id; primary idempotency key.
- `workos.source`: short label for the WorkOS environment (`sandbox`, `demo`, or a fingerprint).
- `workos.migration.client_fingerprint` (realm): SHA-256 prefix of the WorkOS API key — prevents
  accidentally swapping a Sandbox key for a Demo key on the same realm.
- `workos.migration.cursor.<entity>` (realm): resume cursor written page-by-page.
- `workos.migration.counts.<entity>.<action>` (realm): per-action counts persisted at run end.
- `workos.migration.webhook.*` (realm): the listener stores its public id, the WorkOS endpoint id,
  and the HMAC secret here.
- `workos.migration.slow.*` (realm): credentials for the `keycloak-user-migration` legacy service.

## Notes / known partials

- WorkOS Connections (`/connections`) do not expose IdP secrets or SSO URLs. The migrator therefore
  creates **stub** identity providers tagged with `workos.incomplete=true` so administrators can
  finish wiring. Where SAML signing certs are available we set them.
- WorkOS Directories likewise become SCIM **stubs** in the corresponding Phase Two org with a
  placeholder shared secret; the org is tagged `workos.directory.incomplete=true`.
- Phase Two organization roles have no permission model. WorkOS permission slugs are encoded into
  the role description as `[permissions:p1,p2]` plus a `[wos:role_…]` suffix that carries the
  original id.
- Phase Two organization-domain `verified` is set via the verify() API; if PT rejects (e.g. no DNS
  record present in a lab env) the run reports `PARTIAL` and leaves the domain unverified.
- Directory groups (`dsync.group.*`) materialise as Keycloak groups under a per-org parent named
  `org-{pt_org_id}` (the Phase Two organization id, not the WorkOS id).
- **Slow-migration & bulk migration interaction**: when the slow-migration federation provider is
  installed, Keycloak's user-creation API consults it to prevent duplicates. A user that exists in
  WorkOS but not yet locally is "shadow known" via federation; the bulk migrator's `POST /users`
  will then return 409 and the run reports the entity as `SKIPPED reason=conflict_during_create`.
  This is intentional — those users will be materialised on first login by the legacy provider
  contract. Run the bulk migrator with the federation component disabled if you want fully
  eager local user creation.

## Testing

- `mvn test` — unit tests for the webhook verifier, rate limiter, JSON mappings, and the IdP
  provider-id mapper. No Docker required.
- A fuller integration test would require Testcontainers + a published phasetwo-keycloak image; the
  docker-compose workflow above is a more reliable manual check.
