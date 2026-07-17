package dev.minecraftagent.client.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.minecraftagent.client.litematica.LitematicaAdapterDiagnostic;
import dev.minecraftagent.client.transfer.ViewTransferAccumulator;
import dev.minecraftagent.client.ui.OverlayController;
import dev.minecraftagent.client.ui.OverlayPreferences;
import dev.minecraftagent.client.view.StructuredViewDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ClientPresentationSessionTest {
  private static final UUID TRANSFER_ID = UUID.fromString("d0000000-0000-4000-8000-000000000010");
  private static final UUID VIEW_ID = UUID.fromString("d0000000-0000-4000-8000-000000000011");
  private static final UUID REQUEST_ID = UUID.fromString("d0000000-0000-4000-8000-000000000012");
  private static final UUID BUILD_VIEW_ID = UUID.fromString("88888888-8888-4888-8888-888888888888");

  @Test
  void negotiatesReassemblesDisplaysAcknowledgesAndKeepsPinnedPreferenceOnDisconnect()
      throws Exception {
    var outbound = new ArrayList<byte[]>();
    var overlay = new OverlayController(OverlayPreferences.defaults());
    var actions = new RecordingActions();
    var session = session(overlay, outbound, actions);

    session.connect(advertisement());
    assertEquals("client.hello", outboundType(outbound.getFirst()));
    session.receive(
        frame(
            "server.hello", "{\"generation\":9,\"accepted\":true,\"viewSchemaVersion\":\"1.0\"}"));
    assertTrue(session.accepted());
    assertEquals(9, session.generation());

    byte[] view = textView("private answer", VIEW_ID, REQUEST_ID);
    session.receive(begin(view, VIEW_ID, REQUEST_ID));
    session.receive(chunk(view));

    assertEquals("private answer", overlay.snapshot().orElseThrow().view().fallbackText());
    JsonObject ack = outboundPayload(outbound.getLast());
    assertEquals("DISPLAYED", ack.get("status").getAsString());
    assertEquals("VIEW_DISPLAYED", ack.get("code").getAsString());

    session.receive(
        frame(
            "ui.control", "{\"generation\":9,\"action\":\"pin\",\"viewId\":\"" + VIEW_ID + "\"}"));
    assertTrue(overlay.snapshot().orElseThrow().pinned());

    session.disconnect();
    assertFalse(session.accepted());
    assertTrue(overlay.snapshot().isEmpty());
    assertTrue(overlay.preferences().pinned());
    assertEquals(2, actions.disconnects);
  }

  @Test
  void staleGenerationAndCorrelationMismatchAreRejectedWithoutChangingTheHud() throws Exception {
    var outbound = new ArrayList<byte[]>();
    var overlay = new OverlayController(OverlayPreferences.defaults());
    var session = session(overlay, outbound, new RecordingActions());
    session.connect(advertisement());
    session.receive(
        frame(
            "server.hello", "{\"generation\":9,\"accepted\":true,\"viewSchemaVersion\":\"1.0\"}"));
    outbound.clear();

    session.receive(frame("view.clear", "{\"generation\":8,\"viewId\":null}"));
    assertEquals("client.error", outboundType(outbound.getLast()));
    assertEquals(
        "CLIENT_GENERATION_STALE", outboundPayload(outbound.getLast()).get("code").getAsString());

    UUID differentView = UUID.fromString("d0000000-0000-4000-8000-000000000099");
    byte[] view = textView("mismatch", differentView, REQUEST_ID);
    session.receive(begin(view, VIEW_ID, REQUEST_ID));
    session.receive(chunk(view));

    assertTrue(overlay.snapshot().isEmpty());
    JsonObject ack = outboundPayload(outbound.getLast());
    assertEquals("REJECTED", ack.get("status").getAsString());
    assertEquals("VIEW_METADATA_MISMATCH", ack.get("code").getAsString());
  }

  @Test
  void litematicaControlsRequireAnExplicitViewAndNeverCarryAuthority() {
    var outbound = new ArrayList<byte[]>();
    var actions = new RecordingActions();
    var session = session(new OverlayController(OverlayPreferences.defaults()), outbound, actions);
    session.connect(advertisement());
    session.receive(
        frame(
            "server.hello", "{\"generation\":2,\"accepted\":true,\"viewSchemaVersion\":\"1.0\"}"));
    outbound.clear();

    session.receive(
        frame(
            "ui.control",
            "{\"generation\":2,\"action\":\"litematica.preview.load\",\"viewId\":null}"));
    assertTrue(actions.invocations.isEmpty());
    assertEquals(
        "CLIENT_UI_TARGET_REQUIRED", outboundPayload(outbound.getLast()).get("code").getAsString());

    session.receive(
        frame(
            "ui.control",
            "{\"generation\":2,\"action\":\"litematica.preview.remove\",\"viewId\":\""
                + VIEW_ID
                + "\"}"));
    assertEquals(List.of("LITEMATICA_PREVIEW_REMOVE:" + VIEW_ID), actions.invocations);
  }

  @Test
  void clearMessagesCleanAdapterOwnedStateWithoutRemovingPinnedOverlay() throws Exception {
    var outbound = new ArrayList<byte[]>();
    var overlay = new OverlayController(OverlayPreferences.defaults());
    var actions = new RecordingActions();
    var session = session(overlay, outbound, actions);
    session.connect(advertisement());
    session.receive(
        frame(
            "server.hello", "{\"generation\":9,\"accepted\":true,\"viewSchemaVersion\":\"1.0\"}"));
    byte[] view = textView("pinned", VIEW_ID, REQUEST_ID);
    session.receive(begin(view, VIEW_ID, REQUEST_ID));
    session.receive(chunk(view));
    session.receive(
        frame(
            "ui.control", "{\"generation\":9,\"action\":\"pin\",\"viewId\":\"" + VIEW_ID + "\"}"));

    session.receive(frame("view.clear", "{\"generation\":9,\"viewId\":null}"));
    assertTrue(overlay.snapshot().orElseThrow().pinned());
    assertEquals(2, actions.disconnects);

    session.receive(frame("view.clear", "{\"generation\":9,\"viewId\":\"" + VIEW_ID + "\"}"));
    assertEquals(List.of(VIEW_ID), actions.clears);
  }

  @Test
  void queuedDisplayFromAnOldGenerationCannotMutateOrAcknowledgeTheNewGeneration()
      throws Exception {
    var outbound = new ArrayList<byte[]>();
    var overlay = new OverlayController(OverlayPreferences.defaults());
    var actions = new RecordingActions();
    var dispatcher = new QueuedDispatcher();
    var session = session(overlay, outbound, actions, dispatcher);
    session.connect(advertisement());
    session.receive(
        frame(
            "server.hello", "{\"generation\":9,\"accepted\":true,\"viewSchemaVersion\":\"1.0\"}"));
    dispatcher.drain();
    outbound.clear();

    byte[] view = textView("stale", VIEW_ID, REQUEST_ID);
    session.receive(begin(view, VIEW_ID, REQUEST_ID));
    session.receive(chunk(view));
    session.receive(
        frame(
            "server.hello", "{\"generation\":10,\"accepted\":true,\"viewSchemaVersion\":\"1.0\"}"));
    dispatcher.drain();

    assertTrue(overlay.snapshot().isEmpty());
    assertTrue(outbound.isEmpty());
    assertEquals(10, session.generation());
    assertEquals(2, actions.disconnects);
  }

  @Test
  void freshClientCanDisplayAShowWhoseFirstObservedRevisionIsGreaterThanOne() throws Exception {
    var outbound = new ArrayList<byte[]>();
    var overlay = new OverlayController(OverlayPreferences.defaults());
    var session = session(overlay, outbound, new RecordingActions());
    session.connect(advertisement());
    session.receive(
        frame(
            "server.hello", "{\"generation\":9,\"accepted\":true,\"viewSchemaVersion\":\"1.0\"}"));
    byte[] view =
        new String(textView("current", VIEW_ID, REQUEST_ID), StandardCharsets.UTF_8)
            .replace("\"revision\":1", "\"revision\":2")
            .getBytes(StandardCharsets.UTF_8);

    session.receive(begin(view, VIEW_ID, REQUEST_ID, 2, "show"));
    session.receive(chunk(view));

    assertEquals(2, overlay.snapshot().orElseThrow().view().revision());
    assertEquals("DISPLAYED", outboundPayload(outbound.getLast()).get("status").getAsString());
  }

  @Test
  void loadPreparationRunsOffTheSessionMonitorAndCannotCrossAGenerationChange() throws Exception {
    var outbound = new ArrayList<byte[]>();
    var dispatcher = new QueuedDispatcher();
    var actions = new BlockingActions();
    var session =
        session(
            new OverlayController(OverlayPreferences.defaults()), outbound, actions, dispatcher);
    actions.session.set(session);
    session.connect(advertisement());
    session.receive(
        frame(
            "server.hello", "{\"generation\":9,\"accepted\":true,\"viewSchemaVersion\":\"1.0\"}"));
    dispatcher.drain();

    var preparationExecutor = Executors.newSingleThreadExecutor();
    var transitionExecutor = Executors.newSingleThreadExecutor();
    try {
      var preparation =
          preparationExecutor.submit(
              () ->
                  session.receive(
                      frame(
                          "ui.control",
                          "{\"generation\":9,\"action\":\"litematica.preview.load\",\"viewId\":\""
                              + VIEW_ID
                              + "\"}")));
      assertTrue(actions.started.await(1, TimeUnit.SECONDS));
      assertFalse(actions.sessionMonitorHeld);

      var transition =
          transitionExecutor.submit(
              () ->
                  session.receive(
                      frame(
                          "server.hello",
                          "{\"generation\":10,\"accepted\":true,\"viewSchemaVersion\":\"1.0\"}")));
      transition.get(1, TimeUnit.SECONDS);
      assertEquals(10, session.generation());

      actions.release.countDown();
      preparation.get(1, TimeUnit.SECONDS);
      dispatcher.drain();
      assertEquals(0, actions.invocations.get());
    } finally {
      actions.release.countDown();
      preparationExecutor.shutdownNow();
      transitionExecutor.shutdownNow();
    }
  }

  @Test
  void rollsBackAStagedBuildArtifactWhenTheOverlayRejectsTheUpdate() throws Exception {
    var outbound = new ArrayList<byte[]>();
    var actions = new TransactionalActions();
    var session =
        session(
            new OverlayController(OverlayPreferences.defaults()),
            outbound,
            actions,
            action -> {
              action.run();
              return true;
            },
            new StructuredViewDecoder((blockId, properties) -> {}));
    session.connect(advertisement());
    session.receive(
        frame(
            "server.hello", "{\"generation\":9,\"accepted\":true,\"viewSchemaVersion\":\"1.0\"}"));
    outbound.clear();
    byte[] view = buildView(2);

    session.receive(begin(view, BUILD_VIEW_ID, REQUEST_ID, 2, "update"));
    session.receive(chunk(view));

    assertEquals(1, actions.stages);
    assertEquals(0, actions.commits);
    assertEquals(1, actions.discards);
    assertEquals("VIEW_UNKNOWN", outboundPayload(outbound.getLast()).get("code").getAsString());
  }

  private static ClientPresentationSession session(
      OverlayController overlay,
      List<byte[]> outbound,
      ClientPresentationSession.PresentationActionSink actions) {
    return session(
        overlay,
        outbound,
        actions,
        action -> {
          action.run();
          return true;
        });
  }

  private static ClientPresentationSession session(
      OverlayController overlay,
      List<byte[]> outbound,
      ClientPresentationSession.PresentationActionSink actions,
      ClientPresentationSession.ClientThreadDispatcher dispatcher) {
    return session(overlay, outbound, actions, dispatcher, new StructuredViewDecoder());
  }

  private static ClientPresentationSession session(
      OverlayController overlay,
      List<byte[]> outbound,
      ClientPresentationSession.PresentationActionSink actions,
      ClientPresentationSession.ClientThreadDispatcher dispatcher,
      StructuredViewDecoder decoder) {
    return new ClientPresentationSession(
        new ClientPayloadCodec(),
        new ViewTransferAccumulator(),
        decoder,
        overlay,
        dispatcher,
        outbound::add,
        actions);
  }

  private static ClientHandshakeAdvertisement advertisement() {
    return new ClientHandshakeAdvertisement(
        "0.1.0",
        1,
        1,
        1,
        0,
        0,
        Optional.empty(),
        Optional.empty(),
        new LitematicaAdapterDiagnostic(
            LitematicaAdapterDiagnostic.Status.NOT_INSTALLED,
            "1.21.11",
            "0.19.3",
            Optional.empty(),
            Optional.empty(),
            Optional.empty()));
  }

  private static byte[] textView(String text, UUID viewId, UUID requestId) {
    return ("{\"viewSchemaVersion\":\"1.0\",\"viewId\":\""
            + viewId
            + "\",\"requestId\":\""
            + requestId
            + "\",\"viewType\":\"text\",\"revision\":1,\"title\":\"Agent response\","
            + "\"fallbackText\":\""
            + text
            + "\",\"pinnable\":true,\"content\":{\"text\":\""
            + text
            + "\"}}")
        .getBytes(StandardCharsets.UTF_8);
  }

  private static byte[] buildView(int revision) throws Exception {
    Path protocol = Path.of(System.getProperty("minecraftAgent.protocolDir"));
    String content = Files.readString(protocol.resolve("fixtures/valid/build-preview.json"));
    if (revision != 1) {
      content = content.replace("\"revision\": 1", "\"revision\": " + revision);
    }
    return ("{\"viewSchemaVersion\":\"1.0\",\"viewId\":\""
            + BUILD_VIEW_ID
            + "\",\"requestId\":\""
            + REQUEST_ID
            + "\",\"viewType\":\"build_preview\",\"revision\":"
            + revision
            + ",\"title\":\"Build\",\"fallbackText\":\"Build preview\",\"pinnable\":true,"
            + "\"content\":"
            + content
            + "}")
        .getBytes(StandardCharsets.UTF_8);
  }

  private static byte[] begin(byte[] view, UUID descriptorViewId, UUID requestId) throws Exception {
    return begin(view, descriptorViewId, requestId, 1, "show");
  }

  private static byte[] begin(
      byte[] view, UUID descriptorViewId, UUID requestId, int revision, String mode)
      throws Exception {
    return frame(
        "view.begin",
        "{\"generation\":9,\"transferId\":\""
            + TRANSFER_ID
            + "\",\"viewId\":\""
            + descriptorViewId
            + "\",\"requestId\":\""
            + requestId
            + "\",\"revision\":"
            + revision
            + ",\"mode\":\""
            + mode
            + "\",\"encoding\":\"identity\","
            + "\"compressedBytes\":"
            + view.length
            + ",\"uncompressedBytes\":"
            + view.length
            + ",\"chunkCount\":1,\"contentSha256\":\""
            + hash(view)
            + "\"}");
  }

  private static byte[] chunk(byte[] view) throws Exception {
    return frame(
        "view.chunk",
        "{\"generation\":9,\"transferId\":\""
            + TRANSFER_ID
            + "\",\"index\":0,\"byteLength\":"
            + view.length
            + ",\"sha256\":\""
            + hash(view)
            + "\",\"data\":\""
            + Base64.getEncoder().encodeToString(view)
            + "\"}");
  }

  private static byte[] frame(String type, String payload) {
    return ("{\"clientPayloadVersion\":\"1.0\",\"messageId\":\""
            + UUID.randomUUID()
            + "\",\"type\":\""
            + type
            + "\",\"payload\":"
            + payload
            + "}")
        .getBytes(StandardCharsets.UTF_8);
  }

  private static String hash(byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }

  private static String outboundType(byte[] bytes) {
    return JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8))
        .getAsJsonObject()
        .get("type")
        .getAsString();
  }

  private static JsonObject outboundPayload(byte[] bytes) {
    return JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8))
        .getAsJsonObject()
        .getAsJsonObject("payload");
  }

  private static final class RecordingActions
      implements ClientPresentationSession.PresentationActionSink {
    private final List<String> invocations = new ArrayList<>();
    private final List<UUID> clears = new ArrayList<>();
    private int disconnects;

    @Override
    public ClientPresentationSession.PresentationAction prepare(
        ClientServerMessage.Action action, UUID viewId) {
      return () -> invocations.add(action.name() + ":" + viewId);
    }

    @Override
    public void disconnect() {
      disconnects++;
    }

    @Override
    public void clear(UUID viewId) {
      clears.add(viewId);
    }
  }

  private static final class QueuedDispatcher
      implements ClientPresentationSession.ClientThreadDispatcher {
    private final Queue<Runnable> tasks = new ConcurrentLinkedQueue<>();

    @Override
    public boolean execute(Runnable action) {
      tasks.add(action);
      return true;
    }

    private void drain() {
      Runnable task;
      while ((task = tasks.poll()) != null) {
        task.run();
      }
    }
  }

  private static final class BlockingActions
      implements ClientPresentationSession.PresentationActionSink {
    private final AtomicReference<ClientPresentationSession> session = new AtomicReference<>();
    private final CountDownLatch started = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private final AtomicInteger invocations = new AtomicInteger();
    private volatile boolean sessionMonitorHeld;

    @Override
    public ClientPresentationSession.PresentationAction prepare(
        ClientServerMessage.Action action, UUID viewId) {
      sessionMonitorHeld = Thread.holdsLock(session.get());
      started.countDown();
      try {
        release.await();
      } catch (InterruptedException failure) {
        Thread.currentThread().interrupt();
      }
      return invocations::incrementAndGet;
    }
  }

  private static final class TransactionalActions
      implements ClientPresentationSession.PresentationActionSink {
    private int stages;
    private int commits;
    private int discards;

    @Override
    public ClientPresentationSession.PresentationAction prepare(
        ClientServerMessage.Action action, UUID viewId) {
      return () -> {};
    }

    @Override
    public boolean stage(dev.minecraftagent.client.view.StructuredView view) {
      stages++;
      return true;
    }

    @Override
    public boolean commit(
        dev.minecraftagent.client.view.StructuredView view, java.util.Set<UUID> displayedViewIds) {
      commits++;
      return true;
    }

    @Override
    public void discard(dev.minecraftagent.client.view.StructuredView view) {
      discards++;
    }
  }
}
