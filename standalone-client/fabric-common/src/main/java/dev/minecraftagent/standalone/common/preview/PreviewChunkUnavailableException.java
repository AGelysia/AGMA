package dev.minecraftagent.standalone.common.preview;

/** A region cell could not be read because its chunk became unavailable during the snapshot. */
@SuppressWarnings("serial")
public final class PreviewChunkUnavailableException extends RuntimeException {
  public PreviewChunkUnavailableException(String message) {
    super(message);
  }
}
