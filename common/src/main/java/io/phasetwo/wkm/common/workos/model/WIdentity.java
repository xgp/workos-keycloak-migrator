package io.phasetwo.wkm.common.workos.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record WIdentity(
        @JsonProperty("idp_id") String idpId,
        @JsonProperty("type") String type,
        @JsonProperty("provider") String provider) {}
