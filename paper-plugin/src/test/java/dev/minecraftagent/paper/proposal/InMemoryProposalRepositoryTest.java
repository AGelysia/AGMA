package dev.minecraftagent.paper.proposal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InMemoryProposalRepositoryTest {
  private static final Instant NOW = Instant.parse("2026-07-13T00:00:00Z");

  @Test
  void enforcesPerPlayerAndGlobalActiveLimits() {
    var repository = new InMemoryProposalRepository(2, 1);
    var firstPlayer = UUID.randomUUID();
    var secondPlayer = UUID.randomUUID();
    var first = proposal(firstPlayer, NOW.plusSeconds(60));

    assertTrue(repository.insert(first));
    assertFalse(repository.insert(proposal(firstPlayer, NOW.plusSeconds(60))));
    assertTrue(repository.insert(proposal(secondPlayer, NOW.plusSeconds(60))));
    assertFalse(repository.insert(proposal(UUID.randomUUID(), NOW.plusSeconds(60))));
  }

  @Test
  void pruningReclaimsExpiredAndTerminalEntries() {
    var repository = new InMemoryProposalRepository(1, 1);
    var player = UUID.randomUUID();
    var expired = proposal(player, NOW.plusSeconds(1));
    assertTrue(repository.insert(expired));

    repository.prune(NOW.plusSeconds(1));
    var replacement = proposal(player, NOW.plusSeconds(60));
    assertTrue(repository.insert(replacement));

    assertTrue(repository.find(expired.proposalId()).isEmpty());
    assertTrue(
        repository.transition(
            replacement.proposalId(),
            ProposalRepository.State.PENDING,
            ProposalRepository.State.REJECTED));
    repository.prune(NOW.plusSeconds(2));
    assertTrue(repository.find(replacement.proposalId()).isEmpty());
  }

  @Test
  void invalidationCannotRewriteAnExecutionThatAlreadyPassedFinalAdmission() {
    var repository = new InMemoryProposalRepository(1, 1);
    var admitted = proposal(UUID.randomUUID(), NOW.plusSeconds(60));
    assertTrue(repository.insert(admitted));
    assertTrue(
        repository.transition(
            admitted.proposalId(),
            ProposalRepository.State.PENDING,
            ProposalRepository.State.CLAIMED));
    assertTrue(
        repository.transition(
            admitted.proposalId(),
            ProposalRepository.State.CLAIMED,
            ProposalRepository.State.EXECUTING));

    assertTrue(repository.invalidateAll().isEmpty());
    assertEquals(
        ProposalRepository.State.EXECUTING,
        repository.find(admitted.proposalId()).orElseThrow().state());
    assertTrue(
        repository.transition(
            admitted.proposalId(),
            ProposalRepository.State.EXECUTING,
            ProposalRepository.State.EXECUTED));
  }

  private static StoredProposal proposal(UUID playerUuid, Instant expiresAt) {
    var arguments = new JsonObject();
    arguments.addProperty("amount", 1);
    return new StoredProposal(
        UUID.randomUUID(),
        "paper-test",
        UUID.randomUUID(),
        UUID.randomUUID(),
        playerUuid,
        "test.world.write",
        1,
        RiskLevel.WRITE_WORLD,
        "Test write",
        CanonicalArguments.freeze(arguments),
        NOW,
        expiresAt);
  }
}
