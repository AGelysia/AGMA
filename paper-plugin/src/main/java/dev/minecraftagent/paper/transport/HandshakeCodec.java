package dev.minecraftagent.paper.transport;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

final class HandshakeCodec {
  private static final Duration CLOCK_SKEW = Duration.ofSeconds(30);
  private static final Pattern SERVER_ID = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$");
  private static final Pattern COMPONENT_VERSION =
      Pattern.compile("^[0-9]+\\.[0-9]+\\.[0-9]+(?:[-+][0-9A-Za-z.-]+)?$");
  private static final Pattern BASE64_URL = Pattern.compile("^[A-Za-z0-9_-]+$");
  private static final Set<String> ENVELOPE_FIELDS =
      Set.of(
          "protocolVersion",
          "messageId",
          "requestId",
          "serverId",
          "type",
          "timestamp",
          "nonce",
          "payload");
  private static final Set<String> PAYLOAD_FIELDS =
      Set.of(
          "component",
          "componentVersion",
          "supportedProtocolVersions",
          "selectedProtocolVersion",
          "authentication");
  private static final Set<String> AUTHENTICATION_FIELDS =
      Set.of("scheme", "keyId", "challenge", "proof");

  private final SecureRandom secureRandom;
  private final Supplier<Instant> now;
  private final ReplayCache replayCache;

  HandshakeCodec(SecureRandom secureRandom, Supplier<Instant> now, ReplayCache replayCache) {
    this.secureRandom = secureRandom;
    this.now = now;
    this.replayCache = replayCache;
  }

  PaperHello createPaperHello(RuntimeConnectionSettings settings) {
    var messageId = UUID.randomUUID().toString();
    var timestamp = now.get().toString();
    var nonce = randomBase64Url();
    var challenge = randomBase64Url();
    var proof =
        HandshakeProof.compute(
            settings.serverToken(),
            settings.serverId(),
            "paper.hello",
            timestamp,
            nonce,
            "paper",
            settings.componentVersion(),
            challenge);

    var authentication = new JsonObject();
    authentication.addProperty("scheme", "hmac-sha256");
    authentication.addProperty("keyId", settings.serverId());
    authentication.addProperty("challenge", challenge);
    authentication.addProperty("proof", proof);

    var payload = new JsonObject();
    payload.addProperty("component", "paper");
    payload.addProperty("componentVersion", settings.componentVersion());
    var versions = new JsonArray();
    versions.add("1.0");
    payload.add("supportedProtocolVersions", versions);
    payload.add("selectedProtocolVersion", JsonNull.INSTANCE);
    payload.add("authentication", authentication);

    var envelope = new JsonObject();
    envelope.addProperty("protocolVersion", "1.0");
    envelope.addProperty("messageId", messageId);
    envelope.addProperty("requestId", messageId);
    envelope.addProperty("serverId", settings.serverId());
    envelope.addProperty("type", "paper.hello");
    envelope.addProperty("timestamp", timestamp);
    envelope.addProperty("nonce", nonce);
    envelope.add("payload", payload);
    return new PaperHello(envelope.toString(), messageId, nonce, challenge);
  }

  void validateRuntimeHello(String text, PaperHello request, RuntimeConnectionSettings settings) {
    var root = StrictJson.parseObject(text);
    requireFields(root, ENVELOPE_FIELDS);
    var protocolVersion = string(root, "protocolVersion");
    if (!"1.0".equals(protocolVersion)) {
      throw failure("PROTOCOL_INCOMPATIBLE", "protocol");
    }
    var payload = object(root, "payload");
    requireFields(payload, PAYLOAD_FIELDS);
    if (!"1.0".equals(nullableString(payload, "selectedProtocolVersion"))) {
      throw failure("PROTOCOL_INCOMPATIBLE", "protocol");
    }
    var messageId = uuid(root, "messageId");
    var requestId = uuid(root, "requestId");
    if (messageId.equals(request.messageId()) || !requestId.equals(request.messageId())) {
      throw invalid();
    }
    var serverId = string(root, "serverId");
    if (!SERVER_ID.matcher(serverId).matches() || !serverId.equals(settings.serverId())) {
      throw failure("SERVER_ID_MISMATCH", "protocol");
    }
    if (!"runtime.hello".equals(string(root, "type"))) {
      throw invalid();
    }
    var timestampText = string(root, "timestamp");
    var timestamp = parseTimestamp(timestampText);
    if (Duration.between(now.get(), timestamp).abs().compareTo(CLOCK_SKEW) > 0) {
      throw failure("HANDSHAKE_STALE", "authentication");
    }
    var nonce = string(root, "nonce");
    requireRandomValue(nonce);
    if (nonce.equals(request.nonce())) {
      throw failure("HANDSHAKE_REPLAYED", "authentication");
    }

    if (!"runtime".equals(string(payload, "component"))) {
      throw invalid();
    }
    var componentVersion = string(payload, "componentVersion");
    if (componentVersion.length() > 64 || !COMPONENT_VERSION.matcher(componentVersion).matches()) {
      throw invalid();
    }
    var supported = array(payload, "supportedProtocolVersions");
    if (supported.size() != 1 || !"1.0".equals(string(supported.get(0)))) {
      throw failure("PROTOCOL_INCOMPATIBLE", "protocol");
    }
    var authentication = object(payload, "authentication");
    requireFields(authentication, AUTHENTICATION_FIELDS);
    var scheme = string(authentication, "scheme");
    var keyId = string(authentication, "keyId");
    var challenge = string(authentication, "challenge");
    var proof = string(authentication, "proof");
    var authenticationShapeValid =
        "hmac-sha256".equals(scheme)
            && keyId.equals(settings.serverId())
            && challenge.equals(request.challenge())
            && proof.length() == 43
            && BASE64_URL.matcher(proof).matches();
    var proofValid =
        HandshakeProof.verify(
            proof,
            settings.serverToken(),
            serverId,
            "runtime.hello",
            timestampText,
            nonce,
            "runtime",
            componentVersion,
            challenge);
    if (!authenticationShapeValid || !proofValid) {
      throw failure("TOKEN_AUTH_FAILED", "authentication");
    }

    if (!replayCache.accept(messageId, nonce, now.get())) {
      throw failure("HANDSHAKE_REPLAYED", "authentication");
    }
  }

  private String randomBase64Url() {
    var bytes = new byte[16];
    secureRandom.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private static void requireFields(JsonObject object, Set<String> expected) {
    if (!object.keySet().equals(expected)) {
      throw invalid();
    }
  }

  private static JsonObject object(JsonObject object, String name) {
    var value = object.get(name);
    if (value == null || !value.isJsonObject()) {
      throw invalid();
    }
    return value.getAsJsonObject();
  }

  private static JsonArray array(JsonObject object, String name) {
    var value = object.get(name);
    if (value == null || !value.isJsonArray()) {
      throw invalid();
    }
    return value.getAsJsonArray();
  }

  private static String nullableString(JsonObject object, String name) {
    var value = object.get(name);
    if (value == null || value.isJsonNull()) {
      return null;
    }
    return string(value);
  }

  private static String string(JsonObject object, String name) {
    var value = object.get(name);
    if (value == null) {
      throw invalid();
    }
    return string(value);
  }

  private static String string(JsonElement value) {
    if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
      throw invalid();
    }
    return value.getAsString();
  }

  private static String uuid(JsonObject object, String name) {
    var value = string(object, name);
    try {
      var parsed = UUID.fromString(value);
      if (!parsed.toString().equals(value)) {
        throw invalid();
      }
      return value;
    } catch (IllegalArgumentException error) {
      throw invalid();
    }
  }

  private static Instant parseTimestamp(String value) {
    try {
      return Instant.parse(value);
    } catch (DateTimeParseException error) {
      throw invalid();
    }
  }

  private static void requireRandomValue(String value) {
    if (value.length() < 22 || value.length() > 128 || !BASE64_URL.matcher(value).matches()) {
      throw invalid();
    }
    try {
      var decoded = Base64.getUrlDecoder().decode(value);
      if (decoded.length < 16
          || !Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(value)) {
        throw invalid();
      }
    } catch (IllegalArgumentException error) {
      throw invalid();
    }
  }

  private static RuntimeConnectionFailure invalid() {
    return failure("HANDSHAKE_MESSAGE_INVALID", "authentication");
  }

  private static RuntimeConnectionFailure failure(String code, String stage) {
    return new RuntimeConnectionFailure(code, stage);
  }

  record PaperHello(String text, String messageId, String nonce, String challenge) {}
}
