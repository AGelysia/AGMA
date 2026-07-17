package dev.minecraftagent.standalone.common;

import java.time.Duration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

final class ReplayWindow {
  private final long ttlMillis;
  private final int maximumEntries;
  private final Map<UUID, Long> messageIds = new HashMap<>();
  private final Map<String, Long> nonces = new HashMap<>();

  ReplayWindow(Duration ttl, int maximumEntries) {
    if (ttl == null || ttl.isZero() || ttl.isNegative() || maximumEntries < 1) {
      throw new IllegalArgumentException("Replay window policy is invalid");
    }
    ttlMillis = ttl.toMillis();
    this.maximumEntries = maximumEntries;
  }

  synchronized boolean claim(UUID messageId, String nonce, long nowMillis) {
    purge(nowMillis);
    if (messageIds.containsKey(messageId)
        || nonces.containsKey(nonce)
        || messageIds.size() >= maximumEntries
        || nonces.size() >= maximumEntries) {
      return false;
    }
    var expires = nowMillis > Long.MAX_VALUE - ttlMillis ? Long.MAX_VALUE : nowMillis + ttlMillis;
    messageIds.put(messageId, expires);
    nonces.put(nonce, expires);
    return true;
  }

  private void purge(long nowMillis) {
    purge(messageIds, nowMillis);
    purge(nonces, nowMillis);
  }

  private static <T> void purge(Map<T, Long> entries, long nowMillis) {
    for (Iterator<Map.Entry<T, Long>> iterator = entries.entrySet().iterator();
        iterator.hasNext(); ) {
      if (iterator.next().getValue() <= nowMillis) {
        iterator.remove();
      }
    }
  }
}
