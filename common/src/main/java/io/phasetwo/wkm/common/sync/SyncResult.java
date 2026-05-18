package io.phasetwo.wkm.common.sync;

import java.util.ArrayList;
import java.util.List;

public final class SyncResult {

    private final String entityType;
    private final String workosId;
    private SyncAction action;
    private String keycloakId;
    private String reason;
    private final List<String> notes = new ArrayList<>();

    public SyncResult(String entityType, String workosId, SyncAction action) {
        this.entityType = entityType;
        this.workosId = workosId;
        this.action = action;
    }

    public static SyncResult created(String entityType, String workosId, String keycloakId) {
        SyncResult r = new SyncResult(entityType, workosId, SyncAction.CREATED);
        r.keycloakId = keycloakId;
        return r;
    }

    public static SyncResult updated(String entityType, String workosId, String keycloakId) {
        SyncResult r = new SyncResult(entityType, workosId, SyncAction.UPDATED);
        r.keycloakId = keycloakId;
        return r;
    }

    public static SyncResult skipped(String entityType, String workosId, String reason) {
        SyncResult r = new SyncResult(entityType, workosId, SyncAction.SKIPPED);
        r.reason = reason;
        return r;
    }

    public static SyncResult partial(
            String entityType, String workosId, String keycloakId, String reason) {
        SyncResult r = new SyncResult(entityType, workosId, SyncAction.PARTIAL);
        r.keycloakId = keycloakId;
        r.reason = reason;
        return r;
    }

    public static SyncResult failed(String entityType, String workosId, String reason) {
        SyncResult r = new SyncResult(entityType, workosId, SyncAction.FAILED);
        r.reason = reason;
        return r;
    }

    public static SyncResult deleted(String entityType, String workosId, String keycloakId) {
        SyncResult r = new SyncResult(entityType, workosId, SyncAction.DELETED);
        r.keycloakId = keycloakId;
        return r;
    }

    public String entityType() { return entityType; }
    public String workosId() { return workosId; }
    public String keycloakId() { return keycloakId; }
    public SyncAction action() { return action; }
    public String reason() { return reason; }
    public List<String> notes() { return notes; }

    public SyncResult withKeycloakId(String id) { this.keycloakId = id; return this; }
    public SyncResult withReason(String reason) { this.reason = reason; return this; }
    public SyncResult addNote(String note) { this.notes.add(note); return this; }

    @Override
    public String toString() {
        return entityType + " " + workosId + " -> " + action
                + (keycloakId != null ? " kc=" + keycloakId : "")
                + (reason != null ? " reason=" + reason : "");
    }
}
