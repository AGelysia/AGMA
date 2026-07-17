package dev.minecraftagent.paper.client;

import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/** Immutable server-validated structured view metadata and content. */
public final class ClientStructuredView {
  private final String viewSchemaVersion;
  private final UUID viewId;
  private final UUID requestId;
  private final ClientViewType viewType;
  private final int revision;
  private final String title;
  private final String fallbackText;
  private final boolean pinnable;
  private final JsonObject content;

  public ClientStructuredView(
      String viewSchemaVersion,
      UUID viewId,
      UUID requestId,
      ClientViewType viewType,
      int revision,
      String title,
      String fallbackText,
      boolean pinnable,
      JsonObject content) {
    if (!ClientViewSchemaRegistry.VIEW_SCHEMA_V1.equals(viewSchemaVersion)) {
      throw new ClientProtocolException("CLIENT_VIEW_SCHEMA_UNSUPPORTED");
    }
    this.viewSchemaVersion = viewSchemaVersion;
    this.viewId = Objects.requireNonNull(viewId);
    this.requestId = Objects.requireNonNull(requestId);
    this.viewType = Objects.requireNonNull(viewType);
    if (revision < 1) {
      throw new ClientProtocolException("CLIENT_VIEW_REVISION_INVALID");
    }
    this.revision = revision;
    this.title = requireText(title, 128, false, "CLIENT_VIEW_TITLE_INVALID");
    this.fallbackText = requireText(fallbackText, 8192, true, "CLIENT_VIEW_FALLBACK_INVALID");
    this.pinnable = pinnable;
    this.content = Objects.requireNonNull(content).deepCopy();
    if (this.content.toString().getBytes(StandardCharsets.UTF_8).length > 1024 * 1024) {
      throw new ClientProtocolException("CLIENT_VIEW_TOO_LARGE");
    }
    ClientStructuredViewValidator.validate(viewType, this.content);
    if (viewType == ClientViewType.BUILD_PREVIEW
        && (!viewId.toString().equals(this.content.get("previewId").getAsString())
            || revision != this.content.get("revision").getAsInt())) {
      throw new ClientProtocolException("CLIENT_VIEW_CONTENT_INVALID");
    }
  }

  public String viewSchemaVersion() {
    return viewSchemaVersion;
  }

  public UUID viewId() {
    return viewId;
  }

  public UUID requestId() {
    return requestId;
  }

  public ClientViewType viewType() {
    return viewType;
  }

  public int revision() {
    return revision;
  }

  public String title() {
    return title;
  }

  public String fallbackText() {
    return fallbackText;
  }

  public boolean pinnable() {
    return pinnable;
  }

  public JsonObject content() {
    return content.deepCopy();
  }

  public JsonObject toJson() {
    var result = new JsonObject();
    result.addProperty("viewSchemaVersion", viewSchemaVersion);
    result.addProperty("viewId", viewId.toString());
    result.addProperty("requestId", requestId.toString());
    result.addProperty("viewType", viewType.wireName());
    result.addProperty("revision", revision);
    result.addProperty("title", title);
    result.addProperty("fallbackText", fallbackText);
    result.addProperty("pinnable", pinnable);
    result.add("content", content.deepCopy());
    return result;
  }

  public byte[] toJsonBytes() {
    return toJson().toString().getBytes(StandardCharsets.UTF_8);
  }

  /** Strictly decodes one closed {@code structured-view.schema.json} object. */
  public static ClientStructuredView fromJson(JsonObject source) {
    return ClientStructuredViewValidator.fromJson(source);
  }

  private static String requireText(
      String value, int maximum, boolean allowLineFormatting, String code) {
    if (value == null
        || value.isBlank()
        || value.codePointCount(0, value.length()) > maximum
        || value
            .codePoints()
            .anyMatch(codePoint -> unsafeVisibleCodePoint(codePoint, allowLineFormatting))
        || !wellFormed(value)) {
      throw new ClientProtocolException(code);
    }
    return value;
  }

  private static boolean wellFormed(String value) {
    for (var index = 0; index < value.length(); index++) {
      var character = value.charAt(index);
      if (Character.isHighSurrogate(character)) {
        if (++index >= value.length() || !Character.isLowSurrogate(value.charAt(index))) {
          return false;
        }
      } else if (Character.isLowSurrogate(character)) {
        return false;
      }
    }
    return true;
  }

  private static boolean unsafeVisibleCodePoint(int value, boolean allowLineFormatting) {
    if (allowLineFormatting && (value == '\n' || value == '\t')) {
      return false;
    }
    return value <= 0x1f
        || value >= 0x7f && value <= 0x9f
        || value == 0x061c
        || value == 0x200e
        || value == 0x200f
        || value >= 0x202a && value <= 0x202e
        || value >= 0x2066 && value <= 0x2069;
  }
}
