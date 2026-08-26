package dev.minecraftagent.protocol;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Immutable JSON value tree for the client channel.
 *
 * <p>Both ends of the {@code minecraftagent:client} channel parse and serialize payloads through
 * this model so that the framing grammar, the duplicate-key rejection and the parse budgets cannot
 * drift between the Paper plugin and the Fabric client mod.
 */
public sealed interface WireJson {
  /**
   * Serializes this value as compact JSON, matching the escaping used by the hand-rolled writers.
   */
  String toJsonText();

  /** A JSON object that remembers insertion order, mirroring how both ends emit payloads. */
  final class ObjectNode implements WireJson {
    private final LinkedHashMap<String, WireJson> fields = new LinkedHashMap<>();

    public ObjectNode put(String name, WireJson value) {
      fields.put(name, value);
      return this;
    }

    public ObjectNode putString(String name, String value) {
      return put(name, new TextNode(value));
    }

    public ObjectNode putNumber(String name, String literal) {
      return put(name, new NumberNode(literal));
    }

    public ObjectNode putLong(String name, long value) {
      return putNumber(name, Long.toString(value));
    }

    public ObjectNode putBoolean(String name, boolean value) {
      return put(name, new BooleanNode(value));
    }

    public ObjectNode putNull(String name) {
      return put(name, NullNode.INSTANCE);
    }

    public WireJson get(String name) {
      return fields.get(name);
    }

    public boolean has(String name) {
      return fields.containsKey(name);
    }

    public int size() {
      return fields.size();
    }

    public Set<String> names() {
      return Collections.unmodifiableSet(fields.keySet());
    }

    public Map<String, WireJson> fields() {
      return Collections.unmodifiableMap(fields);
    }

    public ObjectNode deepCopy() {
      var copy = new ObjectNode();
      fields.forEach((name, value) -> copy.put(name, WireJson.deepCopy(value)));
      return copy;
    }

    @Override
    public String toJsonText() {
      return WireJsonWriter.write(this);
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof ObjectNode node && node.fields.equals(fields);
    }

    @Override
    public int hashCode() {
      return fields.hashCode();
    }

    @Override
    public String toString() {
      return toJsonText();
    }
  }

  /** A JSON array. */
  final class ArrayNode implements WireJson, Iterable<WireJson> {
    private final List<WireJson> items = new ArrayList<>();

    public ArrayNode add(WireJson value) {
      items.add(value);
      return this;
    }

    public int size() {
      return items.size();
    }

    public WireJson get(int index) {
      return items.get(index);
    }

    public List<WireJson> items() {
      return Collections.unmodifiableList(items);
    }

    @Override
    public java.util.Iterator<WireJson> iterator() {
      return items().iterator();
    }

    @Override
    public String toJsonText() {
      return WireJsonWriter.write(this);
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof ArrayNode node && node.items.equals(items);
    }

    @Override
    public int hashCode() {
      return items.hashCode();
    }

    @Override
    public String toString() {
      return toJsonText();
    }
  }

  /** A JSON string. */
  record TextNode(String value) implements WireJson {
    @Override
    public String toJsonText() {
      return WireJsonWriter.write(this);
    }
  }

  /**
   * A JSON number kept as its exact literal so neither end rounds before validating a bound.
   *
   * @param literal the number exactly as it appeared on the wire
   */
  record NumberNode(String literal) implements WireJson {
    @Override
    public String toJsonText() {
      return literal;
    }
  }

  /** A JSON boolean. */
  record BooleanNode(boolean value) implements WireJson {
    @Override
    public String toJsonText() {
      return Boolean.toString(value);
    }
  }

  /** The JSON {@code null} literal. */
  enum NullNode implements WireJson {
    INSTANCE;

    @Override
    public String toJsonText() {
      return "null";
    }
  }

  static WireJson deepCopy(WireJson value) {
    if (value instanceof ObjectNode object) {
      return object.deepCopy();
    }
    if (value instanceof ArrayNode array) {
      var copy = new ArrayNode();
      array.items().forEach(item -> copy.add(deepCopy(item)));
      return copy;
    }
    return value;
  }
}
