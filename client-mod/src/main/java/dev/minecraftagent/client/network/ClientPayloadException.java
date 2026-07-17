package dev.minecraftagent.client.network;

public final class ClientPayloadException extends RuntimeException {
  private final String code;

  public ClientPayloadException(String code) {
    super(code);
    this.code = code;
  }

  public String code() {
    return code;
  }
}
