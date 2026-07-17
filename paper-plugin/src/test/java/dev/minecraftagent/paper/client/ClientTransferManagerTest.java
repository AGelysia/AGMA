package dev.minecraftagent.paper.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;

class ClientTransferManagerTest {
  private static final UUID PLAYER = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID MESSAGE = UUID.fromString("33333333-3333-4333-8333-333333333333");

  @Test
  void framesCompressedContentWithWholeAndPerChunkHashes() throws Exception {
    var manager =
        new ClientTransferManager(
            new ClientTransferManager.Limits(64, 4096, 64, 8192, 8, Duration.ofSeconds(5)));
    manager.open(PLAYER, 4);
    var plan = manager.prepare(PLAYER, 4, view("repeat ".repeat(300)), Instant.EPOCH);

    assertEquals(ClientTransferManager.Encoding.GZIP, plan.encoding());
    assertTrue(plan.chunks().stream().allMatch(chunk -> chunk.bytes().length <= 64));
    for (var chunk : plan.chunks()) {
      assertEquals(ClientTransferManager.sha256(chunk.bytes()), chunk.sha256());
    }
    var encoded = new ByteArrayOutputStream();
    for (var chunk : plan.chunks()) {
      encoded.write(chunk.bytes());
    }
    byte[] content;
    try (var gzip = new GZIPInputStream(new ByteArrayInputStream(encoded.toByteArray()))) {
      content = gzip.readAllBytes();
    }
    assertEquals(plan.contentSha256(), ClientTransferManager.sha256(content));
    assertEquals(content.length, plan.uncompressedBytes());
  }

  @Test
  void staleAckCannotConsumeCurrentTransferAndValidAckIsOnlyDisplayFact() {
    var manager = ClientTransferManager.withProductionLimits();
    manager.open(PLAYER, 7);
    var plan = manager.prepare(PLAYER, 7, view("hello"), Instant.EPOCH);

    var stale =
        ack(plan.transferId(), 6, ClientInboundMessage.Ack.Status.DISPLAYED, "VIEW_DISPLAYED");
    assertEquals(
        ClientTransferManager.AcknowledgementResult.STALE_GENERATION,
        manager.acknowledge(PLAYER, stale));
    assertEquals(1, manager.pendingCount(PLAYER));

    var valid =
        ack(plan.transferId(), 7, ClientInboundMessage.Ack.Status.DISPLAYED, "VIEW_DISPLAYED");
    assertEquals(
        ClientTransferManager.AcknowledgementResult.DISPLAY_REPORTED,
        manager.acknowledge(PLAYER, valid));
    assertEquals(0, manager.pendingCount(PLAYER));
    assertEquals(
        ClientTransferManager.AcknowledgementResult.UNKNOWN_TRANSFER,
        manager.acknowledge(PLAYER, valid));
  }

  @Test
  void transferlessClientErrorCannotConsumeUnrelatedDeliveries() {
    var manager = ClientTransferManager.withProductionLimits();
    manager.open(PLAYER, 7);
    var first = manager.prepare(PLAYER, 7, view("first"), Instant.EPOCH);
    var second = manager.prepare(PLAYER, 7, view("second"), Instant.EPOCH);

    var unscoped = new ClientInboundMessage.Error(MESSAGE, null, 7, "CLIENT_PRESENTATION_FAILED");
    assertEquals(
        ClientTransferManager.AcknowledgementResult.UNSCOPED_ERROR_REPORTED,
        manager.clientError(PLAYER, unscoped));
    assertEquals(2, manager.pendingCount(PLAYER));

    var scoped =
        new ClientInboundMessage.Error(MESSAGE, first.transferId(), 7, "VIEW_DECODE_FAILED");
    assertEquals(
        ClientTransferManager.AcknowledgementResult.REJECTION_REPORTED,
        manager.clientError(PLAYER, scoped));
    assertEquals(1, manager.pendingCount(PLAYER));
    assertTrue(manager.isPending(PLAYER, 7, second.transferId()));
  }

  @Test
  void timeoutDisconnectAndGenerationReplacementClearPendingState() {
    var manager = ClientTransferManager.withProductionLimits();
    manager.open(PLAYER, 1);
    manager.prepare(PLAYER, 1, view("hello"), Instant.EPOCH);
    assertTrue(manager.expire(Instant.EPOCH.plusSeconds(14)).isEmpty());
    assertEquals(1, manager.expire(Instant.EPOCH.plusSeconds(15)).size());

    manager.prepare(PLAYER, 1, view("again"), Instant.EPOCH);
    manager.open(PLAYER, 2);
    assertEquals(0, manager.pendingCount(PLAYER));
    assertEquals(
        "CLIENT_GENERATION_STALE",
        assertThrows(
                ClientProtocolException.class,
                () -> manager.prepare(PLAYER, 1, view("stale"), Instant.EPOCH))
            .code());

    manager.prepare(PLAYER, 2, view("current"), Instant.EPOCH);
    manager.disconnect(PLAYER);
    assertEquals(0, manager.pendingCount(PLAYER));
  }

  @Test
  void enforcesPerConnectionUncompressedPendingBudget() {
    var limits = new ClientTransferManager.Limits(256, 1600, 64, 2000, 8, Duration.ofSeconds(5));
    var manager = new ClientTransferManager(limits);
    manager.open(PLAYER, 1);
    manager.prepare(PLAYER, 1, view("x".repeat(900)), Instant.EPOCH);

    assertEquals(
        "CLIENT_PENDING_BYTE_BUDGET",
        assertThrows(
                ClientProtocolException.class,
                () -> manager.prepare(PLAYER, 1, view("y".repeat(900)), Instant.EPOCH))
            .code());
  }

  private static ClientStructuredView view(String text) {
    var content = new JsonObject();
    content.addProperty("text", text);
    return new ClientStructuredView(
        "1.0",
        UUID.randomUUID(),
        UUID.randomUUID(),
        ClientViewType.TEXT,
        1,
        "Transfer",
        "fallback",
        true,
        content);
  }

  private static ClientInboundMessage.Ack ack(
      UUID transferId, long generation, ClientInboundMessage.Ack.Status status, String code) {
    return new ClientInboundMessage.Ack(MESSAGE, transferId, generation, status, code);
  }
}
