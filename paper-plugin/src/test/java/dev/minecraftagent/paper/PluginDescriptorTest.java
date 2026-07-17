package dev.minecraftagent.paper;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class PluginDescriptorTest {
  @Test
  void descriptorLeavesAgentCommandForConditionalRuntimeRegistration() throws IOException {
    try (var stream = getClass().getClassLoader().getResourceAsStream("paper-plugin.yml")) {
      assertNotNull(stream);
      var descriptor = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
      assertTrue(descriptor.contains("name: AGMA"));
      assertTrue(descriptor.contains("main: dev.minecraftagent.paper.MinecraftAgentPlugin"));
      assertTrue(descriptor.contains("prefix: AGMA"));
      assertTrue(descriptor.contains("minecraftagent.admin.setup:"));
      assertTrue(descriptor.contains("minecraftagent.use:"));
      assertTrue(
          descriptor.contains(
              "minecraftagent.use:\n"
                  + "    description: Allows a player to ask AGMA private questions\n"
                  + "    default: true"));
      assertTrue(descriptor.contains("minecraftagent.command.agent:"));
      assertTrue(descriptor.contains("minecraftagent.ui:"));
      assertTrue(descriptor.contains("minecraftagent.admin.status:"));
      assertTrue(descriptor.contains("minecraftagent.admin.doctor:"));
      assertTrue(descriptor.contains("minecraftagent.admin.reload:"));
      assertTrue(descriptor.contains("minecraftagent.admin.capabilities:"));
      assertTrue(descriptor.contains("minecraftagent.admin.costs:"));
      assertTrue(
          descriptor.contains(
              "minecraftagent.admin.reload:\n"
                  + "    description: Names the Console or configured Owner reload operation\n"
                  + "    default: false"));
      assertFalse(
          descriptor.contains(
              "minecraftagent.command.agent:\n"
                  + "    description: Aggregate for read-only AGMA management queries\n"
                  + "    default: op\n"
                  + "    children:\n"
                  + "      minecraftagent.admin.reload: true"));
      assertTrue(
          descriptor.contains(
              "minecraftagent.proposal.respond:\n"
                  + "    description: Allows a player to confirm or reject their own AGMA proposals\n"
                  + "    default: true"));
      assertTrue(
          descriptor.contains(
              "minecraftagent.write.temporary:\n"
                  + "    description: Allows temporary typed proposal tools when one is installed\n"
                  + "    default: false"));
      assertTrue(
          descriptor.contains(
              "minecraftagent.write.world:\n"
                  + "    description: Allows authorized operators to confirm typed world writes\n"
                  + "    default: op"));
      assertTrue(
          descriptor.contains(
              "minecraftagent.write.player:\n"
                  + "    description: Allows authorized operators to confirm typed player writes\n"
                  + "    default: op"));
      assertTrue(
          descriptor.contains(
              "minecraftagent.server.admin:\n"
                  + "    description: Allows configured owners to confirm typed server administration\n"
                  + "    default: false"));
      assertTrue(
          descriptor.contains(
              "minecraftagent.module:\n"
                  + "    description: Allows a player to route one request through an explicit Agent module\n"
                  + "    default: true"));
      assertTrue(descriptor.contains("minecraftagent.admin.toggle:"));
      assertTrue(
          descriptor.contains(
              "minecraftagent.admin.toggle:\n"
                  + "    description: Allows authorized administrators to toggle AGMA\n"
                  + "    default: op"));
      assertFalse(descriptor.contains("commands:"));
    }
  }
}
