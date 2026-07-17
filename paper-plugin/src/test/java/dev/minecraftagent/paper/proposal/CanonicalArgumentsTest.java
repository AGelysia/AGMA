package dev.minecraftagent.paper.proposal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class CanonicalArgumentsTest {
  @Test
  void matchesTheSharedRfc8785GoldenFixture() throws Exception {
    var protocolRoot =
        Path.of(System.getProperty("minecraftAgent.protocolDir")).toAbsolutePath().normalize();
    var root =
        JsonParser.parseString(
                Files.readString(
                    protocolRoot.resolve("fixtures/valid/proposal-argument-hash-v1.json")))
            .getAsJsonObject();
    var contract = root.getAsJsonObject("hashContract");
    var proposal = root.getAsJsonObject("proposal");
    var frozen = CanonicalArguments.freeze(proposal.getAsJsonObject("arguments"));

    assertEquals(contract.get("canonicalArguments").getAsString(), frozen.canonicalJson());
    assertEquals(contract.get("argumentHash").getAsString(), frozen.sha256());
    assertEquals(proposal.get("argumentHash").getAsString(), frozen.sha256());
  }

  @Test
  void usesEcmaScriptNumberFormattingThresholds() {
    var values = new JsonObject();
    values.addProperty("a", 1e23);
    values.addProperty("b", 1e21);
    values.addProperty("c", 1e20);
    values.addProperty("d", 1e7);
    values.addProperty("e", 1e-6);
    values.addProperty("f", 1e-7);
    values.addProperty("g", 333333333.33333329);
    values.addProperty("h", Double.MIN_VALUE);

    assertEquals(
        "{\"a\":1e+23,\"b\":1e+21,\"c\":100000000000000000000,\"d\":10000000,"
            + "\"e\":0.000001,\"f\":1e-7,\"g\":333333333.3333333,\"h\":5e-324}",
        CanonicalArguments.freeze(values).canonicalJson());
  }

  @Test
  void objectOrderAndEquivalentNumbersHaveOneDefinedHash() {
    var first = new JsonObject();
    first.addProperty("z", 1.0);
    first.addProperty("a", "value");
    var second = new JsonObject();
    second.addProperty("a", "value");
    second.addProperty("z", 1);

    assertEquals(CanonicalArguments.hash(first), CanonicalArguments.hash(second));
    assertEquals("{\"a\":\"value\",\"z\":1}", CanonicalArguments.freeze(first).canonicalJson());

    second.addProperty("z", "1");
    assertNotEquals(CanonicalArguments.hash(first), CanonicalArguments.hash(second));
  }

  @Test
  void frozenArgumentsAreDetachedAndPersistedTamperingIsDetected() {
    var source = new JsonObject();
    source.addProperty("amount", 2);
    var frozen = CanonicalArguments.freeze(source);
    source.addProperty("amount", 999);

    assertEquals(2, frozen.arguments().get("amount").getAsInt());
    assertTrue(frozen.verified());

    var tampered = CanonicalArguments.fromPersisted("{\"amount\":999}", frozen.sha256());
    assertFalse(tampered.verified());
    assertFalse(tampered.toString().contains("999"));
    assertFalse(tampered.toString().contains(frozen.sha256()));
    assertTrue(tampered.toString().contains("<redacted>"));
  }

  @Test
  void structuralLimitRejectsDeepArgumentBombs() {
    var root = new JsonObject();
    var cursor = new JsonArray();
    root.add("nested", cursor);
    for (int index = 0; index < 40; index++) {
      var next = new JsonArray();
      cursor.add(next);
      cursor = next;
    }

    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalArgumentException.class, () -> CanonicalArguments.freeze(root));
  }
}
