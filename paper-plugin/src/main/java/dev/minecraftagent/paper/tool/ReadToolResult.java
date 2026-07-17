package dev.minecraftagent.paper.tool;

import com.google.gson.JsonObject;
import java.util.Objects;

public record ReadToolResult(
    Status status, Source source, Trust trust, JsonObject result, Error error) {
  public enum Status {
    SUCCEEDED("succeeded"),
    REJECTED("rejected"),
    FAILED("failed");

    private final String protocolName;

    Status(String protocolName) {
      this.protocolName = protocolName;
    }

    public String protocolName() {
      return protocolName;
    }
  }

  public enum Source {
    PAPER_API("paper_api"),
    PAPER_POLICY("paper_policy"),
    SERVER_REGISTRY("server_registry");

    private final String protocolName;

    Source(String protocolName) {
      this.protocolName = protocolName;
    }

    public String protocolName() {
      return protocolName;
    }
  }

  public enum Trust {
    AUTHORITATIVE("authoritative");

    private final String protocolName;

    Trust(String protocolName) {
      this.protocolName = protocolName;
    }

    public String protocolName() {
      return protocolName;
    }
  }

  public record Error(String code, String message, boolean retryable) {
    public Error {
      Objects.requireNonNull(code);
      Objects.requireNonNull(message);
    }
  }

  public ReadToolResult {
    Objects.requireNonNull(status);
    Objects.requireNonNull(source);
    Objects.requireNonNull(trust);
    if (status == Status.SUCCEEDED) {
      result = Objects.requireNonNull(result).deepCopy();
      if (error != null) {
        throw new IllegalArgumentException("Successful tool result cannot contain an error");
      }
    } else {
      Objects.requireNonNull(error);
      if (result != null) {
        throw new IllegalArgumentException("Rejected tool result cannot contain data");
      }
    }
  }

  public static ReadToolResult succeeded(Source source, JsonObject result) {
    return new ReadToolResult(Status.SUCCEEDED, source, Trust.AUTHORITATIVE, result, null);
  }

  public static ReadToolResult rejected(Source source, String code, String message) {
    return new ReadToolResult(
        Status.REJECTED, source, Trust.AUTHORITATIVE, null, new Error(code, message, false));
  }

  public static ReadToolResult failed(
      Source source, String code, String message, boolean retryable) {
    return new ReadToolResult(
        Status.FAILED, source, Trust.AUTHORITATIVE, null, new Error(code, message, retryable));
  }
}
