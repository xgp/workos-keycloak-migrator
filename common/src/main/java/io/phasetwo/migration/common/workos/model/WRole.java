package io.phasetwo.migration.common.workos.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record WRole(
        @JsonProperty("id") String id,
        @JsonProperty("slug") String slug,
        @JsonProperty("name") String name,
        @JsonProperty("description") String description,
        @JsonProperty("type") String type,
        @JsonProperty("resource_type_slug") String resourceTypeSlug,
        @JsonProperty("permissions") List<String> permissions,
        @JsonProperty("created_at") String createdAt,
        @JsonProperty("updated_at") String updatedAt) {

    public boolean isEnvironmentRole() {
        return "EnvironmentRole".equals(type);
    }

    public boolean isOrganizationRole() {
        return "OrganizationRole".equals(type);
    }
}
