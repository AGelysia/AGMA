package dev.minecraftagent.standalone.common;

import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Allocates outbound message identities and guards inbound replay for one session.
 *
 * <p>Every allocation draws a candidate identifier from the injected supplier, pairs it with a
 * fresh nonce, and claims the pair in the session {@link ReplayWindow}, so the same (identifier,
 * nonce) can never be used twice in either direction. Instances hold no lock of their own; every
 * method must be invoked while the owning {@link ConnectorConnection} monitor is held so identity
 * admission stays atomic with session state transitions.
 */
final class ConnectorIdentityAllocator {
  private final JavaHttpLocalConnector.EntropySource entropy;
  private final Supplier<UUID> identifiers;
  private final Clock clock;
  private final ReplayWindow replayWindow;

  ConnectorIdentityAllocator(
      JavaHttpLocalConnector.EntropySource entropy,
      Supplier<UUID> identifiers,
      Clock clock,
      Duration replayTtl,
      int replayMaximumEntries) {
    this.entropy = Objects.requireNonNull(entropy, "entropy");
    this.identifiers = Objects.requireNonNull(identifiers, "identifiers");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.replayWindow = new ReplayWindow(replayTtl, replayMaximumEntries);
  }

  /** Claims a Runtime-initiated message against the replay window. */
  boolean claimInbound(UUID messageId, String nonce, long nowMillis) {
    return replayWindow.claim(messageId, nonce, nowMillis);
  }

  ConnectorMessageIdentity allocate() {
    for (var attempt = 0; attempt < 8; attempt++) {
      var candidate = Objects.requireNonNull(identifiers.get(), "identifier");
      var identity = tryIdentity(candidate);
      if (identity != null) {
        return identity;
      }
    }
    throw new ConnectorException(
        "IDENTITY_EXHAUSTED", "Connector message identity could not be allocated");
  }

  ConnectorMessageIdentity allocate(UUID messageId) {
    for (var attempt = 0; attempt < 8; attempt++) {
      var identity = tryIdentity(messageId);
      if (identity != null) {
        return identity;
      }
    }
    throw new ConnectorException(
        "REQUEST_ID_REUSED", "Connector request identifier was already used");
  }

  ConnectorMessageIdentity allocateDistinct(UUID... excluded) {
    for (var attempt = 0; attempt < 8; attempt++) {
      var candidate = Objects.requireNonNull(identifiers.get(), "identifier");
      if (Arrays.asList(excluded).contains(candidate)) {
        continue;
      }
      var identity = tryIdentity(candidate);
      if (identity != null) {
        return identity;
      }
    }
    throw new ConnectorException(
        "IDENTITY_EXHAUSTED", "Connector message identity could not be allocated");
  }

  private ConnectorMessageIdentity tryIdentity(UUID messageId) {
    var bytes = new byte[16];
    try {
      entropy.nextBytes(bytes);
      var nonce = ConnectorAuthentication.encodeNonce(bytes);
      var now = clock.millis();
      return replayWindow.claim(messageId, nonce, now)
          ? new ConnectorMessageIdentity(messageId, nonce)
          : null;
    } finally {
      Arrays.fill(bytes, (byte) 0);
    }
  }
}
