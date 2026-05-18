package io.phasetwo.migration.common.workos.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record WDirectory(
    @JsonProperty("id") String id,
    @JsonProperty("organization_id") String organizationId,
    @JsonProperty("external_key") String externalKey,
    @JsonProperty("type") String type,
    @JsonProperty("state") String state,
    @JsonProperty("name") String name,
    @JsonProperty("domain") String domain,
    @JsonProperty("created_at") String createdAt,
    @JsonProperty("updated_at") String updatedAt) {

  public boolean isLinked() {
    return "linked".equals(state);
  }
}
