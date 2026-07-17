package dev.minecraftagent.paper.transport;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.util.HashSet;

public final class StrictJson {
  private static final int MAX_DEPTH = 16;
  private static final int MAX_VALUES = 512;

  private StrictJson() {}

  static JsonObject parseObject(String text) {
    return parseObject(text, "HANDSHAKE_MESSAGE_INVALID", "authentication");
  }

  public static JsonObject parseObject(String text, String failureCode, String failureStage) {
    return parseObject(text, failureCode, failureStage, MAX_DEPTH, MAX_VALUES);
  }

  public static JsonObject parseObject(
      String text, String failureCode, String failureStage, int maximumDepth, int maximumValues) {
    if (maximumDepth < 1 || maximumDepth > 64 || maximumValues < 1 || maximumValues > 1_000_000) {
      throw new IllegalArgumentException("Strict JSON limits are invalid");
    }
    try {
      var reader = new JsonReader(new StringReader(text));
      reader.setStrictness(Strictness.STRICT);
      var budget = new Budget(maximumDepth, maximumValues);
      var element = read(reader, 0, budget, failureCode, failureStage);
      if (reader.peek() != JsonToken.END_DOCUMENT || !element.isJsonObject()) {
        throw invalid(failureCode, failureStage);
      }
      return element.getAsJsonObject();
    } catch (IOException | IllegalStateException | NumberFormatException error) {
      throw invalid(failureCode, failureStage);
    }
  }

  private static JsonElement read(
      JsonReader reader, int depth, Budget budget, String failureCode, String failureStage)
      throws IOException {
    if (depth > budget.maximumDepth || ++budget.values > budget.maximumValues) {
      throw invalid(failureCode, failureStage);
    }
    return switch (reader.peek()) {
      case BEGIN_OBJECT -> readObject(reader, depth, budget, failureCode, failureStage);
      case BEGIN_ARRAY -> readArray(reader, depth, budget, failureCode, failureStage);
      case STRING -> new JsonPrimitive(reader.nextString());
      case NUMBER -> new JsonPrimitive(new BigDecimal(reader.nextString()));
      case BOOLEAN -> new JsonPrimitive(reader.nextBoolean());
      case NULL -> {
        reader.nextNull();
        yield JsonNull.INSTANCE;
      }
      default -> throw invalid(failureCode, failureStage);
    };
  }

  private static JsonObject readObject(
      JsonReader reader, int depth, Budget budget, String failureCode, String failureStage)
      throws IOException {
    reader.beginObject();
    var object = new JsonObject();
    var names = new HashSet<String>();
    while (reader.hasNext()) {
      var name = reader.nextName();
      if (!names.add(name)) {
        throw invalid(failureCode, failureStage);
      }
      object.add(name, read(reader, depth + 1, budget, failureCode, failureStage));
    }
    reader.endObject();
    return object;
  }

  private static JsonArray readArray(
      JsonReader reader, int depth, Budget budget, String failureCode, String failureStage)
      throws IOException {
    reader.beginArray();
    var array = new JsonArray();
    while (reader.hasNext()) {
      array.add(read(reader, depth + 1, budget, failureCode, failureStage));
    }
    reader.endArray();
    return array;
  }

  private static RuntimeConnectionFailure invalid(String code, String stage) {
    return new RuntimeConnectionFailure(code, stage);
  }

  private static final class Budget {
    private final int maximumDepth;
    private final int maximumValues;
    private int values;

    private Budget(int maximumDepth, int maximumValues) {
      this.maximumDepth = maximumDepth;
      this.maximumValues = maximumValues;
    }
  }
}
