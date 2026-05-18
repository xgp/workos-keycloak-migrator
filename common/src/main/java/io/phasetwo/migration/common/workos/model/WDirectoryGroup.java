package io.phasetwo.migration.common.workos.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record WDirectoryGroup(
        @JsonProperty("id") String id,
        @JsonProperty("idp_id") String idpId,
        @JsonProperty("directory_id") String directoryId,
        @JsonProperty("organization_id") String organizationId,
        @JsonProperty("name") String name,
        @JsonProperty("created_at") String createdAt,
        @JsonProperty("updated_at") String updatedAt) {}
