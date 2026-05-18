package io.phasetwo.wkm.common.workos.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record WOrgDomain(
        @JsonProperty("id") String id,
        @JsonProperty("organization_id") String organizationId,
        @JsonProperty("domain") String domain,
        @JsonProperty("state") String state) {

    public boolean isVerified() {
        return "verified".equals(state) || "legacy_verified".equals(state);
    }
}
