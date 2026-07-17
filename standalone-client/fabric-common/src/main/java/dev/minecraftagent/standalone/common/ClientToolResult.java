package dev.minecraftagent.standalone.common;

import java.util.Map;

public record ClientToolResult(Map<String, Object> result) implements ClientToolOutcome {
  public ClientToolResult {
    result = ClientToolPayloads.copyObject(result, 16);
  }
}
