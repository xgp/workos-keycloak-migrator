package io.phasetwo.wkm.common.workos.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record Page<T>(
        @JsonProperty("data") List<T> data,
        @JsonProperty("list_metadata") Cursor listMetadata) {
    public Page {
        if (data == null) data = List.of();
        if (listMetadata == null) listMetadata = Cursor.empty();
    }
}
