package io.phasetwo.migration.common.workos.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Subset of the WorkOS {@code DirectoryUser} entity, returned by {@code /directory_users}. Used to
 * link Keycloak users back to the SCIM directory connection that created them.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WDirectoryUser(
        @JsonProperty("id") String id,
        @JsonProperty("directory_id") String directoryId,
        @JsonProperty("organization_id") String organizationId,
        @JsonProperty("idp_id") String idpId,
        @JsonProperty("email") String email,
        @JsonProperty("first_name") String firstName,
        @JsonProperty("last_name") String lastName,
        @JsonProperty("state") String state,
        @JsonProperty("created_at") String createdAt,
        @JsonProperty("updated_at") String updatedAt) {}
