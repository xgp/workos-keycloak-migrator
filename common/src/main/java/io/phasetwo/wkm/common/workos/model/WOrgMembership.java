package io.phasetwo.wkm.common.workos.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record WOrgMembership(
        @JsonProperty("id") String id,
        @JsonProperty("user_id") String userId,
        @JsonProperty("organization_id") String organizationId,
        @JsonProperty("status") String status,
        @JsonProperty("role") Role role,
        @JsonProperty("role_assignments") List<RoleAssignment> roleAssignments,
        @JsonProperty("created_at") String createdAt,
        @JsonProperty("updated_at") String updatedAt) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Role(@JsonProperty("slug") String slug) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RoleAssignment(
            @JsonProperty("id") String id, @JsonProperty("role") Role role) {}
}
