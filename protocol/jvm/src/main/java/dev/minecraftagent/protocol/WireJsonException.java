package dev.minecraftagent.protocol;

/** Signals that a raw JSON document violated the shared strict grammar or a parse budget. */
public final class WireJsonException extends RuntimeException {
  /** Why the document was rejected. */
  public enum Reason {
    /** The document is not strict JSON. */
    JSON_INVALID,

    /** The document is valid JSON but exceeded a depth, node, field, item or character budget. */
    JSON_LIMIT_EXCEEDED,

    /** The document repeated an object field name. */
    DUPLICATE_FIELD,
  }

  private final Reason reason;

  public WireJsonException(Reason reason) {
    super(reason.name());
    this.reason = reason;
  }

  public Reason reason() {
    return reason;
  }
}
