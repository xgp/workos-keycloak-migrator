package io.phasetwo.wkm.common.state;

import io.phasetwo.wkm.common.AttributeKeys;
import io.phasetwo.wkm.common.sync.SyncAction;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.RealmRepresentation;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Read/write realm-scoped migration state. Backed by RealmRepresentation.attributes which Keycloak
 * exposes via the admin client.
 */
public class MigrationState {

    private final RealmResource realm;
    private RealmRepresentation cached;

    public MigrationState(RealmResource realm) {
        this.realm = realm;
    }

    private RealmRepresentation reload() {
        cached = realm.toRepresentation();
        if (cached.getAttributes() == null) cached.setAttributes(new HashMap<>());
        return cached;
    }

    public synchronized String get(String key) {
        if (cached == null) reload();
        return cached.getAttributes().get(key);
    }

    public synchronized void set(String key, String value) {
        if (cached == null) reload();
        Map<String, String> attrs = cached.getAttributes();
        if (value == null) attrs.remove(key); else attrs.put(key, value);
        flush();
    }

    public synchronized void setAll(Map<String, String> entries) {
        if (cached == null) reload();
        cached.getAttributes().putAll(entries);
        flush();
    }

    public synchronized void clearPrefix(String prefix) {
        if (cached == null) reload();
        cached.getAttributes().keySet().removeIf(k -> k.startsWith(prefix));
        flush();
    }

    private void flush() {
        realm.update(cached);
    }

    // Convenience helpers

    public String getCursor(String entity) {
        return get(AttributeKeys.REALM_CURSOR_PREFIX + entity);
    }

    public void setCursor(String entity, String cursor) {
        set(AttributeKeys.REALM_CURSOR_PREFIX + entity, cursor);
    }

    public void clearCursors() {
        clearPrefix(AttributeKeys.REALM_CURSOR_PREFIX);
    }

    public void touchLastRun(String status) {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put(AttributeKeys.REALM_LAST_RUN_AT, Instant.now().toString());
        entries.put(AttributeKeys.REALM_LAST_RUN_STATUS, status);
        setAll(entries);
    }

    public void writeCounters(String entityType, Map<SyncAction, Integer> counts) {
        Map<String, String> entries = new LinkedHashMap<>();
        for (SyncAction a : SyncAction.values()) {
            Integer c = counts.get(a);
            if (c != null) {
                entries.put(
                        AttributeKeys.REALM_COUNTS_PREFIX
                                + entityType
                                + "."
                                + a.name().toLowerCase(),
                        Integer.toString(c));
            }
        }
        if (!entries.isEmpty()) setAll(entries);
    }
}
