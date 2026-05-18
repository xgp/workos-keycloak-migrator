package io.phasetwo.migration.common.workos.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/** WorkOS webhook envelope. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WebhookEvent(
        @JsonProperty("id") String id,
        @JsonProperty("event") String event,
        @JsonProperty("data") JsonNode data,
        @JsonProperty("created_at") String createdAt) {}
