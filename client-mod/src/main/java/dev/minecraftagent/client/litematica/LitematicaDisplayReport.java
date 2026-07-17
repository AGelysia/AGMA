package dev.minecraftagent.client.litematica;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Untrusted display telemetry. This value deliberately has no permission, approval, or execution
 * field and must never be used as server authorization.
 */
public record LitematicaDisplayReport(
    UUID previewId, State state, Optional<String> contentSha256, Optional<Failure> failure) {
  public enum State {
    LOADED,
    REMOVED,
    MATERIAL_LIST_OPEN,
    FAILED
  }

  public enum Failure {
    ADAPTER_UNAVAILABLE,
    WRONG_THREAD,
    INVALID_REQUEST,
    MANAGED_FILE_UNAVAILABLE,
    MANAGED_FILE_HASH_MISMATCH,
    PREVIEW_LIMIT_REACHED,
    PREVIEW_ALREADY_LOADED,
    PREVIEW_NOT_FOUND,
    ADAPTER_CALL_FAILED
  }

  public LitematicaDisplayReport {
    Objects.requireNonNull(previewId, "previewId");
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(contentSha256, "contentSha256");
    Objects.requireNonNull(failure, "failure");
    if ((state == State.FAILED) != failure.isPresent()) {
      throw new IllegalArgumentException("only failed display reports carry a failure code");
    }
  }

  static LitematicaDisplayReport success(UUID previewId, String hash, State state) {
    return new LitematicaDisplayReport(
        previewId, state, Optional.ofNullable(hash), Optional.empty());
  }

  static LitematicaDisplayReport failed(UUID previewId, String hash, Failure failure) {
    return new LitematicaDisplayReport(
        previewId, State.FAILED, Optional.ofNullable(hash), Optional.of(failure));
  }
}
