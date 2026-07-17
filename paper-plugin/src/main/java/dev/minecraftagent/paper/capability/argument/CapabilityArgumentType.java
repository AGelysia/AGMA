package dev.minecraftagent.paper.capability.argument;

import java.util.Arrays;

/** Closed capability argument type identifiers from capability.schema.json. */
public enum CapabilityArgumentType {
  STRING("string"),
  INTEGER("integer"),
  NUMBER("number"),
  BOOLEAN("boolean"),
  ENUM("enum"),
  BLOCK_PATTERN("minecraft:block-pattern"),
  ITEM("minecraft:item"),
  PLAYER("minecraft:player"),
  DIMENSION("minecraft:dimension"),
  COORDINATES("minecraft:coordinates");

  private final String protocolName;

  CapabilityArgumentType(String protocolName) {
    this.protocolName = protocolName;
  }

  public String protocolName() {
    return protocolName;
  }

  static CapabilityArgumentType parse(String value, String argumentName) {
    return Arrays.stream(values())
        .filter(type -> type.protocolName.equals(value))
        .findFirst()
        .orElseThrow(
            () ->
                new CapabilityArgumentException(
                    CapabilityArgumentException.Failure.DESCRIPTOR_INVALID, argumentName));
  }
}
