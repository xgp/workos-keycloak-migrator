package io.phasetwo.migration.common.state;

import static org.assertj.core.api.Assertions.assertThat;

import io.phasetwo.migration.common.sync.SyncAction;
import io.phasetwo.migration.common.sync.SyncResult;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CountersTest {

  @Test
  void recordsPerEntityAndAction() {
    Counters c = new Counters();
    c.record(SyncResult.created("user", "u1", "kc1"));
    c.record(SyncResult.created("user", "u2", "kc2"));
    c.record(SyncResult.updated("user", "u3", "kc3"));
    c.record(SyncResult.skipped("organization", "o1", "unchanged"));

    Map<String, Map<SyncAction, Integer>> snap = c.snapshot();
    assertThat(snap.get("user").get(SyncAction.CREATED)).isEqualTo(2);
    assertThat(snap.get("user").get(SyncAction.UPDATED)).isEqualTo(1);
    assertThat(snap.get("organization").get(SyncAction.SKIPPED)).isEqualTo(1);
    assertThat(c.total(SyncAction.CREATED)).isEqualTo(2);
    assertThat(c.total(SyncAction.FAILED)).isZero();
  }
}
