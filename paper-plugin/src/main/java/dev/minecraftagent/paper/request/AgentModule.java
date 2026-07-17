package dev.minecraftagent.paper.request;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

public enum AgentModule {
  GENERAL("general"),
  RECIPE("recipe"),
  GUIDE("guide"),
  LOCATE("locate"),
  BUILD("build"),
  PROJECT("project");

  private static final List<String> PROTOCOL_NAMES =
      java.util.Arrays.stream(values()).map(AgentModule::protocolName).toList();

  private final String protocolName;

  AgentModule(String protocolName) {
    this.protocolName = protocolName;
  }

  public String protocolName() {
    return protocolName;
  }

  public static List<String> protocolNames() {
    return PROTOCOL_NAMES;
  }

  public static Optional<AgentModule> fromProtocolName(String value) {
    if (value == null) {
      return Optional.empty();
    }
    var normalized = value.toLowerCase(Locale.ROOT);
    return java.util.Arrays.stream(values())
        .filter(module -> module.protocolName.equals(normalized))
        .findFirst();
  }
}
