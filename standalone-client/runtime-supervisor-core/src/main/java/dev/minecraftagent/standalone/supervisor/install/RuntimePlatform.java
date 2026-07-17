package dev.minecraftagent.standalone.supervisor.install;

import java.util.Locale;
import java.util.Objects;

/** Supported standalone sidecar targets and their archive-relative Node executable. */
public enum RuntimePlatform {
  LINUX_X86_64("linux-x86_64", "bin/node"),
  WINDOWS_X86_64("windows-x86_64", "bin/node.exe");

  private final String id;
  private final String nodeExecutable;

  RuntimePlatform(String id, String nodeExecutable) {
    this.id = id;
    this.nodeExecutable = nodeExecutable;
  }

  public String id() {
    return id;
  }

  public String nodeExecutable() {
    return nodeExecutable;
  }

  public static RuntimePlatform current() {
    return detect(System.getProperty("os.name"), System.getProperty("os.arch"));
  }

  public static RuntimePlatform detect(String osName, String osArchitecture) {
    var os = normalize(osName, "osName");
    var architecture = normalize(osArchitecture, "osArchitecture");
    var x86_64 =
        architecture.equals("x86_64")
            || architecture.equals("x86-64")
            || architecture.equals("amd64")
            || architecture.equals("x64");
    if (!x86_64) {
      throw new IllegalArgumentException("UNSUPPORTED_RUNTIME_PLATFORM");
    }
    if (os.contains("linux")) {
      return LINUX_X86_64;
    }
    if (os.contains("windows")) {
      return WINDOWS_X86_64;
    }
    throw new IllegalArgumentException("UNSUPPORTED_RUNTIME_PLATFORM");
  }

  public static RuntimePlatform fromId(String id) {
    for (var platform : values()) {
      if (platform.id.equals(id)) {
        return platform;
      }
    }
    throw new IllegalArgumentException("UNSUPPORTED_RUNTIME_PLATFORM");
  }

  private static String normalize(String value, String field) {
    Objects.requireNonNull(value, field);
    if (value.isBlank() || value.codePoints().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException("UNSUPPORTED_RUNTIME_PLATFORM");
    }
    return value.toLowerCase(Locale.ROOT).trim();
  }
}
