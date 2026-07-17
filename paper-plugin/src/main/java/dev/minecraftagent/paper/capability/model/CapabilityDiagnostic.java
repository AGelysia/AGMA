package dev.minecraftagent.paper.capability.model;

import java.util.Objects;

/** Stable, value-free diagnostic suitable for logs and management surfaces. */
public record CapabilityDiagnostic(Code code, String field) {
  public CapabilityDiagnostic {
    Objects.requireNonNull(code);
    Objects.requireNonNull(field);
    if (!field.matches("[a-zA-Z0-9_.\\[\\]-]{1,160}")) {
      throw new IllegalArgumentException("Invalid diagnostic field");
    }
  }

  public static CapabilityDiagnostic of(Code code) {
    return new CapabilityDiagnostic(code, "manifest");
  }

  public enum Code {
    ROOT_MISSING,
    ROOT_UNSAFE,
    ROOT_CHANGED,
    ENTRY_LIMIT_EXCEEDED,
    FILE_COUNT_EXCEEDED,
    TOTAL_BYTES_EXCEEDED,
    DIRECTORY_DEPTH_EXCEEDED,
    PATH_UNSAFE,
    FILE_NOT_REGULAR,
    FILE_NOT_PRIVATE,
    FILE_HARD_LINKED,
    FILE_TOO_LARGE,
    UNSUPPORTED_FILE,
    IO_UNAVAILABLE,
    UTF8_INVALID,
    YAML_INVALID,
    MANIFEST_STRUCTURE_INVALID,
    MANIFEST_VALUE_INVALID,
    TEMPLATE_INVALID,
    POLICY_INCONSISTENT,
    DUPLICATE_ID,
    PLUGIN_INVENTORY_UNAVAILABLE,
    PLUGIN_INVENTORY_AMBIGUOUS,
    PLUGIN_MISSING,
    PLUGIN_UNAVAILABLE,
    PLUGIN_VERSION_INVALID,
    PLUGIN_VERSION_RANGE_INVALID,
    PLUGIN_VERSION_MISMATCH,
    CONSOLE_SOURCE_DISABLED,
    EXAMPLE_ONLY,
    DRAFT_ONLY,
    APPROVAL_REQUIRED,
    APPROVAL_SOURCE_UNAVAILABLE,
    REVERSAL_TARGET_MISSING,
    REVERSAL_TARGET_UNAVAILABLE,
    REVERSAL_TARGET_INCOMPATIBLE,
    REVERSAL_TARGET_SELF,
    REVERSAL_CYCLE,
    CONTENT_HASH_UNAVAILABLE
  }
}
