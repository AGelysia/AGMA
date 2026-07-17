package dev.minecraftagent.paper.client;

/** A stable, non-sensitive reason for rejecting a client-channel message or transfer. */
public final class ClientProtocolException extends RuntimeException {
  private final String code;

  public ClientProtocolException(String code) {
    super(code);
    this.code = code;
  }

  public String code() {
    return code;
  }
}
