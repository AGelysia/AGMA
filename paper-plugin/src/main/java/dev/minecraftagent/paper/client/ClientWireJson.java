package dev.minecraftagent.paper.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.minecraftagent.protocol.WireJson;
import java.math.BigDecimal;
import java.util.Map;

/**
 * Bridges the Gson trees the Paper plugin keeps for its view models onto the shared wire model the
 * channel contract is defined over.
 *
 * <p>The plugin still parses and renders structured views as Gson because its runtime services and
 * tests consume {@link JsonObject}; only the validation and the framing grammar moved to the shared
 * module, so this adapter is the seam between the two.
 */
final class ClientWireJson {
  private ClientWireJson() {}

  /** Converts one Gson element into the shared wire model. */
  static WireJson fromGson(JsonElement element) {
    if (element == null || element.isJsonNull()) {
      return WireJson.NullNode.INSTANCE;
    }
    if (element.isJsonPrimitive()) {
      var primitive = element.getAsJsonPrimitive();
      if (primitive.isBoolean()) {
        return new WireJson.BooleanNode(primitive.getAsBoolean());
      }
      if (primitive.isNumber()) {
        return new WireJson.NumberNode(primitive.getAsNumber().toString());
      }
      return new WireJson.TextNode(primitive.getAsString());
    }
    if (element.isJsonArray()) {
      var array = new WireJson.ArrayNode();
      for (JsonElement item : element.getAsJsonArray()) {
        array.add(fromGson(item));
      }
      return array;
    }
    var object = new WireJson.ObjectNode();
    for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
      object.put(entry.getKey(), fromGson(entry.getValue()));
    }
    return object;
  }

  /** Converts one shared wire value back into a Gson element. */
  static JsonElement toGson(WireJson value) {
    if (value instanceof WireJson.ObjectNode object) {
      var result = new JsonObject();
      for (Map.Entry<String, WireJson> entry : object.fields().entrySet()) {
        result.add(entry.getKey(), toGson(entry.getValue()));
      }
      return result;
    }
    if (value instanceof WireJson.ArrayNode array) {
      var result = new JsonArray(array.size());
      for (WireJson item : array) {
        result.add(toGson(item));
      }
      return result;
    }
    if (value instanceof WireJson.TextNode text) {
      return new JsonPrimitive(text.value());
    }
    if (value instanceof WireJson.NumberNode number) {
      return new JsonPrimitive(new BigDecimal(number.literal()));
    }
    if (value instanceof WireJson.BooleanNode bool) {
      return new JsonPrimitive(bool.value());
    }
    return JsonNull.INSTANCE;
  }
}
