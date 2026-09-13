package dev.minecraftagent.standalone.common.preview;

/** A bounded preview limit was exceeded; the executor maps this to PREVIEW_LIMIT_EXCEEDED. */
@SuppressWarnings("serial")
public final class PreviewLimitException extends IllegalArgumentException {
  public PreviewLimitException(String message) {
    super(message);
  }
}
