package dev.minecraftagent.standalone.supervisor.install;

import java.util.Locale;
import java.util.Set;

final class InstallPathRules {
  private static final Set<String> ALLOWED_ROOTS =
      Set.of("app", "bin", "licenses", "standalone-client");
  private static final Set<String> WINDOWS_RESERVED =
      Set.of(
          "con", "prn", "aux", "nul", "com1", "com2", "com3", "com4", "com5", "com6", "com7",
          "com8", "com9", "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9");
  private static final Set<String> FORBIDDEN_ROOTS =
      Set.of("paper-plugin", "client-mod", "capability-packs", "deploy", "release");
  private static final String PLAN_NAME = "01-standalone-client-development-plan.md";
  private static final String STANDALONE_ENTRYPOINT = "app/dist/standalone/bootstrap/index.js";

  private InstallPathRules() {}

  static String manifestPath(String raw) throws ManagedRuntimeInstallException {
    if (!safeRelativePath(raw) || forbiddenPayload(raw)) {
      throw failure("MANIFEST_INVALID");
    }
    var root = raw.substring(0, raw.indexOf('/') < 0 ? raw.length() : raw.indexOf('/'));
    if (!ALLOWED_ROOTS.contains(root)) {
      throw failure("MANIFEST_INVALID");
    }
    return raw;
  }

  static String archivePath(String raw, boolean directory) throws ManagedRuntimeInstallException {
    if (raw == null || raw.isEmpty()) {
      throw failure("ARCHIVE_ENTRY_PATH_INVALID");
    }
    var path = directory && raw.endsWith("/") ? raw.substring(0, raw.length() - 1) : raw;
    if ((!directory && raw.endsWith("/")) || !safeRelativePath(path)) {
      throw failure("ARCHIVE_ENTRY_PATH_INVALID");
    }
    return path;
  }

  static String folded(String path) {
    return path.toLowerCase(Locale.ROOT);
  }

  private static boolean safeRelativePath(String path) {
    if (path == null
        || path.isEmpty()
        || path.length() > 240
        || path.startsWith("/")
        || path.endsWith("/")
        || path.indexOf('\\') >= 0) {
      return false;
    }
    for (var character : path.toCharArray()) {
      if (character < 0x20
          || character > 0x7e
          || character == '<'
          || character == '>'
          || character == ':'
          || character == '"'
          || character == '|'
          || character == '?'
          || character == '*') {
        return false;
      }
    }
    for (var segment : path.split("/", -1)) {
      if (segment.isEmpty()
          || segment.equals(".")
          || segment.equals("..")
          || segment.endsWith(".")
          || segment.endsWith(" ")) {
        return false;
      }
      var base =
          segment.substring(0, segment.indexOf('.') < 0 ? segment.length() : segment.indexOf('.'));
      if (WINDOWS_RESERVED.contains(base.toLowerCase(Locale.ROOT))) {
        return false;
      }
    }
    return true;
  }

  private static boolean forbiddenPayload(String path) {
    var folded = folded(path);
    var root = folded.substring(0, folded.indexOf('/') < 0 ? folded.length() : folded.indexOf('/'));
    return FORBIDDEN_ROOTS.contains(root)
        || folded.equals(PLAN_NAME)
        || folded.endsWith("/" + PLAN_NAME)
        || folded.startsWith("protocol/")
        || folded.startsWith("standalone-client/contracts/fixtures/")
        || (folded.startsWith("app/dist/") && !folded.equals(STANDALONE_ENTRYPOINT))
        || (!folded.startsWith("app/node_modules/")
            && (folded.endsWith(".yml") || folded.endsWith(".yaml")))
        || folded.equals(".env")
        || folded.endsWith("/.env");
  }

  private static ManagedRuntimeInstallException failure(String code) {
    return new ManagedRuntimeInstallException(code);
  }
}
