package dev.minecraftagent.protocol;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/**
 * The framing codec shared by both ends of the {@code minecraftagent:client} channel.
 *
 * <p>A frame is a raw UTF-8 JSON object {@code
 * {"clientPayloadVersion","messageId","type","payload"}} produced and consumed only through this
 * class, so strict decoding, duplicate-key rejection and the parse budgets are identical on the
 * server and on the client.
 *
 * <p>The grammar is shared; the failure vocabulary is not. Each end constructs its own {@link
 * Codes} so the stable codes it already reports keep their meaning.
 */
public final class ClientPayloadFrames {
  /**
   * Stable failure codes for the shared grammar.
   *
   * @param tooLarge the frame exceeded its byte budget
   * @param utf8Invalid the frame is not raw UTF-8
   * @param jsonInvalid the frame is not strict JSON
   * @param jsonLimit the frame breached a depth, node, field, item or character budget
   * @param duplicateField the frame repeated an object field name
   * @param envelopeFieldsInvalid the envelope key set is not the closed envelope key set
   * @param fieldInvalid a typed envelope field is missing or has the wrong shape
   * @param uuidInvalid a UUID is not canonical lowercase
   * @param protocolIncompatible the envelope version is not {@link
   *     ClientChannelContract#PAYLOAD_VERSION}
   * @param outboundTooLarge an encoded frame exceeded its byte budget
   */
  public record Codes(
      String tooLarge,
      String utf8Invalid,
      String jsonInvalid,
      String jsonLimit,
      String duplicateField,
      String envelopeFieldsInvalid,
      String fieldInvalid,
      String uuidInvalid,
      String protocolIncompatible,
      String outboundTooLarge) {
    public Codes {
      Objects.requireNonNull(tooLarge, "tooLarge");
      Objects.requireNonNull(utf8Invalid, "utf8Invalid");
      Objects.requireNonNull(jsonInvalid, "jsonInvalid");
      Objects.requireNonNull(jsonLimit, "jsonLimit");
      Objects.requireNonNull(duplicateField, "duplicateField");
      Objects.requireNonNull(envelopeFieldsInvalid, "envelopeFieldsInvalid");
      Objects.requireNonNull(fieldInvalid, "fieldInvalid");
      Objects.requireNonNull(uuidInvalid, "uuidInvalid");
      Objects.requireNonNull(protocolIncompatible, "protocolIncompatible");
      Objects.requireNonNull(outboundTooLarge, "outboundTooLarge");
    }
  }

  /** A decoded envelope, before any end applies its own direction or payload policy. */
  public record Frame(UUID messageId, String type, WireJson.ObjectNode payload) {}

  /** Parse budgets for the frame grammar, shared by both directions. */
  public static final WireJsonReader.Budget BUDGET =
      new WireJsonReader.Budget(16, 1024, 64 * 1024, 32, 128);

  private final Codes codes;

  public ClientPayloadFrames(Codes codes) {
    this.codes = Objects.requireNonNull(codes, "codes");
  }

  /** The codes this codec reports. */
  public Codes codes() {
    return codes;
  }

  /**
   * Decodes one frame, validating the envelope but leaving message direction and payload shape to
   * the caller.
   *
   * @param bytes the raw frame
   * @param maximumBytes the byte budget for this direction
   */
  public Frame decode(byte[] bytes, int maximumBytes) {
    Objects.requireNonNull(bytes, "bytes");
    if (bytes.length < 1 || bytes.length > maximumBytes) {
      throw violation(codes.tooLarge());
    }
    var envelope = parseEnvelope(decodeUtf8(bytes, codes.utf8Invalid()));
    ClientPayloadFields.requireFields(
        envelope, ClientChannelContract.ENVELOPE_FIELDS, codes.envelopeFieldsInvalid());
    if (!(envelope.get("clientPayloadVersion") instanceof WireJson.TextNode version)) {
      throw violation(codes.fieldInvalid());
    }
    if (!ClientChannelContract.PAYLOAD_VERSION.equals(version.value())) {
      throw violation(codes.protocolIncompatible());
    }
    var messageId =
        ClientPayloadFields.uuid(
            envelope, "messageId", false, codes.fieldInvalid(), codes.uuidInvalid());
    var type =
        ClientPayloadFields.string(
            envelope, "type", 1, ClientPayloadLimits.MAX_TYPE_CHARS, codes.fieldInvalid());
    var payload = ClientPayloadFields.object(envelope, "payload", codes.fieldInvalid());
    return new Frame(messageId, type, payload);
  }

  /** Encodes one frame and rejects it when it exceeds {@code maximumBytes}. */
  public byte[] encode(UUID messageId, String type, WireJson.ObjectNode payload, int maximumBytes) {
    var envelope =
        new WireJson.ObjectNode()
            .putString("clientPayloadVersion", ClientChannelContract.PAYLOAD_VERSION)
            .putString("messageId", Objects.requireNonNull(messageId, "messageId").toString())
            .putString("type", Objects.requireNonNull(type, "type"))
            .put("payload", Objects.requireNonNull(payload, "payload"));
    var bytes = envelope.toJsonText().getBytes(StandardCharsets.UTF_8);
    if (bytes.length < 1 || bytes.length > maximumBytes) {
      throw violation(codes.outboundTooLarge());
    }
    return bytes;
  }

  private WireJson.ObjectNode parseEnvelope(String text) {
    WireJson value;
    try {
      value = WireJsonReader.parse(text, BUDGET);
    } catch (WireJsonException failure) {
      throw violation(
          switch (failure.reason()) {
            case JSON_INVALID -> codes.jsonInvalid();
            case JSON_LIMIT_EXCEEDED -> codes.jsonLimit();
            case DUPLICATE_FIELD -> codes.duplicateField();
          });
    }
    if (!(value instanceof WireJson.ObjectNode envelope)) {
      throw violation(codes.jsonInvalid());
    }
    return envelope;
  }

  private static String decodeUtf8(byte[] bytes, String utf8Invalid) {
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes))
          .toString();
    } catch (CharacterCodingException error) {
      throw new ProtocolViolationException(utf8Invalid);
    }
  }

  private ProtocolViolationException violation(String code) {
    throw new ProtocolViolationException(code);
  }
}
