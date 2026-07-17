package dev.minecraftagent.paper.startup;

import java.util.Objects;

/** A startup failure whose diagnostic is safe to write to a server log. */
public final class StartupFailure extends Exception {
  public enum Code {
    PAPER_CONFIG_INVALID,
    PAPER_CONFIG_TOO_LARGE,
    PAPER_RESTART_REQUIRED,
    MANAGED_RUNTIME_SETUP_REQUIRED,
    RUNTIME_ENDPOINT_INVALID,
    SERVER_TOKEN_MISSING,
    SERVER_TOKEN_UNSAFE,
    JAVA_VERSION_UNSUPPORTED,
    PAPER_VERSION_UNSUPPORTED,
    STATE_DIRECTORY_UNAVAILABLE,
    STATE_DIRECTORY_UNSAFE,
    STATE_FILE_INVALID,
    STATE_FILE_UNSAFE,
    STATE_PERSISTENCE_FAILED,
    AUDIT_STORAGE_UNAVAILABLE,
    AUDIT_STORAGE_UNSAFE,
    CORE_POLICY_INVALID,
    CORE_TOOL_MISSING,
    CORE_TOOL_DUPLICATE,
    CORE_TOOL_UNSAFE,
    CORE_TOOL_INITIALIZATION_FAILED,
    LANDMARK_CATALOG_INVALID,
    LANDMARK_CATALOG_UNSAFE
  }

  public enum Stage {
    CONFIG,
    ENVIRONMENT,
    STATE,
    SECURITY_POLICY,
    CORE_TOOLS
  }

  private final Code code;
  private final Stage stage;

  public StartupFailure(Code code, Stage stage) {
    super("Paper startup check failed: " + Objects.requireNonNull(code).name());
    this.code = code;
    this.stage = Objects.requireNonNull(stage);
  }

  public Code code() {
    return code;
  }

  public Stage stage() {
    return stage;
  }
}
