package io.phasetwo.migration.common.workos;

import io.phasetwo.migration.common.workos.model.Cursor;
import io.phasetwo.migration.common.workos.model.Page;
import io.phasetwo.migration.common.workos.model.WConnection;
import io.phasetwo.migration.common.workos.model.WDirectory;
import io.phasetwo.migration.common.workos.model.WDirectoryGroup;
import io.phasetwo.migration.common.workos.model.WDirectoryUser;
import io.phasetwo.migration.common.workos.model.WIdentity;
import io.phasetwo.migration.common.workos.model.WOrgMembership;
import io.phasetwo.migration.common.workos.model.WOrganization;
import io.phasetwo.migration.common.workos.model.WRole;
import io.phasetwo.migration.common.workos.model.WUser;
import io.phasetwo.migration.common.workos.model.WWebhookEndpoint;
import java.util.List;
import java.util.Optional;

public interface WorkOSClient {

  // Users
  Page<WUser> listUsers(Cursor cursor, int limit);

  Optional<WUser> findUserByEmail(String email);

  Optional<WUser> getUser(String id);

  List<WIdentity> listUserIdentities(String userId);

  // Organizations
  Page<WOrganization> listOrganizations(Cursor cursor, int limit);

  Optional<WOrganization> getOrganization(String id);

  // Memberships
  Page<WOrgMembership> listOrganizationMemberships(String organizationId, Cursor cursor, int limit);

  Page<WOrgMembership> listOrganizationMembershipsForUser(String userId, Cursor cursor, int limit);

  Optional<WOrgMembership> getOrganizationMembership(String id);

  // Roles
  List<WRole> listEnvironmentRoles();

  List<WRole> listOrganizationRoles(String organizationId);

  // Connections (SSO)
  Page<WConnection> listConnections(Cursor cursor, int limit);

  Optional<WConnection> getConnection(String id);

  // Directories (SCIM)
  Page<WDirectory> listDirectories(Cursor cursor, int limit);

  Optional<WDirectory> getDirectory(String id);

  Page<WDirectoryGroup> listDirectoryGroups(String directoryId, Cursor cursor, int limit);

  Page<WDirectoryUser> listDirectoryUsers(String directoryId, Cursor cursor, int limit);

  Optional<WDirectoryUser> findDirectoryUserByEmail(String email);

  // Webhook endpoints
  List<WWebhookEndpoint> listWebhookEndpoints();

  WWebhookEndpoint createWebhookEndpoint(String endpointUrl, List<String> events);

  WWebhookEndpoint updateWebhookEndpoint(String id, List<String> events);

  // Authenticate (slow migration POST)
  boolean authenticatePassword(String email, String password, String clientId, String clientSecret);
}
