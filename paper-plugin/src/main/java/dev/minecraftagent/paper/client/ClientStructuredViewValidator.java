package dev.minecraftagent.paper.client;

import com.google.gson.JsonObject;
import dev.minecraftagent.protocol.ProtocolViolationException;
import dev.minecraftagent.protocol.StructuredViewValidator;
import dev.minecraftagent.protocol.WireJson;

/**
 * Thin server-side wrapper over the shared structured-view validation.
 *
 * <p>Structure, bounds and cross-field rules are the shared module's single implementation. What
 * stays here is server policy: the stable codes this server reports and the {@link ClientViewType}
 * enum that decides which view types may be published at all.
 */
final class ClientStructuredViewValidator {
  private static final StructuredViewValidator VALIDATOR =
      new StructuredViewValidator(
          "CLIENT_VIEW_CONTENT_INVALID",
          "CLIENT_VIEW_SCHEMA_UNSUPPORTED",
          "CLIENT_VIEW_TYPE_UNKNOWN");

  private ClientStructuredViewValidator() {}

  /** Strictly decodes one closed {@code structured-view.schema.json} object. */
  static ClientStructuredView fromJson(JsonObject source) {
    StructuredViewValidator.ValidatedView validated;
    try {
      validated = VALIDATOR.validate((WireJson.ObjectNode) ClientWireJson.fromGson(source));
    } catch (ProtocolViolationException failure) {
      throw new ClientProtocolException(failure.code());
    }
    return new ClientStructuredView(
        validated.viewSchemaVersion(),
        validated.viewId(),
        validated.requestId(),
        ClientViewType.fromWireName(validated.viewType()),
        validated.revision(),
        validated.title(),
        validated.fallbackText(),
        validated.pinnable(),
        (JsonObject) ClientWireJson.toGson(validated.content()));
  }

  /** Validates the content object of a view whose metadata was already accepted. */
  static void validate(ClientViewType type, JsonObject content) {
    try {
      VALIDATOR.validateContent(
          type.wireName(), (WireJson.ObjectNode) ClientWireJson.fromGson(content));
    } catch (ProtocolViolationException failure) {
      throw new ClientProtocolException(failure.code());
    }
  }
}
