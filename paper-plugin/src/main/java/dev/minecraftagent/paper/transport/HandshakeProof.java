package dev.minecraftagent.paper.transport;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class HandshakeProof {
  private static final String DOMAIN = "minecraft-agent-handshake-v1";

  private HandshakeProof() {}

  public static String compute(
      String token,
      String serverId,
      String type,
      String timestamp,
      String nonce,
      String component,
      String componentVersion,
      String challenge) {
    var transcript =
        String.join(
            "\n", DOMAIN, serverId, type, timestamp, nonce, component, componentVersion, challenge);
    try {
      var mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(token.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return Base64.getUrlEncoder()
          .withoutPadding()
          .encodeToString(mac.doFinal(transcript.getBytes(StandardCharsets.UTF_8)));
    } catch (GeneralSecurityException error) {
      throw new IllegalStateException("HmacSHA256 is unavailable", error);
    }
  }

  public static boolean verify(
      String actualProof,
      String token,
      String serverId,
      String type,
      String timestamp,
      String nonce,
      String component,
      String componentVersion,
      String challenge) {
    byte[] actual;
    try {
      actual = Base64.getUrlDecoder().decode(actualProof);
    } catch (IllegalArgumentException error) {
      actual = new byte[0];
    }
    var expected =
        Base64.getUrlDecoder()
            .decode(
                compute(
                    token,
                    serverId,
                    type,
                    timestamp,
                    nonce,
                    component,
                    componentVersion,
                    challenge));
    return MessageDigest.isEqual(actual, expected);
  }
}
