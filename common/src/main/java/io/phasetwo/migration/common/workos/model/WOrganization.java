package io.phasetwo.migration.common.workos.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record WOrganization(
        @JsonProperty("id") String id,
        @JsonProperty("name") String name,
        @JsonProperty("external_id") String externalId,
        @JsonProperty("stripe_customer_id") String stripeCustomerId,
        @JsonProperty("metadata") Map<String, String> metadata,
        @JsonProperty("domains") List<WOrgDomain> domains,
        @JsonProperty("created_at") String createdAt,
        @JsonProperty("updated_at") String updatedAt) {}
