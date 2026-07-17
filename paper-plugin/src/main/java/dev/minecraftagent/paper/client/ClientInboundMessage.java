package dev.minecraftagent.paper.client;

import java.util.Objects;
import java.util.UUID;

/** Closed set of untrusted Fabric-to-Paper messages. Player identity is supplied out of band. */
public sealed interface ClientInboundMessage {
  UUID messageId();

  record Hello(UUID messageId, ClientHandshake handshake) implements ClientInboundMessage {
    public Hello {
      Objects.requireNonNull(messageId);
      Objects.requireNonNull(handshake);
    }
  }

  record Ack(UUID messageId, UUID transferId, long generation, Status status, String code)
      implements ClientInboundMessage {
    public Ack {
      Objects.requireNonNull(messageId);
      Objects.requireNonNull(transferId);
      Objects.requireNonNull(status);
      if (generation < 1 || generation > Integer.MAX_VALUE) {
        throw new ClientProtocolException("CLIENT_GENERATION_INVALID");
      }
      if (!validCode(code)) {
        throw new ClientProtocolException("CLIENT_ACK_INVALID");
      }
    }

    public enum Status {
      DISPLAYED("DISPLAYED"),
      REJECTED("REJECTED");

      private final String wireName;

      Status(String wireName) {
        this.wireName = wireName;
      }

      public String wireName() {
        return wireName;
      }

      public static Status fromWireName(String wireName) {
        return switch (wireName) {
          case "DISPLAYED" -> DISPLAYED;
          case "REJECTED" -> REJECTED;
          default -> throw new ClientProtocolException("CLIENT_ACK_STATUS_INVALID");
        };
      }
    }
  }

  record Error(UUID messageId, UUID transferId, long generation, String code)
      implements ClientInboundMessage {
    public Error {
      Objects.requireNonNull(messageId);
      if (generation < 1 || generation > Integer.MAX_VALUE) {
        throw new ClientProtocolException("CLIENT_GENERATION_INVALID");
      }
      if (!validCode(code)) {
        throw new ClientProtocolException("CLIENT_ERROR_INVALID");
      }
    }
  }

  private static boolean validCode(String code) {
    return code != null && code.length() <= 64 && code.matches("[A-Z][A-Z0-9_]{1,63}");
  }
}
