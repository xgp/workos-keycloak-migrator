package io.phasetwo.migration.common;

/** Single source of truth for the Keycloak attribute keys this project reads & writes. */
public final class AttributeKeys {

  public static final String PREFIX = "workos.";
  public static final String MIGRATION_PREFIX = "workos.migration.";

  public static final String WORKOS_ID = PREFIX + "id";
  public static final String WORKOS_EXTERNAL_ID = PREFIX + "external_id";
  public static final String WORKOS_PROFILE_PICTURE_URL = PREFIX + "profile_picture_url";
  public static final String WORKOS_LOCALE = PREFIX + "locale";
  public static final String WORKOS_LAST_SIGN_IN_AT = PREFIX + "last_sign_in_at";
  public static final String WORKOS_MIGRATED_AT = PREFIX + "migrated_at";
  public static final String WORKOS_LAST_SYNC_AT = PREFIX + "last_sync_at";
  public static final String WORKOS_SOURCE = PREFIX + "source";
  public static final String WORKOS_SYNC_HASH = PREFIX + "sync_hash";

  public static final String WORKOS_METADATA_PREFIX = PREFIX + "metadata.";

  public static final String WORKOS_ROLE_SLUG = PREFIX + "slug";
  public static final String WORKOS_ROLE_PERMISSIONS = PREFIX + "permissions";

  public static final String WORKOS_STRIPE_CUSTOMER_ID = PREFIX + "stripe_customer_id";

  public static final String WORKOS_IDP_CONNECTION_ID = "workos.connection_id";
  public static final String WORKOS_IDP_CONNECTION_TYPE = "workos.connection_type";
  public static final String WORKOS_IDP_INCOMPLETE = "workos.incomplete";

  public static final String WORKOS_DIRECTORY_ID = PREFIX + "directory.id";
  public static final String WORKOS_DIRECTORY_TYPE = PREFIX + "directory.type";
  public static final String WORKOS_DIRECTORY_STATE = PREFIX + "directory.state";
  public static final String WORKOS_DIRECTORY_INCOMPLETE = PREFIX + "directory.incomplete";

  public static final String WORKOS_ORG_MEMBERSHIP_ID = PREFIX + "org_membership_id";

  // SCIM / directory-user tagging — applied to KC users provisioned via WorkOS Directory Sync
  public static final String SCIM_MANAGED_ROLE = "scim-managed";
  public static final String SCIM_DIRECTORY_USER_ID = "scim.directory_user_id";
  public static final String SCIM_DIRECTORY_ID_ATTR = "scim.directory_id";
  public static final String SCIM_IDP_ID = "scim.idp_id";
  public static final String SCIM_STATE = "scim.state";

  // Realm-scoped migration state attributes
  public static final String REALM_CLIENT_FINGERPRINT = MIGRATION_PREFIX + "client_fingerprint";
  public static final String REALM_LAST_RUN_AT = MIGRATION_PREFIX + "last_run_at";
  public static final String REALM_LAST_RUN_STATUS = MIGRATION_PREFIX + "last_run_status";
  public static final String REALM_LAST_RUN_SUMMARY = MIGRATION_PREFIX + "last_run_summary";
  public static final String REALM_CURSOR_PREFIX = MIGRATION_PREFIX + "cursor.";
  public static final String REALM_COUNTS_PREFIX = MIGRATION_PREFIX + "counts.";

  public static final String REALM_WEBHOOK_PUBLIC_ID = MIGRATION_PREFIX + "webhook.public_id";
  public static final String REALM_WEBHOOK_ENDPOINT_ID = MIGRATION_PREFIX + "webhook.endpoint_id";
  public static final String REALM_WEBHOOK_SECRET = MIGRATION_PREFIX + "webhook.secret";

  public static final String REALM_SLOW_TOKEN = MIGRATION_PREFIX + "slow.token";
  public static final String REALM_SLOW_CLIENT_ID = MIGRATION_PREFIX + "slow.client_id";
  public static final String REALM_SLOW_CLIENT_SECRET = MIGRATION_PREFIX + "slow.client_secret";

  private AttributeKeys() {}
}
