package dev.minecraftagent.standalone.common;

import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Remembers recent terminal correlations so a late Runtime terminal cannot complete twice. */
final class SettledRequestWindow {
  private final long ttlMillis;
  private final int maximumEntries;
  private final Map<UUID, Long> entries = new LinkedHashMap<>();

  SettledRequestWindow(Duration ttl, int maximumEntries) {
    if (ttl == null || ttl.isZero() || ttl.isNegative() || maximumEntries < 1) {
      throw new IllegalArgumentException("Settled request policy is invalid");
    }
    ttlMillis = ttl.toMillis();
    this.maximumEntries = maximumEntries;
  }

  synchronized void remember(UUID requestId, long nowMillis) {
    purge(nowMillis);
    entries.remove(requestId);
    while (entries.size() >= maximumEntries) {
      var oldest = entries.keySet().iterator();
      if (!oldest.hasNext()) {
        break;
      }
      oldest.next();
      oldest.remove();
    }
    var expires = nowMillis > Long.MAX_VALUE - ttlMillis ? Long.MAX_VALUE : nowMillis + ttlMillis;
    entries.put(requestId, expires);
  }

  synchronized boolean contains(UUID requestId, long nowMillis) {
    purge(nowMillis);
    return entries.containsKey(requestId);
  }

  private void purge(long nowMillis) {
    for (Iterator<Map.Entry<UUID, Long>> iterator = entries.entrySet().iterator();
        iterator.hasNext(); ) {
      if (iterator.next().getValue() <= nowMillis) {
        iterator.remove();
      }
    }
  }
}
