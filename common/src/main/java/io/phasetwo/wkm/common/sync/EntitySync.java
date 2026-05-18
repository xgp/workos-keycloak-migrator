package io.phasetwo.wkm.common.sync;

@FunctionalInterface
public interface EntitySync<S> {
    SyncResult sync(S source);
}
