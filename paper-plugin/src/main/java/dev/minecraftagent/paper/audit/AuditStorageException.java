package dev.minecraftagent.paper.audit;

import dev.minecraftagent.paper.startup.StartupFailure;
import java.util.Objects;

/** Stable runtime failure raised when a durable audit append cannot be trusted. */
public final class AuditStorageException extends IllegalStateException {
  private final StartupFailure.Code code;

  AuditStorageException(StartupFailure.Code code) {
    super(Objects.requireNonNull(code).name());
    this.code = code;
  }

  public StartupFailure.Code code() {
    return code;
  }
}
