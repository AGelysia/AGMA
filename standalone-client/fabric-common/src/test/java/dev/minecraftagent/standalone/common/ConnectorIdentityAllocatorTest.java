package dev.minecraftagent.standalone.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class ConnectorIdentityAllocatorTest {
  private static final long NOW = Instant.parse("2026-07-17T00:00:00Z").toEpochMilli();

  @Test
  void allocatesFreshIdentitiesThatCannotBeClaimedInboundAgain() {
    var allocator = allocator();

    var first = allocator.allocate();
    var second = allocator.allocate();

    assertNotEquals(first.messageId(), second.messageId());
    assertNotEquals(first.nonce(), second.nonce());
    assertFalse(allocator.claimInbound(first.messageId(), first.nonce(), NOW));
    assertFalse(allocator.claimInbound(first.messageId(), "fresh-nonce", NOW));
  }

  @Test
  void rejectsInboundClaimsThatRepeatAnIdentityOrNonce() {
    var allocator = allocator();

    assertTrue(allocator.claimInbound(uuid(50), nonce(), NOW));
    assertFalse(allocator.claimInbound(uuid(50), nonce(), NOW));
    assertFalse(allocator.claimInbound(uuid(51), nonce(), NOW));
  }

  @Test
  void reusingARequestIdFailsWithRequestIdReused() {
    var allocator = allocator();
    var identity = allocator.allocate();

    var failure =
        assertThrows(ConnectorException.class, () -> allocator.allocate(identity.messageId()));
    assertEquals("REQUEST_ID_REUSED", failure.code());
  }

  @Test
  void exhaustedIdentifiersFailWithIdentityExhausted() {
    var fixedIdentifier = uuid(1);
    var allocator =
        new ConnectorIdentityAllocator(
            entropy(), () -> fixedIdentifier, clock(), Duration.ofSeconds(60), 4096);
    allocator.allocate();

    var failure = assertThrows(ConnectorException.class, allocator::allocate);
    assertEquals("IDENTITY_EXHAUSTED", failure.code());
  }

  @Test
  void distinctAllocationAvoidsTheExcludedIdentifiers() {
    var firstIdentifier = uuid(1);
    var secondIdentifier = uuid(2);
    var counter = new AtomicLong();
    var allocator =
        new ConnectorIdentityAllocator(
            entropy(),
            () -> counter.getAndIncrement() == 0 ? firstIdentifier : secondIdentifier,
            clock(),
            Duration.ofSeconds(60),
            4096);
    var first = allocator.allocate();
    assertEquals(firstIdentifier, first.messageId());

    var distinct = allocator.allocateDistinct(firstIdentifier, uuid(999));
    assertEquals(secondIdentifier, distinct.messageId());

    var failure =
        assertThrows(
            ConnectorException.class,
            () -> allocator.allocateDistinct(firstIdentifier, secondIdentifier));
    assertEquals("IDENTITY_EXHAUSTED", failure.code());
  }

  private static ConnectorIdentityAllocator allocator() {
    var identifiers = new AtomicLong();
    return new ConnectorIdentityAllocator(
        entropy(),
        () -> uuid(identifiers.getAndIncrement()),
        clock(),
        Duration.ofSeconds(60),
        4096);
  }

  private static Clock clock() {
    return Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC);
  }

  private static JavaHttpLocalConnector.EntropySource entropy() {
    var counter = new AtomicInteger(1);
    return bytes -> Arrays.fill(bytes, (byte) counter.getAndIncrement());
  }

  private static String nonce() {
    var bytes = new byte[16];
    bytes[0] = 9;
    return ConnectorAuthentication.encodeNonce(bytes);
  }

  private static UUID uuid(long value) {
    return new UUID(0x1111111111114111L, 0x8111000000000000L | value);
  }
}
