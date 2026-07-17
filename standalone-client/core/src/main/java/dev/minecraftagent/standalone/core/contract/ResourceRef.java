package dev.minecraftagent.standalone.core.contract;

import java.math.BigDecimal;
import java.util.Objects;

public record ResourceRef(
    Kind kind,
    String id,
    String componentsFingerprint,
    String displayName,
    String translationKey,
    String modId,
    String modName,
    String modVersion,
    BigDecimal amount,
    String unit,
    Source source) {
  public ResourceRef {
    Objects.requireNonNull(kind, "kind");
    id = ContractChecks.namespacedId(id, "id");
    componentsFingerprint =
        ContractChecks.optionalSha256(componentsFingerprint, "componentsFingerprint");
    displayName = ContractChecks.text(displayName, "displayName", 512);
    translationKey = ContractChecks.optionalText(translationKey, "translationKey", 256);
    modId = ContractChecks.symbolicId(modId, "modId");
    modName = ContractChecks.text(modName, "modName", 128);
    modVersion = ContractChecks.text(modVersion, "modVersion", 128);
    amount = ContractChecks.positive(amount, "amount");
    unit = ContractChecks.text(unit, "unit", 32);
    Objects.requireNonNull(source, "source");
  }

  public enum Kind {
    ITEM,
    FLUID,
    ENERGY,
    CHEMICAL,
    ENTITY,
    CUSTOM
  }

  public enum Layer {
    INTEGRATED_SERVER,
    CLIENT_REGISTRY,
    CLIENT_RECIPE,
    JEI,
    EMI,
    LOCAL_RESOURCE_PACK
  }

  public enum Trust {
    L0A,
    L0B,
    L1,
    L2
  }

  public enum Completeness {
    COMPLETE,
    PARTIAL,
    OPAQUE,
    UNAVAILABLE
  }

  public record Source(
      Layer layer, String providerId, Trust trust, Completeness completeness, String generationId) {
    public Source {
      Objects.requireNonNull(layer, "layer");
      providerId = ContractChecks.symbolicId(providerId, "providerId");
      Objects.requireNonNull(trust, "trust");
      Objects.requireNonNull(completeness, "completeness");
      generationId = ContractChecks.opaqueId(generationId, "generationId");
      var expectedTrust =
          switch (layer) {
            case INTEGRATED_SERVER -> Trust.L0A;
            case CLIENT_REGISTRY -> Trust.L0B;
            case CLIENT_RECIPE, JEI, EMI -> Trust.L1;
            case LOCAL_RESOURCE_PACK -> Trust.L2;
          };
      if (trust != expectedTrust) {
        throw new IllegalArgumentException("source layer and trust level do not match");
      }
    }
  }
}
