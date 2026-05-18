package io.phasetwo.migration.common.sync;

@FunctionalInterface
public interface EntitySync<S> {
  SyncResult sync(S source);
}
