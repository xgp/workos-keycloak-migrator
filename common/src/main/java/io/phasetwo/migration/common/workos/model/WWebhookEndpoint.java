package io.phasetwo.migration.common.workos.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record WWebhookEndpoint(
    @JsonProperty("id") String id,
    @JsonProperty("endpoint_url") String endpointUrl,
    @JsonProperty("secret") String secret,
    @JsonProperty("status") String status,
    @JsonProperty("events") List<String> events,
    @JsonProperty("created_at") String createdAt,
    @JsonProperty("updated_at") String updatedAt) {}
