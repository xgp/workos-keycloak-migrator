package io.phasetwo.migration.common.workos.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record Cursor(@JsonProperty("before") String before, @JsonProperty("after") String after) {
    public static Cursor empty() {
        return new Cursor(null, null);
    }
}
