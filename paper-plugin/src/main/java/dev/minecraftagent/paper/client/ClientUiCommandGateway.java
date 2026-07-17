package dev.minecraftagent.paper.client;

import java.util.Objects;
import java.util.UUID;

/** Gateway for explicit player UI preferences; it cannot invoke commands or grant authority. */
public final class ClientUiCommandGateway {
  private final ClientConnectionRegistry connections;
  private final ControlSink sink;

  public ClientUiCommandGateway(ClientConnectionRegistry connections, ControlSink sink) {
    this.connections = Objects.requireNonNull(connections);
    this.sink = Objects.requireNonNull(sink);
  }

  public Result invoke(UUID playerUuid, Action action) {
    return invoke(playerUuid, action, null);
  }

  public Result invoke(UUID playerUuid, Action action, UUID viewId) {
    Objects.requireNonNull(playerUuid);
    Objects.requireNonNull(action);
    var connection = connections.lookup(playerUuid);
    if (connection.isEmpty() || !connection.orElseThrow().negotiated()) {
      return Result.CLIENT_UNAVAILABLE;
    }
    var state = connection.orElseThrow();
    if (!state.handshake().capabilities().supports(action.requiredFeature(), 1)) {
      return Result.CLIENT_UNAVAILABLE;
    }
    sink.send(playerUuid, new Control(state.generation(), action, viewId));
    return Result.SENT;
  }

  @FunctionalInterface
  public interface ControlSink {
    void send(UUID playerUuid, Control control);
  }

  public enum Action {
    PIN("pin", ClientFeature.OVERLAY),
    UNPIN("unpin", ClientFeature.OVERLAY),
    CLEAR("clear", ClientFeature.OVERLAY),
    LITEMATICA_PREVIEW_LOAD("litematica.preview.load", ClientFeature.LITEMATICA_PREVIEW),
    LITEMATICA_PREVIEW_REMOVE("litematica.preview.remove", ClientFeature.LITEMATICA_PREVIEW),
    LITEMATICA_MATERIAL_LIST_OPEN(
        "litematica.material_list.open", ClientFeature.LITEMATICA_MATERIAL_LIST);

    private final String wireName;
    private final ClientFeature requiredFeature;

    Action(String wireName, ClientFeature requiredFeature) {
      this.wireName = wireName;
      this.requiredFeature = requiredFeature;
    }

    public String wireName() {
      return wireName;
    }

    ClientFeature requiredFeature() {
      return requiredFeature;
    }
  }

  public enum Result {
    SENT,
    CLIENT_UNAVAILABLE
  }

  public record Control(long generation, Action action, UUID viewId) {
    public Control {
      if (generation < 1 || generation > Integer.MAX_VALUE) {
        throw new IllegalArgumentException("generation must be positive");
      }
      Objects.requireNonNull(action);
      if (action.requiredFeature() == ClientFeature.LITEMATICA_PREVIEW && viewId == null
          || action == Action.LITEMATICA_MATERIAL_LIST_OPEN && viewId == null) {
        throw new ClientProtocolException("CLIENT_UI_VIEW_ID_REQUIRED");
      }
    }
  }
}
