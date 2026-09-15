package dev.minecraftagent.standalone.common;

/**
 * Maps runtime lifecycle states and startup failure codes to the standalone client's existing lang
 * keys, so version shells never render raw enum names or wire codes.
 */
public final class ClientRuntimeStateText {
  private ClientRuntimeStateText() {}

  /** Lang key for the short localized name of one lifecycle state. */
  public static String stateKey(ClientLifecycleState state) {
    return switch (state) {
      case UNCONFIGURED -> "screen.agma_standalone.runtime_unconfigured";
      case STOPPED -> "screen.agma_standalone.runtime_stopped_state";
      case STARTING -> "screen.agma_standalone.runtime_starting_state";
      case READY -> "screen.agma_standalone.runtime_running_state";
      case STOPPING -> "screen.agma_standalone.runtime_stopping_state";
      case ERROR -> "screen.agma_standalone.runtime_error_state";
    };
  }

  /**
   * Lang key for a startup failure. The {@code runtime_start_failed_code} variant takes the raw
   * failure code as its single argument; keys without placeholders ignore any extra argument.
   */
  public static String startupFailureKey(String code) {
    if (code == null) {
      return "screen.agma_standalone.runtime_start_failed";
    }
    if (code.equals("RUNTIME_START_CANCELLED")) {
      return "screen.agma_standalone.runtime_start_cancelled";
    }
    return "screen.agma_standalone.runtime_start_failed_code";
  }
}
