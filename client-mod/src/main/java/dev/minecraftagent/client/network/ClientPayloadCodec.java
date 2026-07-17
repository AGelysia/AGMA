package dev.minecraftagent.client.network;

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
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Strict raw-UTF-8 codec for the client side of the single custom payload channel. */
public final class ClientPayloadCodec {
  public static final String PAYLOAD_VERSION = "1.0";
  public static final String HELLO_PROTOCOL_VERSION = "1.1";
  public static final int MAX_INBOUND_BYTES = AgentClientPayload.MAX_FRAME_BYTES;
  public static final int MAX_OUTBOUND_BYTES = 16 * 1024;

  private static final int MAX_DEPTH = 12;
  private static final int MAX_NODES = 1024;
  private static final int MAX_STRING_CHARS = 64 * 1024;
  private static final int MAX_OBJECT_FIELDS = 32;
  private static final int MAX_ARRAY_ITEMS = 128;
  private static final Pattern SHA256 = Pattern.compile("[a-f0-9]{64}");
  private static final Pattern STABLE_CODE = Pattern.compile("[A-Z][A-Z0-9_]{1,63}");
  private static final Set<String> ENVELOPE_FIELDS =
      Set.of("clientPayloadVersion", "messageId", "type", "payload");

  public ClientServerMessage decodeServer(byte[] bytes) {
    JsonObject envelope = parse(bytes);
    requireFields(envelope, ENVELOPE_FIELDS);
    if (!PAYLOAD_VERSION.equals(string(envelope, "clientPayloadVersion", 3))) {
      throw failure("CLIENT_PROTOCOL_INCOMPATIBLE");
    }
    UUID messageId = uuid(envelope, "messageId", false);
    String type = string(envelope, "type", 32);
    JsonObject payload = object(envelope, "payload");
    return switch (type) {
      case "server.hello" -> decodeServerHello(messageId, payload);
      case "view.begin" -> decodeViewBegin(messageId, payload);
      case "view.chunk" -> decodeViewChunk(messageId, payload);
      case "view.clear" -> decodeViewClear(messageId, payload);
      case "ui.control" -> decodeUiControl(messageId, payload);
      case "client.hello", "client.ack", "client.error" ->
          throw failure("CLIENT_MESSAGE_DIRECTION_INVALID");
      default -> throw failure("CLIENT_MESSAGE_TYPE_INVALID");
    };
  }

  public byte[] encodeHello(UUID messageId, ClientHandshakeAdvertisement advertisement) {
    Objects.requireNonNull(advertisement);
    JsonObject capabilities = new JsonObject();
    capabilities.addProperty("overlay", advertisement.overlay());
    capabilities.addProperty("itemIcons", advertisement.itemIcons());
    capabilities.addProperty("recipeView", advertisement.recipeView());
    capabilities.addProperty("litematicaPreview", advertisement.litematicaPreview());
    capabilities.addProperty("litematicaMaterialList", advertisement.litematicaMaterialList());

    JsonObject dependencies = new JsonObject();
    addNullable(dependencies, "litematica", advertisement.litematicaVersion().orElse(null));
    addNullable(dependencies, "malilib", advertisement.malilibVersion().orElse(null));

    var diagnostic = advertisement.litematicaAdapterDiagnostic();
    JsonObject litematicaAdapter = new JsonObject();
    litematicaAdapter.addProperty("status", diagnostic.status().name());
    litematicaAdapter.addProperty("minecraftVersion", diagnostic.minecraftVersion());
    litematicaAdapter.addProperty("fabricLoaderVersion", diagnostic.fabricLoaderVersion());
    addNullable(
        litematicaAdapter, "litematicaVersion", diagnostic.litematicaVersion().orElse(null));
    addNullable(litematicaAdapter, "malilibVersion", diagnostic.malilibVersion().orElse(null));
    addNullable(litematicaAdapter, "adapterId", diagnostic.adapterId().orElse(null));
    JsonObject diagnostics = new JsonObject();
    diagnostics.add("litematicaAdapter", litematicaAdapter);

    JsonObject payload = new JsonObject();
    payload.addProperty("clientProtocolVersion", HELLO_PROTOCOL_VERSION);
    payload.addProperty("modVersion", advertisement.modVersion());
    payload.add("capabilities", capabilities);
    payload.add("dependencies", dependencies);
    payload.add("diagnostics", diagnostics);
    return encode(messageId, "client.hello", payload);
  }

  public byte[] encodeAck(
      UUID messageId, long generation, UUID transferId, boolean displayed, String code) {
    requireGeneration(generation);
    requireCode(code);
    JsonObject payload = new JsonObject();
    payload.addProperty("generation", generation);
    payload.addProperty("transferId", Objects.requireNonNull(transferId).toString());
    payload.addProperty("status", displayed ? "DISPLAYED" : "REJECTED");
    payload.addProperty("code", code);
    return encode(messageId, "client.ack", payload);
  }

  public byte[] encodeError(UUID messageId, long generation, UUID transferId, String code) {
    requireGeneration(generation);
    requireCode(code);
    JsonObject payload = new JsonObject();
    payload.addProperty("generation", generation);
    addNullable(payload, "transferId", transferId == null ? null : transferId.toString());
    payload.addProperty("code", code);
    return encode(messageId, "client.error", payload);
  }

  private static ClientServerMessage decodeServerHello(UUID messageId, JsonObject payload) {
    requireFields(payload, Set.of("generation", "accepted", "viewSchemaVersion"));
    long generation = integer(payload, "generation", 1, Integer.MAX_VALUE);
    boolean accepted = bool(payload, "accepted");
    String viewSchemaVersion = nullableString(payload, "viewSchemaVersion", 3);
    if ((accepted && !"1.0".equals(viewSchemaVersion))
        || (!accepted && viewSchemaVersion != null)) {
      throw failure("CLIENT_SERVER_HELLO_INVALID");
    }
    return new ClientServerMessage.ServerHello(messageId, generation, accepted, viewSchemaVersion);
  }

  private static ClientServerMessage decodeViewBegin(UUID messageId, JsonObject payload) {
    requireFields(
        payload,
        Set.of(
            "generation",
            "transferId",
            "viewId",
            "requestId",
            "revision",
            "mode",
            "encoding",
            "compressedBytes",
            "uncompressedBytes",
            "chunkCount",
            "contentSha256"));
    long generation = integer(payload, "generation", 1, Integer.MAX_VALUE);
    int compressedBytes = integer(payload, "compressedBytes", 1, 1024 * 1024);
    int uncompressedBytes = integer(payload, "uncompressedBytes", 1, 1024 * 1024);
    int chunkCount = integer(payload, "chunkCount", 1, 64);
    int expectedChunkCount = (compressedBytes + 24 * 1024 - 1) / (24 * 1024);
    if (chunkCount != expectedChunkCount) {
      throw failure("CLIENT_TRANSFER_DESCRIPTOR_INVALID");
    }
    ClientServerMessage.Encoding encoding =
        switch (string(payload, "encoding", 8)) {
          case "identity" -> ClientServerMessage.Encoding.IDENTITY;
          case "gzip" -> ClientServerMessage.Encoding.GZIP;
          default -> throw failure("CLIENT_TRANSFER_ENCODING_INVALID");
        };
    if (encoding == ClientServerMessage.Encoding.IDENTITY && compressedBytes != uncompressedBytes) {
      throw failure("CLIENT_TRANSFER_DESCRIPTOR_INVALID");
    }
    ClientServerMessage.Mode mode =
        switch (string(payload, "mode", 8)) {
          case "show" -> ClientServerMessage.Mode.SHOW;
          case "update" -> ClientServerMessage.Mode.UPDATE;
          default -> throw failure("CLIENT_VIEW_MODE_INVALID");
        };
    return new ClientServerMessage.ViewBegin(
        messageId,
        generation,
        uuid(payload, "transferId", false),
        uuid(payload, "viewId", false),
        uuid(payload, "requestId", false),
        integer(payload, "revision", 1, Integer.MAX_VALUE),
        mode,
        encoding,
        compressedBytes,
        uncompressedBytes,
        chunkCount,
        hash(payload, "contentSha256"));
  }

  private static ClientServerMessage decodeViewChunk(UUID messageId, JsonObject payload) {
    requireFields(
        payload, Set.of("generation", "transferId", "index", "byteLength", "sha256", "data"));
    int byteLength = integer(payload, "byteLength", 1, 24 * 1024);
    String encoded = string(payload, "data", 32768);
    byte[] data;
    try {
      data = Base64.getDecoder().decode(encoded);
    } catch (IllegalArgumentException error) {
      throw failure("CLIENT_CHUNK_BASE64_INVALID");
    }
    if (data.length != byteLength || !Base64.getEncoder().encodeToString(data).equals(encoded)) {
      throw failure("CLIENT_CHUNK_BASE64_INVALID");
    }
    String hash = hash(payload, "sha256");
    if (!hash.equals(sha256(data))) {
      throw failure("CLIENT_CHUNK_HASH_MISMATCH");
    }
    return new ClientServerMessage.ViewChunk(
        messageId,
        integer(payload, "generation", 1, Integer.MAX_VALUE),
        uuid(payload, "transferId", false),
        integer(payload, "index", 0, 63),
        byteLength,
        hash,
        data);
  }

  private static ClientServerMessage decodeViewClear(UUID messageId, JsonObject payload) {
    requireFields(payload, Set.of("generation", "viewId"));
    return new ClientServerMessage.ViewClear(
        messageId,
        integer(payload, "generation", 1, Integer.MAX_VALUE),
        uuid(payload, "viewId", true));
  }

  private static ClientServerMessage decodeUiControl(UUID messageId, JsonObject payload) {
    requireFields(payload, Set.of("generation", "action", "viewId"));
    ClientServerMessage.Action action =
        switch (string(payload, "action", 64)) {
          case "pin" -> ClientServerMessage.Action.PIN;
          case "unpin" -> ClientServerMessage.Action.UNPIN;
          case "clear" -> ClientServerMessage.Action.CLEAR;
          case "litematica.preview.load" -> ClientServerMessage.Action.LITEMATICA_PREVIEW_LOAD;
          case "litematica.preview.remove" -> ClientServerMessage.Action.LITEMATICA_PREVIEW_REMOVE;
          case "litematica.material_list.open" ->
              ClientServerMessage.Action.LITEMATICA_MATERIAL_LIST_OPEN;
          default -> throw failure("CLIENT_UI_ACTION_INVALID");
        };
    return new ClientServerMessage.UiControl(
        messageId,
        integer(payload, "generation", 1, Integer.MAX_VALUE),
        action,
        uuid(payload, "viewId", true));
  }

  private static byte[] encode(UUID messageId, String type, JsonObject payload) {
    JsonObject envelope = new JsonObject();
    envelope.addProperty("clientPayloadVersion", PAYLOAD_VERSION);
    envelope.addProperty("messageId", Objects.requireNonNull(messageId).toString());
    envelope.addProperty("type", type);
    envelope.add("payload", payload);
    byte[] bytes = envelope.toString().getBytes(StandardCharsets.UTF_8);
    if (bytes.length < 1 || bytes.length > MAX_OUTBOUND_BYTES) {
      throw failure("CLIENT_OUTBOUND_FRAME_TOO_LARGE");
    }
    return bytes;
  }

  private static JsonObject parse(byte[] bytes) {
    Objects.requireNonNull(bytes);
    if (bytes.length < 1 || bytes.length > MAX_INBOUND_BYTES) {
      throw failure("CLIENT_FRAME_TOO_LARGE");
    }
    String source;
    try {
      source =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(bytes))
              .toString();
    } catch (CharacterCodingException error) {
      throw failure("CLIENT_FRAME_UTF8_INVALID");
    }

    ParseBudget budget = new ParseBudget();
    try (JsonReader reader = new JsonReader(new StringReader(source))) {
      reader.setStrictness(Strictness.STRICT);
      JsonElement value = readElement(reader, budget, 1);
      if (reader.peek() != JsonToken.END_DOCUMENT || !value.isJsonObject()) {
        throw failure("CLIENT_FRAME_JSON_INVALID");
      }
      return value.getAsJsonObject();
    } catch (ClientPayloadException error) {
      throw error;
    } catch (IOException | IllegalStateException | NumberFormatException error) {
      throw failure("CLIENT_FRAME_JSON_INVALID");
    }
  }

  private static JsonElement readElement(JsonReader reader, ParseBudget budget, int depth)
      throws IOException {
    budget.node(depth);
    return switch (reader.peek()) {
      case BEGIN_OBJECT -> readObject(reader, budget, depth);
      case BEGIN_ARRAY -> readArray(reader, budget, depth);
      case STRING -> {
        String value = reader.nextString();
        budget.string(value);
        yield new JsonPrimitive(value);
      }
      case NUMBER -> new JsonPrimitive(new BigDecimal(reader.nextString()));
      case BOOLEAN -> new JsonPrimitive(reader.nextBoolean());
      case NULL -> {
        reader.nextNull();
        yield JsonNull.INSTANCE;
      }
      default -> throw failure("CLIENT_FRAME_JSON_INVALID");
    };
  }

  private static JsonObject readObject(JsonReader reader, ParseBudget budget, int depth)
      throws IOException {
    JsonObject object = new JsonObject();
    Set<String> names = new HashSet<>();
    reader.beginObject();
    while (reader.hasNext()) {
      if (names.size() >= MAX_OBJECT_FIELDS) {
        throw failure("CLIENT_FRAME_JSON_LIMIT");
      }
      String name = reader.nextName();
      budget.string(name);
      if (!names.add(name)) {
        throw failure("CLIENT_FRAME_DUPLICATE_FIELD");
      }
      object.add(name, readElement(reader, budget, depth + 1));
    }
    reader.endObject();
    return object;
  }

  private static JsonArray readArray(JsonReader reader, ParseBudget budget, int depth)
      throws IOException {
    JsonArray array = new JsonArray();
    reader.beginArray();
    while (reader.hasNext()) {
      if (array.size() >= MAX_ARRAY_ITEMS) {
        throw failure("CLIENT_FRAME_JSON_LIMIT");
      }
      array.add(readElement(reader, budget, depth + 1));
    }
    reader.endArray();
    return array;
  }

  private static void requireFields(JsonObject object, Set<String> fields) {
    if (!object.keySet().equals(fields)) {
      throw failure("CLIENT_FRAME_FIELDS_INVALID");
    }
  }

  private static JsonObject object(JsonObject object, String name) {
    JsonElement value = object.get(name);
    if (value == null || !value.isJsonObject()) {
      throw failure("CLIENT_FRAME_FIELD_INVALID");
    }
    return value.getAsJsonObject();
  }

  private static String string(JsonObject object, String name, int maximum) {
    JsonElement value = object.get(name);
    if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
      throw failure("CLIENT_FRAME_FIELD_INVALID");
    }
    String result = value.getAsString();
    if (result.isEmpty() || result.length() > maximum || result.indexOf('\0') >= 0) {
      throw failure("CLIENT_FRAME_FIELD_INVALID");
    }
    return result;
  }

  private static String nullableString(JsonObject object, String name, int maximum) {
    JsonElement value = object.get(name);
    return value.isJsonNull() ? null : string(object, name, maximum);
  }

  private static boolean bool(JsonObject object, String name) {
    JsonElement value = object.get(name);
    if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
      throw failure("CLIENT_FRAME_FIELD_INVALID");
    }
    return value.getAsBoolean();
  }

  private static int integer(JsonObject object, String name, int minimum, int maximum) {
    JsonElement value = object.get(name);
    if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
      throw failure("CLIENT_FRAME_FIELD_INVALID");
    }
    try {
      BigDecimal number = value.getAsBigDecimal();
      int result = number.intValueExact();
      if (result < minimum || result > maximum) {
        throw failure("CLIENT_FRAME_FIELD_INVALID");
      }
      return result;
    } catch (ArithmeticException error) {
      throw failure("CLIENT_FRAME_FIELD_INVALID");
    }
  }

  private static UUID uuid(JsonObject object, String name, boolean nullable) {
    JsonElement value = object.get(name);
    if (nullable && value != null && value.isJsonNull()) {
      return null;
    }
    String source = string(object, name, 36);
    try {
      UUID result = UUID.fromString(source);
      if (!result.toString().equals(source)) {
        throw failure("CLIENT_FRAME_UUID_INVALID");
      }
      return result;
    } catch (IllegalArgumentException error) {
      throw failure("CLIENT_FRAME_UUID_INVALID");
    }
  }

  private static String hash(JsonObject object, String name) {
    String value = string(object, name, 64);
    if (!SHA256.matcher(value).matches()) {
      throw failure("CLIENT_FRAME_HASH_INVALID");
    }
    return value;
  }

  private static void requireGeneration(long generation) {
    if (generation < 1 || generation > Integer.MAX_VALUE) {
      throw new IllegalArgumentException("Invalid client connection generation");
    }
  }

  private static void requireCode(String code) {
    if (code == null || !STABLE_CODE.matcher(code).matches()) {
      throw new IllegalArgumentException("Invalid client status code");
    }
  }

  private static void addNullable(JsonObject object, String name, String value) {
    if (value == null) {
      object.add(name, JsonNull.INSTANCE);
    } else {
      object.addProperty(name, value);
    }
  }

  private static String sha256(byte[] bytes) {
    try {
      return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 is unavailable", error);
    }
  }

  private static ClientPayloadException failure(String code) {
    return new ClientPayloadException(code);
  }

  private static final class ParseBudget {
    private int nodes;
    private int stringCharacters;

    private void node(int depth) {
      nodes++;
      if (depth > MAX_DEPTH || nodes > MAX_NODES) {
        throw failure("CLIENT_FRAME_JSON_LIMIT");
      }
    }

    private void string(String value) {
      stringCharacters = Math.addExact(stringCharacters, value.length());
      if (stringCharacters > MAX_STRING_CHARS) {
        throw failure("CLIENT_FRAME_JSON_LIMIT");
      }
    }
  }
}
