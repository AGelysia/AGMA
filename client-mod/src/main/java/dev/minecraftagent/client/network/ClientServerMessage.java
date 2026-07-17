package dev.minecraftagent.client.network;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/** Closed set of Paper-to-client presentation messages. */
public sealed interface ClientServerMessage {
  UUID messageId();

  record ServerHello(UUID messageId, long generation, boolean accepted, String viewSchemaVersion)
      implements ClientServerMessage {
    public ServerHello {
      Objects.requireNonNull(messageId);
    }
  }

  record ViewBegin(
      UUID messageId,
      long generation,
      UUID transferId,
      UUID viewId,
      UUID requestId,
      int revision,
      Mode mode,
      Encoding encoding,
      int compressedBytes,
      int uncompressedBytes,
      int chunkCount,
      String contentSha256)
      implements ClientServerMessage {
    public ViewBegin {
      Objects.requireNonNull(messageId);
      Objects.requireNonNull(transferId);
      Objects.requireNonNull(viewId);
      Objects.requireNonNull(requestId);
      Objects.requireNonNull(mode);
      Objects.requireNonNull(encoding);
      Objects.requireNonNull(contentSha256);
    }
  }

  record ViewChunk(
      UUID messageId,
      long generation,
      UUID transferId,
      int index,
      int byteLength,
      String sha256,
      byte[] data)
      implements ClientServerMessage {
    public ViewChunk {
      Objects.requireNonNull(messageId);
      Objects.requireNonNull(transferId);
      Objects.requireNonNull(sha256);
      data = Arrays.copyOf(Objects.requireNonNull(data), data.length);
    }

    @Override
    public byte[] data() {
      return Arrays.copyOf(data, data.length);
    }
  }

  record ViewClear(UUID messageId, long generation, UUID viewId) implements ClientServerMessage {
    public ViewClear {
      Objects.requireNonNull(messageId);
    }
  }

  record UiControl(UUID messageId, long generation, Action action, UUID viewId)
      implements ClientServerMessage {
    public UiControl {
      Objects.requireNonNull(messageId);
      Objects.requireNonNull(action);
    }
  }

  enum Mode {
    SHOW,
    UPDATE
  }

  enum Encoding {
    IDENTITY,
    GZIP
  }

  enum Action {
    PIN,
    UNPIN,
    CLEAR,
    LITEMATICA_PREVIEW_LOAD,
    LITEMATICA_PREVIEW_REMOVE,
    LITEMATICA_MATERIAL_LIST_OPEN
  }
}
