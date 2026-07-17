package dev.minecraftagent.paper.transport;

import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

final class ReplayCache {
  private static final int MAX_ENTRIES = 2048;
  private static final Duration RETENTION = Duration.ofSeconds(60);

  private final Map<String, Instant> entries = new LinkedHashMap<>();

  synchronized boolean accept(String messageId, String nonce, Instant now) {
    removeExpired(now);
    var messageKey = "message:" + messageId;
    var nonceKey = "nonce:" + nonce;
    if (entries.containsKey(messageKey) || entries.containsKey(nonceKey)) {
      return false;
    }
    if (entries.size() + 2 > MAX_ENTRIES) {
      return false;
    }
    var expires = now.plus(RETENTION);
    entries.put(messageKey, expires);
    entries.put(nonceKey, expires);
    return true;
  }

  private void removeExpired(Instant now) {
    Iterator<Map.Entry<String, Instant>> iterator = entries.entrySet().iterator();
    while (iterator.hasNext()) {
      if (!iterator.next().getValue().isAfter(now)) {
        iterator.remove();
      }
    }
  }
}
