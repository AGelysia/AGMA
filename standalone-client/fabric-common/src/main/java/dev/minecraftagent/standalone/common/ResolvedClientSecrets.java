package dev.minecraftagent.standalone.common;

import java.util.Objects;

/** Connector and Provider secrets resolved in memory, never serialized by the profile codec. */
public record ResolvedClientSecrets(
    SecretMaterial connectorToken, SecretMaterial modelApiKey, SecretMaterial searchApiKey)
    implements AutoCloseable {
  public ResolvedClientSecrets {
    Objects.requireNonNull(connectorToken, "connectorToken");
    Objects.requireNonNull(modelApiKey, "modelApiKey");
  }

  public ResolvedClientSecrets(SecretMaterial connectorToken, SecretMaterial modelApiKey) {
    this(connectorToken, modelApiKey, null);
  }

  @Override
  public void close() {
    connectorToken.close();
    modelApiKey.close();
    if (searchApiKey != null) {
      searchApiKey.close();
    }
  }

  @Override
  public String toString() {
    return "ResolvedClientSecrets[redacted]";
  }
}
