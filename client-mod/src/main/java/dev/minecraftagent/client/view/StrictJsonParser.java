package dev.minecraftagent.client.view;

import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses one strict JSON document into a bounded {@link JsonNode} tree.
 *
 * <p>The view protocol is decoded without materializing an unbounded Gson object graph: document
 * bytes, depth, node count, string characters, object width, array width and number length are all
 * capped while reading, and duplicate field names are rejected.
 */
final class StrictJsonParser {
  static final int MAX_PAYLOAD_BYTES = 1024 * 1024;
  static final int MAX_JSON_DEPTH = 24;
  static final int MAX_JSON_NODES = 32768;
  static final int MAX_TOTAL_STRING_CHARS = MAX_PAYLOAD_BYTES;

  private static final int MAX_OBJECT_FIELDS = 32;
  private static final int MAX_ARRAY_ITEMS = 4096;
  private static final int MAX_NUMBER_CHARS = 64;

  private StrictJsonParser() {}

  static String decodeUtf8(byte[] payload) throws ViewDecodeException {
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(payload))
          .toString();
    } catch (CharacterCodingException exception) {
      throw new ViewDecodeException(ViewDecodeException.Code.INVALID_UTF8, exception);
    }
  }

  static JsonNode parse(String json) throws ViewDecodeException {
    ParseBudget budget = new ParseBudget();
    try (JsonReader reader = new JsonReader(new StringReader(json))) {
      reader.setStrictness(Strictness.STRICT);
      JsonNode node = readNode(reader, budget, 1);
      if (reader.peek() != JsonToken.END_DOCUMENT) {
        throw new ViewDecodeException(ViewDecodeException.Code.INVALID_JSON);
      }
      return node;
    } catch (ViewDecodeException exception) {
      throw exception;
    } catch (IOException | IllegalStateException | NumberFormatException exception) {
      throw new ViewDecodeException(ViewDecodeException.Code.INVALID_JSON, exception);
    }
  }

  private static JsonNode readNode(JsonReader reader, ParseBudget budget, int depth)
      throws IOException, ViewDecodeException {
    budget.addNode(depth);
    return switch (reader.peek()) {
      case BEGIN_OBJECT -> readObject(reader, budget, depth);
      case BEGIN_ARRAY -> readArray(reader, budget, depth);
      case STRING -> {
        String value = reader.nextString();
        budget.addString(value);
        yield new JsonString(value);
      }
      case NUMBER -> {
        String value = reader.nextString();
        if (value.length() > MAX_NUMBER_CHARS) {
          throw new ViewDecodeException(ViewDecodeException.Code.JSON_LIMIT_EXCEEDED);
        }
        yield new JsonNumber(value);
      }
      case BOOLEAN -> new JsonBoolean(reader.nextBoolean());
      case NULL -> {
        reader.nextNull();
        yield JsonNull.INSTANCE;
      }
      default -> throw new ViewDecodeException(ViewDecodeException.Code.INVALID_JSON);
    };
  }

  private static JsonObject readObject(JsonReader reader, ParseBudget budget, int depth)
      throws IOException, ViewDecodeException {
    reader.beginObject();
    Map<String, JsonNode> fields = new LinkedHashMap<>();
    while (reader.hasNext()) {
      if (fields.size() >= MAX_OBJECT_FIELDS) {
        throw new ViewDecodeException(ViewDecodeException.Code.JSON_LIMIT_EXCEEDED);
      }
      String name = reader.nextName();
      budget.addString(name);
      if (fields.containsKey(name)) {
        throw new ViewDecodeException(ViewDecodeException.Code.DUPLICATE_FIELD);
      }
      fields.put(name, readNode(reader, budget, depth + 1));
    }
    reader.endObject();
    return new JsonObject(Map.copyOf(fields));
  }

  private static JsonArray readArray(JsonReader reader, ParseBudget budget, int depth)
      throws IOException, ViewDecodeException {
    reader.beginArray();
    List<JsonNode> values = new ArrayList<>();
    while (reader.hasNext()) {
      if (values.size() >= MAX_ARRAY_ITEMS) {
        throw new ViewDecodeException(ViewDecodeException.Code.JSON_LIMIT_EXCEEDED);
      }
      values.add(readNode(reader, budget, depth + 1));
    }
    reader.endArray();
    return new JsonArray(List.copyOf(values));
  }

  private static final class ParseBudget {
    private int nodes;
    private int stringChars;

    private void addNode(int depth) throws ViewDecodeException {
      if (depth > MAX_JSON_DEPTH || ++nodes > MAX_JSON_NODES) {
        throw new ViewDecodeException(ViewDecodeException.Code.JSON_LIMIT_EXCEEDED);
      }
    }

    private void addString(String value) throws ViewDecodeException {
      if ((long) stringChars + value.length() > MAX_TOTAL_STRING_CHARS) {
        throw new ViewDecodeException(ViewDecodeException.Code.JSON_LIMIT_EXCEEDED);
      }
      stringChars += value.length();
    }
  }
}
