package dev.minecraftagent.standalone.common;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.regex.Pattern;

/** Writes a bounded support snapshot with no secrets, identifiers, paths, or message content. */
public final class ClientDiagnosticExporter {
  private static final Pattern VERSION = Pattern.compile("^[0-9A-Za-z][0-9A-Za-z._+-]{0,63}$");
  private final Clock clock;

  public ClientDiagnosticExporter() {
    this(Clock.systemUTC());
  }

  ClientDiagnosticExporter(Clock clock) {
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  public Path write(Path root, ClientRuntimeView runtime, CatalogDiagnostic catalog) {
    Objects.requireNonNull(runtime, "runtime");
    Objects.requireNonNull(catalog, "catalog");
    try {
      var privateRoot = PrivateFilePermissions.prepareDirectory(root);
      var diagnostics = PrivateFilePermissions.prepareChildDirectory(privateRoot, "diagnostics");
      var target = diagnostics.resolve("latest.json");
      var profile = runtime.profile();
      var document = new LinkedHashMap<String, Object>();
      document.put("schemaVersion", 1);
      document.put("generatedAt", clock.instant().toString());
      document.put("minecraftVersion", catalog.minecraftVersion());
      document.put("runtimeState", profile.state().name());
      document.put("provider", profile.provider());
      document.put("model", profile.model());
      document.put("failureCode", runtime.startupFailureCode());
      var tools = new ArrayList<>(runtime.activeTools());
      tools.sort(String::compareTo);
      document.put("activeTools", tools);
      document.put("catalogState", catalog.state());
      document.put("catalogVisibility", catalog.visibility());
      document.put("catalogCompleteness", catalog.completeness());
      document.put("resourceCount", catalog.resourceCount());
      document.put("processCount", catalog.processCount());
      document.put("viewerSource", catalog.viewerSource());
      PrivateFilePermissions.atomicWrite(
          privateRoot,
          target,
          (StrictJson.write(document) + "\n").getBytes(StandardCharsets.UTF_8));
      return target;
    } catch (IOException | RuntimeException failure) {
      throw new ClientRuntimeException(
          "DIAGNOSTIC_EXPORT_FAILED", "Diagnostic export could not be written", failure);
    }
  }

  public record CatalogDiagnostic(
      String minecraftVersion,
      String state,
      String visibility,
      String completeness,
      int resourceCount,
      int processCount,
      String viewerSource) {
    public CatalogDiagnostic {
      if (minecraftVersion == null || !VERSION.matcher(minecraftVersion).matches()) {
        throw new IllegalArgumentException("Minecraft diagnostic version is invalid");
      }
      state = token(state, "catalog state");
      visibility = token(visibility, "catalog visibility");
      completeness = token(completeness, "catalog completeness");
      if (resourceCount < 0
          || resourceCount > 100_000
          || processCount < 0
          || processCount > 100_000) {
        throw new IllegalArgumentException("Catalog diagnostic count is invalid");
      }
      viewerSource = token(viewerSource, "viewer source");
    }

    private static String token(String value, String field) {
      Objects.requireNonNull(value, field);
      if (!value.matches("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")) {
        throw new IllegalArgumentException(field + " is invalid");
      }
      return value;
    }
  }
}
