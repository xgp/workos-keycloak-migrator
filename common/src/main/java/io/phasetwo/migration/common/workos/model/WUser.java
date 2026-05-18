package io.phasetwo.migration.common.workos.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record WUser(
    @JsonProperty("id") String id,
    @JsonProperty("email") String email,
    @JsonProperty("email_verified") Boolean emailVerified,
    @JsonProperty("first_name") String firstName,
    @JsonProperty("last_name") String lastName,
    @JsonProperty("profile_picture_url") String profilePictureUrl,
    @JsonProperty("external_id") String externalId,
    @JsonProperty("locale") String locale,
    @JsonProperty("last_sign_in_at") String lastSignInAt,
    @JsonProperty("created_at") String createdAt,
    @JsonProperty("updated_at") String updatedAt,
    @JsonProperty("metadata") Map<String, String> metadata) {}
