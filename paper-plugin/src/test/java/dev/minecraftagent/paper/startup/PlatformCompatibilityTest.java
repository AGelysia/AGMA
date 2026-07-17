package dev.minecraftagent.paper.startup;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class PlatformCompatibilityTest {
  private final PlatformCompatibility compatibility = new PlatformCompatibility();

  @Test
  void acceptsJava21OrNewerOnlyForThePinnedMinecraftVersion() {
    assertDoesNotThrow(() -> compatibility.check(21, "1.21.11"));
    assertDoesNotThrow(() -> compatibility.check(25, "1.21.11"));

    var javaFailure = assertThrows(StartupFailure.class, () -> compatibility.check(17, "1.21.11"));
    assertEquals(StartupFailure.Code.JAVA_VERSION_UNSUPPORTED, javaFailure.code());

    var versionFailure =
        assertThrows(StartupFailure.class, () -> compatibility.check(21, "1.21.10"));
    assertEquals(StartupFailure.Code.PAPER_VERSION_UNSUPPORTED, versionFailure.code());
  }
}
