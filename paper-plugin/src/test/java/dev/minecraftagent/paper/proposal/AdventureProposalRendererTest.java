package dev.minecraftagent.paper.proposal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

class AdventureProposalRendererTest {
  private static final UUID PROPOSAL_ID = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
  private static final String ARGUMENT_HASH =
      "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

  @Test
  void rendersOnlyLiteralServerOwnedFieldsAndFixedProposalActions() {
    var displayName = "<click:run_command:'/op attacker'>Fill spawn</click>";
    var view =
        new ProposalView(
            PROPOSAL_ID,
            "world.fill",
            displayName,
            RiskLevel.WRITE_WORLD,
            ARGUMENT_HASH,
            Instant.parse("2026-07-13T10:15:30Z"),
            new ProposalView.Action(ProposalView.ActionKind.CONFIRM, PROPOSAL_ID),
            new ProposalView.Action(ProposalView.ActionKind.REJECT, PROPOSAL_ID));

    var rendered = new AdventureProposalRenderer().render(view);
    var plain = PlainTextComponentSerializer.plainText().serialize(rendered);
    assertTrue(plain.contains(displayName));
    assertTrue(plain.contains("WRITE_WORLD"));
    assertTrue(plain.contains("2026-07-13T10:15:30Z"));
    assertFalse(plain.contains(ARGUMENT_HASH));
    assertFalse(plain.contains("world.fill"));

    var clickable =
        descendants(rendered).stream().filter(node -> node.clickEvent() != null).toList();
    assertEquals(2, clickable.size());
    assertClick(
        clickable.get(0),
        "[Confirm]",
        "/minecraftagent:agent confirm " + PROPOSAL_ID,
        "Confirm this proposal.");
    assertClick(
        clickable.get(1),
        "[Reject]",
        "/minecraftagent:agent reject " + PROPOSAL_ID,
        "Reject this proposal.");

    for (var node : descendants(rendered)) {
      if (!clickable.contains(node)) {
        assertNull(node.clickEvent());
        assertNull(node.hoverEvent());
      }
    }
  }

  private static void assertClick(
      Component component, String label, String command, String hoverText) {
    assertEquals(label, PlainTextComponentSerializer.plainText().serialize(component));
    assertEquals(ClickEvent.Action.RUN_COMMAND, component.clickEvent().action());
    assertEquals(command, ((ClickEvent.Payload.Text) component.clickEvent().payload()).value());
    assertEquals(HoverEvent.Action.SHOW_TEXT, component.hoverEvent().action());
    assertEquals(
        hoverText,
        PlainTextComponentSerializer.plainText()
            .serialize((Component) component.hoverEvent().value()));
  }

  private static List<Component> descendants(Component root) {
    var result = new ArrayList<Component>();
    collect(root, result);
    return result;
  }

  private static void collect(Component component, List<Component> result) {
    result.add(component);
    component.children().forEach(child -> collect(child, result));
  }
}
