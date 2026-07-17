package dev.minecraftagent.paper.setup;

import java.util.Objects;
import java.util.regex.Pattern;

/** A setup view that can be rendered without exposing paths, credentials, or provider details. */
public record RedactedSetupSnapshot(SetupState state, String diagnosticCode) {
  private static final Pattern DIAGNOSTIC_CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");

  public RedactedSetupSnapshot {
    Objects.requireNonNull(state);
    if (diagnosticCode != null && !DIAGNOSTIC_CODE.matcher(diagnosticCode).matches()) {
      throw new IllegalArgumentException("diagnosticCode must be a stable diagnostic code");
    }
    if ((state == SetupState.FAILED) != (diagnosticCode != null)) {
      throw new IllegalArgumentException("Only failed snapshots carry a diagnostic code");
    }
  }

  public static RedactedSetupSnapshot of(SetupState state) {
    return new RedactedSetupSnapshot(state, null);
  }

  public static RedactedSetupSnapshot failed(String diagnosticCode) {
    return new RedactedSetupSnapshot(SetupState.FAILED, diagnosticCode);
  }
}
