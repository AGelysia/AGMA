package dev.minecraftagent.client.network;

import java.util.Arrays;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Raw UTF-8 JSON carried on the single AGMA custom payload channel. */
public record AgentClientPayload(byte[] bytes) implements CustomPacketPayload {
  public static final int MAX_FRAME_BYTES = 40 * 1024;
  public static final Identifier CHANNEL =
      Identifier.fromNamespaceAndPath("minecraftagent", "client");
  public static final Type<AgentClientPayload> TYPE = new Type<>(CHANNEL);
  public static final StreamCodec<FriendlyByteBuf, AgentClientPayload> CODEC =
      StreamCodec.of(
          (buffer, payload) -> buffer.writeBytes(payload.bytes),
          buffer -> {
            int length = buffer.readableBytes();
            if (length < 1 || length > MAX_FRAME_BYTES) {
              throw new IllegalArgumentException("AGMA payload frame is out of bounds");
            }
            byte[] bytes = new byte[length];
            buffer.readBytes(bytes);
            return new AgentClientPayload(bytes);
          });

  public AgentClientPayload {
    if (bytes == null || bytes.length < 1 || bytes.length > MAX_FRAME_BYTES) {
      throw new IllegalArgumentException("AGMA payload frame is out of bounds");
    }
    bytes = Arrays.copyOf(bytes, bytes.length);
  }

  @Override
  public byte[] bytes() {
    return Arrays.copyOf(bytes, bytes.length);
  }

  @Override
  public Type<AgentClientPayload> type() {
    return TYPE;
  }
}
