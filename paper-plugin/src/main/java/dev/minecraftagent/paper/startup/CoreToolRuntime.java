package dev.minecraftagent.paper.startup;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public final class CoreToolRuntime {
  public static final Set<String> REQUIRED_TOOL_IDS =
      Set.of(
          "player.context.read",
          "player.held_item.read",
          "server.info.read",
          "server.plugins.list",
          "server.recipe.lookup",
          "server.recipe.uses",
          "landmark.search",
          "build.preview.create");

  private static final Pattern TOOL_ID = Pattern.compile("[a-z][a-z0-9_]*(?:\\.[a-z][a-z0-9_]*)+");
  private final Map<String, CoreToolDescriptor> descriptors;

  private CoreToolRuntime(Map<String, CoreToolDescriptor> descriptors) {
    this.descriptors = Collections.unmodifiableMap(new LinkedHashMap<>(descriptors));
  }

  public static CoreToolRuntime initializeDefaults() throws StartupFailure {
    var defaults = new ArrayList<CoreToolDescriptor>();
    REQUIRED_TOOL_IDS.stream()
        .sorted()
        .forEach(
            id ->
                defaults.add(
                    new CoreToolDescriptor(id, CoreToolDescriptor.AccessMode.READ, true, true)));
    return initialize(defaults);
  }

  public static CoreToolRuntime initialize(Collection<CoreToolDescriptor> source)
      throws StartupFailure {
    if (source == null || source.isEmpty()) {
      throw failure(StartupFailure.Code.CORE_TOOL_MISSING);
    }
    try {
      var indexed = new LinkedHashMap<String, CoreToolDescriptor>();
      for (var descriptor : source) {
        if (descriptor == null
            || !TOOL_ID.matcher(descriptor.id()).matches()
            || descriptor.accessMode() != CoreToolDescriptor.AccessMode.READ
            || !descriptor.schemaClosed()
            || !descriptor.executionCapable()) {
          throw failure(StartupFailure.Code.CORE_TOOL_UNSAFE);
        }
        if (indexed.putIfAbsent(descriptor.id(), descriptor) != null) {
          throw failure(StartupFailure.Code.CORE_TOOL_DUPLICATE);
        }
      }
      if (!indexed.keySet().containsAll(REQUIRED_TOOL_IDS)) {
        throw failure(StartupFailure.Code.CORE_TOOL_MISSING);
      }
      if (!indexed.keySet().equals(REQUIRED_TOOL_IDS)) {
        throw failure(StartupFailure.Code.CORE_TOOL_UNSAFE);
      }
      return new CoreToolRuntime(indexed);
    } catch (StartupFailure failure) {
      throw failure;
    } catch (RuntimeException exception) {
      throw failure(StartupFailure.Code.CORE_TOOL_INITIALIZATION_FAILED);
    }
  }

  public List<CoreToolDescriptor> descriptors() {
    return List.copyOf(descriptors.values());
  }

  public boolean ready() {
    return descriptors.keySet().equals(REQUIRED_TOOL_IDS);
  }

  public boolean executionAvailable() {
    return ready() && descriptors.values().stream().allMatch(CoreToolDescriptor::executionCapable);
  }

  private static StartupFailure failure(StartupFailure.Code code) {
    return new StartupFailure(code, StartupFailure.Stage.CORE_TOOLS);
  }
}
