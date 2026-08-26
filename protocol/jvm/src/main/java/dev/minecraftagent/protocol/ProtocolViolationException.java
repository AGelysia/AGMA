package dev.minecraftagent.protocol;

/**
 * A stable, non-sensitive reason for rejecting a client-channel frame.
 *
 * <p>The shared grammar reports conditions; each end supplies the stable code vocabulary it already
 * publishes on the wire, so moving the grammar into this module never renames a code.
 */
public final class ProtocolViolationException extends RuntimeException {
  private final String code;

  public ProtocolViolationException(String code) {
    super(code);
    this.code = code;
  }

  public String code() {
    return code;
  }
}
