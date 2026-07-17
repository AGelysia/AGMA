package dev.minecraftagent.client.view;

import java.util.Map;

/** Resolves one complete protocol state against the active client registry. */
@FunctionalInterface
public interface BuildPreviewBlockStateResolver {
  void validate(String blockId, Map<String, String> properties) throws IllegalArgumentException;
}
