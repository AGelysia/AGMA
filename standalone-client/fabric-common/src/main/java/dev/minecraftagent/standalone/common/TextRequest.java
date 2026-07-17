package dev.minecraftagent.standalone.common;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** Bounded text request with optional, explicit web and single-use inventory authorization. */
public record TextRequest(
    UUID requestId,
    UUID sessionId,
    String text,
    Duration timeout,
    LocalContext localContext,
    WebAuthorization webAuthorization,
    WebContext webContext,
    InventoryAuthorization inventoryAuthorization) {
  public static final int MAXIMUM_TEXT_LENGTH = 4096;
  private static final Pattern VERSION = Pattern.compile("^[0-9A-Za-z][0-9A-Za-z._+-]{0,63}$");
  private static final Pattern NAMESPACED_ID = Pattern.compile("^[a-z0-9_.-]+:[a-z0-9_./-]+$");
  private static final Pattern SYMBOLIC_ID = Pattern.compile("^[a-z0-9][a-z0-9._-]{0,63}$");
  private static final Pattern GENERATION = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$");

  public TextRequest {
    Objects.requireNonNull(requestId, "requestId");
    text = Objects.requireNonNull(text, "text").strip();
    Objects.requireNonNull(timeout, "timeout");
    if (text.isEmpty()
        || text.codePointCount(0, text.length()) > MAXIMUM_TEXT_LENGTH
        || containsInvalidControl(text)) {
      throw new IllegalArgumentException("Text request is invalid");
    }
    if (timeout.compareTo(Duration.ofSeconds(1)) < 0
        || timeout.compareTo(Duration.ofMinutes(5)) > 0) {
      throw new IllegalArgumentException("Text request timeout is out of bounds");
    }
    if (webContext != null && webAuthorization == null) {
      throw new IllegalArgumentException("Web context requires explicit authorization mode");
    }
    if (inventoryAuthorization != null
        && (localContext == null
            || !inventoryAuthorization.generationId().equals(localContext.catalogGenerationId()))) {
      throw new IllegalArgumentException(
          "Inventory authorization requires matching trusted local context");
    }
  }

  public TextRequest(UUID requestId, String text, Duration timeout) {
    this(requestId, null, text, timeout, null, null, null, null);
  }

  public TextRequest(UUID requestId, UUID sessionId, String text, Duration timeout) {
    this(requestId, sessionId, text, timeout, null, null, null, null);
  }

  Map<String, Object> applicationPayload() {
    var payload = new LinkedHashMap<String, Object>();
    payload.put("sessionId", sessionId == null ? null : sessionId.toString());
    payload.put("message", text);
    if (localContext != null) {
      payload.put("localContext", localContext.wire());
    }
    if (webAuthorization != null) {
      payload.put("webAuthorization", webAuthorization.wireValue);
    }
    if (webContext != null) {
      payload.put("webContext", webContext.wire());
    }
    if (inventoryAuthorization != null) {
      payload.put("inventoryAuthorization", inventoryAuthorization.wire());
    }
    return java.util.Collections.unmodifiableMap(payload);
  }

  private static boolean containsInvalidControl(String value) {
    return value
        .codePoints()
        .anyMatch(character -> Character.isISOControl(character) && character != '\n');
  }

  public enum WebAuthorization {
    OFF("off"),
    ONCE("once"),
    PERSISTENT("persistent");

    private final String wireValue;

    WebAuthorization(String wireValue) {
      this.wireValue = wireValue;
    }
  }

  /** Trusted client-local target context. It is never permission to send data to the web. */
  public record LocalContext(String minecraftVersion, String catalogGenerationId, Target target) {
    public LocalContext {
      if (minecraftVersion == null || !VERSION.matcher(minecraftVersion).matches()) {
        throw new IllegalArgumentException("Local Minecraft version context is invalid");
      }
      if (catalogGenerationId == null || !GENERATION.matcher(catalogGenerationId).matches()) {
        throw new IllegalArgumentException("Local catalog generation is invalid");
      }
      Objects.requireNonNull(target, "target");
      if (target.id() == null) {
        throw new IllegalArgumentException("Local target requires an exact resource id");
      }
    }

    Map<String, Object> wire() {
      return Map.of(
          "minecraftVersion", minecraftVersion,
          "catalogGenerationId", catalogGenerationId,
          "target", target.wire());
    }
  }

  public record WebContext(String minecraftVersion, Target target, ModPack modPack) {
    public WebContext {
      if (minecraftVersion == null || !VERSION.matcher(minecraftVersion).matches()) {
        throw new IllegalArgumentException("Minecraft version context is invalid");
      }
    }

    Map<String, Object> wire() {
      var value = new LinkedHashMap<String, Object>();
      value.put("minecraftVersion", minecraftVersion);
      if (target != null) {
        value.put("target", target.wire());
      }
      if (modPack != null) {
        value.put("modPack", modPack.wire());
      }
      return Map.copyOf(value);
    }
  }

  public record Target(String id, String displayName, String modId, String modVersion) {
    public Target {
      if (id == null && displayName == null && modId == null && modVersion == null) {
        throw new IllegalArgumentException("Request target is empty");
      }
      if (id != null && !NAMESPACED_ID.matcher(id).matches()) {
        throw new IllegalArgumentException("Request target id is invalid");
      }
      displayName = boundedOptional(displayName, 256, "Request target display name");
      if (modId != null && !SYMBOLIC_ID.matcher(modId).matches()) {
        throw new IllegalArgumentException("Request target mod id is invalid");
      }
      modVersion = boundedOptional(modVersion, 128, "Request target mod version");
    }

    Map<String, Object> wire() {
      var value = new LinkedHashMap<String, Object>();
      if (id != null) {
        value.put("id", id);
      }
      if (displayName != null) {
        value.put("displayName", displayName);
      }
      if (modId != null) {
        value.put("modId", modId);
      }
      if (modVersion != null) {
        value.put("modVersion", modVersion);
      }
      return Map.copyOf(value);
    }
  }

  public record ModPack(String name, String version) {
    public ModPack {
      name = bounded(name, 256, "Mod pack name");
      version = boundedOptional(version, 128, "Mod pack version");
    }

    Map<String, Object> wire() {
      var value = new LinkedHashMap<String, Object>();
      value.put("name", name);
      value.put("version", version);
      return java.util.Collections.unmodifiableMap(value);
    }
  }

  public record InventoryAuthorization(
      UUID authorizationId, String generationId, List<String> resourceIds) {
    public InventoryAuthorization {
      Objects.requireNonNull(authorizationId, "authorizationId");
      if (generationId == null || !GENERATION.matcher(generationId).matches()) {
        throw new IllegalArgumentException("Inventory generation is invalid");
      }
      Objects.requireNonNull(resourceIds, "resourceIds");
      var sorted = new ArrayList<>(resourceIds);
      sorted.sort(String::compareTo);
      if (sorted.isEmpty()
          || sorted.size() > 64
          || new HashSet<>(sorted).size() != sorted.size()
          || sorted.stream().anyMatch(id -> id == null || !NAMESPACED_ID.matcher(id).matches())) {
        throw new IllegalArgumentException("Inventory resource authorization is invalid");
      }
      resourceIds = List.copyOf(sorted);
    }

    Map<String, Object> wire() {
      return Map.of(
          "authorizationId",
          authorizationId.toString(),
          "generationId",
          generationId,
          "resourceIds",
          resourceIds);
    }
  }

  private static String bounded(String value, int maximum, String field) {
    Objects.requireNonNull(value, field);
    if (value.isEmpty()
        || value.codePointCount(0, value.length()) > maximum
        || containsInvalidControl(value)) {
      throw new IllegalArgumentException(field + " is invalid");
    }
    return value;
  }

  private static String boundedOptional(String value, int maximum, String field) {
    return value == null ? null : bounded(value, maximum, field);
  }
}
