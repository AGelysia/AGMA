package dev.minecraftagent.paper.capability.load;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DeterministicVersionRangeTest {
  @Test
  void matchesClosedNumericAndRangesAtTheirBoundaries() {
    assertTrue(DeterministicVersionRange.parse("=2.20.1").includes("2.20.1"));
    assertTrue(DeterministicVersionRange.parse(">=7.3 <8").includes("7.3.0"));
    assertTrue(DeterministicVersionRange.parse(">=7.3 <8").includes("7.99.999"));
    assertFalse(DeterministicVersionRange.parse(">=7.3 <8").includes("8"));
    assertTrue(DeterministicVersionRange.parse("=1").includes("1.0.0"));
    assertTrue(DeterministicVersionRange.parse(">7.2").includes("7.2.1"));
    assertFalse(DeterministicVersionRange.parse(">7.2").includes("7.2"));
    assertTrue(DeterministicVersionRange.parse("<=7.2").includes("7.2"));
    assertFalse(DeterministicVersionRange.parse("<=7.2").includes("7.2.1"));
  }

  @Test
  void rejectsEveryGrammarOutsideV1() {
    for (var invalid :
        new String[] {
          "2.20.1",
          "=01",
          "=1.02",
          "=1.2.03",
          "=1.2.3.4",
          "=1.*",
          "^1",
          "~1",
          ">=1 || <2",
          ">=1, <2",
          ">=1  <2",
          " >=1",
          ">=1 ",
          "=1-SNAPSHOT"
        }) {
      assertThrows(IllegalArgumentException.class, () -> DeterministicVersionRange.parse(invalid));
    }
    assertFalse(DeterministicVersionRange.validInstalledVersion("01.2"));
    assertFalse(DeterministicVersionRange.validInstalledVersion("1.2-SNAPSHOT"));
  }
}
