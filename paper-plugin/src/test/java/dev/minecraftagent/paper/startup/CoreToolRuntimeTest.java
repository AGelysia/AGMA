package dev.minecraftagent.paper.startup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CoreToolRuntimeTest {
  @Test
  void initializesAllPinnedReadOnlyClosedExecutableDescriptors() throws Exception {
    var runtime = CoreToolRuntime.initializeDefaults();

    assertTrue(runtime.ready());
    assertTrue(runtime.executionAvailable());
    assertEquals(CoreToolRuntime.REQUIRED_TOOL_IDS, descriptorIds(runtime.descriptors()));
    assertTrue(
        runtime.descriptors().stream()
            .allMatch(
                descriptor ->
                    descriptor.accessMode() == CoreToolDescriptor.AccessMode.READ
                        && descriptor.schemaClosed()
                        && descriptor.executionCapable()));
    assertThrows(
        UnsupportedOperationException.class,
        () -> runtime.descriptors().add(runtime.descriptors().getFirst()));
  }

  @Test
  void rejectsMissingDuplicateWritableOrExecutableDescriptors() throws Exception {
    var defaults = new ArrayList<>(CoreToolRuntime.initializeDefaults().descriptors());

    var missing = new ArrayList<>(defaults);
    missing.removeFirst();
    assertCode(StartupFailure.Code.CORE_TOOL_MISSING, missing);

    var duplicate = new ArrayList<>(defaults);
    duplicate.add(defaults.getFirst());
    assertCode(StartupFailure.Code.CORE_TOOL_DUPLICATE, duplicate);

    var writable = new ArrayList<>(defaults);
    var first = writable.getFirst();
    writable.set(
        0, new CoreToolDescriptor(first.id(), CoreToolDescriptor.AccessMode.WRITE, true, false));
    assertCode(StartupFailure.Code.CORE_TOOL_UNSAFE, writable);

    var nonExecutable = new ArrayList<>(defaults);
    nonExecutable.set(
        0, new CoreToolDescriptor(first.id(), CoreToolDescriptor.AccessMode.READ, true, false));
    assertCode(StartupFailure.Code.CORE_TOOL_UNSAFE, nonExecutable);

    var unknown = new ArrayList<>(defaults);
    unknown.add(
        new CoreToolDescriptor(
            "server.unknown.read", CoreToolDescriptor.AccessMode.READ, true, false));
    assertCode(StartupFailure.Code.CORE_TOOL_UNSAFE, unknown);
  }

  private static java.util.Set<String> descriptorIds(List<CoreToolDescriptor> descriptors) {
    return descriptors.stream()
        .map(CoreToolDescriptor::id)
        .collect(java.util.stream.Collectors.toSet());
  }

  private static void assertCode(
      StartupFailure.Code expectedCode, List<CoreToolDescriptor> descriptors) {
    var failure = assertThrows(StartupFailure.class, () -> CoreToolRuntime.initialize(descriptors));
    assertEquals(expectedCode, failure.code());
  }
}
