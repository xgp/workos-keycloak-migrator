package io.phasetwo.migration.common.workos.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record WConnection(
    @JsonProperty("id") String id,
    @JsonProperty("organization_id") String organizationId,
    @JsonProperty("connection_type") String connectionType,
    @JsonProperty("name") String name,
    @JsonProperty("state") String state,
    @JsonProperty("domains") List<WConnectionDomain> domains,
    @JsonProperty("options") Options options,
    @JsonProperty("created_at") String createdAt,
    @JsonProperty("updated_at") String updatedAt) {

  public boolean isActive() {
    return "active".equals(state);
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Options(@JsonProperty("signing_cert") String signingCert) {}
}
