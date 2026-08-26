package dev.minecraftagent.client.view;

import java.util.List;
import java.util.Map;

/**
 * Bounded JSON value tree produced by {@link StrictJsonParser}.
 *
 * <p>The nodes carry no validation state: every limit is enforced while the document is read, so
 * decoding units can pattern match on the tree without re-checking sizes.
 */
sealed interface JsonNode
    permits JsonObject, JsonArray, JsonString, JsonNumber, JsonBoolean, JsonNull {}

record JsonObject(Map<String, JsonNode> fields) implements JsonNode {}

record JsonArray(List<JsonNode> values) implements JsonNode {}

record JsonString(String value) implements JsonNode {}

record JsonNumber(String value) implements JsonNode {}

record JsonBoolean(boolean value) implements JsonNode {}

enum JsonNull implements JsonNode {
  INSTANCE
}
