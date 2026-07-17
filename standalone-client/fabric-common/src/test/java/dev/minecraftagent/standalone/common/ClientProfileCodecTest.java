package dev.minecraftagent.standalone.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.minecraftagent.standalone.core.contract.RuntimeClientProfile;
import org.junit.jupiter.api.Test;

class ClientProfileCodecTest {
  private final ClientProfileCodec codec = new ClientProfileCodec();

  @Test
  void roundTripsOnlySecretReferences() {
    var profile = TestProfiles.environment(38_127);
    var encoded = codec.encode(profile);

    assertEquals(profile, codec.decode(encoded));
    assertTrue(encoded.contains("AGMA_CLIENT_CONNECTOR_TOKEN"));
    assertTrue(encoded.contains("OPENAI_API_KEY"));
    assertFalse(encoded.contains("resolved-connector-secret"));
    assertFalse(encoded.contains("resolved-provider-secret"));
  }

  @Test
  void rejectsDuplicateAndInlineSecretProperties() {
    var encoded = codec.encode(TestProfiles.environment(38_127));
    var duplicate =
        encoded.replaceFirst(
            "\\\"profile\\\":\\\"client\\\"",
            "\\\"profile\\\":\\\"client\\\",\\\"profile\\\":\\\"client\\\"");
    var inline =
        encoded.replaceFirst(
            "\\\"reference\\\":\\\"AGMA_CLIENT_CONNECTOR_TOKEN\\\"",
            "\\\"reference\\\":\\\"AGMA_CLIENT_CONNECTOR_TOKEN\\\",\\\"value\\\":\\\"plaintext\\\"");

    assertEquals(
        "CONFIG_SCHEMA_INVALID",
        assertThrows(ClientConfigurationException.class, () -> codec.decode(duplicate)).code());
    assertEquals(
        "CONFIG_SCHEMA_INVALID",
        assertThrows(ClientConfigurationException.class, () -> codec.decode(inline)).code());
  }

  @Test
  void credentialStoreReferencesFailClosed() {
    var profile =
        TestProfiles.profile(
            38_127,
            new RuntimeClientProfile.SecretReference("credential_store", "connector-token"),
            new RuntimeClientProfile.SecretReference("environment", "OPENAI_API_KEY"));

    assertEquals(
        "SECRET_REFERENCE_UNSUPPORTED",
        assertThrows(ClientConfigurationException.class, () -> codec.encode(profile)).code());
  }
}
