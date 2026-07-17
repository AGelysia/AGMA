package dev.minecraftagent.paper.proposal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import dev.minecraftagent.paper.lifecycle.AgentState;
import dev.minecraftagent.paper.lifecycle.OfflineReason;
import dev.minecraftagent.paper.lifecycle.OperationalGate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ProposalServiceTest {
  private static final String SERVER_ID = "paper-test";
  private static final String TOOL_ID = "test.world.write";
  private static final String PERMISSION = "minecraftagent.write.world";
  private static final UUID REQUEST_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID SESSION_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID PLAYER_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final UUID PROPOSAL_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");
  private static final Instant NOW = Instant.parse("2026-07-13T00:00:00Z");

  private final MutableClock clock = new MutableClock(NOW);
  private final OperationalGate gate = new OperationalGate(AgentState.ONLINE);
  private final MutablePlayers players = new MutablePlayers();
  private final AtomicReference<ProposalAuthorizer.Policy> authorizationPolicy =
      new AtomicReference<>(
          new ProposalAuthorizer.Policy(
              Set.of(), ProposalAuthorizer.WriteAccess.OP, ProposalAuthorizer.WriteAccess.OP));
  private final AtomicBoolean contextActive = new AtomicBoolean(true);
  private final Set<ProposalContextPolicy.Context> activeContexts = ConcurrentHashMap.newKeySet();
  private final AtomicBoolean guardValid = new AtomicBoolean(true);
  private final AtomicInteger executions = new AtomicInteger();
  private final CopyOnWriteArrayList<ProposalAuditEvent> auditEvents = new CopyOnWriteArrayList<>();
  private final AtomicReference<ProposalAuditEvent.Type> failingAuditType = new AtomicReference<>();
  private final AtomicBoolean expireDuringIntentAudit = new AtomicBoolean();
  private final AtomicReference<TypedProposalTool<?>> currentTool = new AtomicReference<>();
  private ProposalRepository repository;
  private ProposalService service;

  @BeforeEach
  void setUp() {
    players.online.add(PLAYER_ID);
    players.operators.add(PLAYER_ID);
    players.permissions.add(PLAYER_ID);
    currentTool.set(tool(7));
    repository = new InMemoryProposalRepository();
    service = service(repository);
  }

  @Test
  void confirmsOnceAndPassesOnlyDecodedArgumentsToTheTypedExecutor() {
    var proposal = create().proposal();
    assertNotNull(proposal);

    var result = service.confirm(proposal.proposalId(), PLAYER_ID);

    assertEquals(ProposalConfirmationResult.Status.EXECUTED, result.status());
    assertEquals("WORLD_WRITE_APPLIED", result.code());
    assertEquals(1, executions.get());
    assertEquals(
        ProposalRepository.State.EXECUTED,
        repository.find(proposal.proposalId()).orElseThrow().state());
  }

  @Test
  void concurrentDoubleConfirmationExecutesExactlyOnce() throws Exception {
    var proposalId = create().proposal().proposalId();
    var start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var first = executor.submit(() -> confirmAfter(start, proposalId));
      var second = executor.submit(() -> confirmAfter(start, proposalId));
      start.countDown();

      var results = List.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));
      assertEquals(
          1,
          results.stream()
              .filter(result -> result.status() == ProposalConfirmationResult.Status.EXECUTED)
              .count());
      assertEquals(1, executions.get());
    }
  }

  @Test
  void liveOpAndPermissionAreRecheckedAfterCreation() {
    var deopped = create().proposal().proposalId();
    players.operators.remove(PLAYER_ID);
    assertEquals("OP_REQUIRED", service.confirm(deopped, PLAYER_ID).code());
    assertEquals(0, executions.get());

    players.operators.add(PLAYER_ID);
    var permissionRevoked = create().proposal().proposalId();
    players.permissions.remove(PLAYER_ID);
    assertEquals("PERMISSION_REQUIRED", service.confirm(permissionRevoked, PLAYER_ID).code());
    assertEquals(0, executions.get());
  }

  @Test
  void cancelledRequestContextCannotExecuteAnOldProposal() {
    var proposalId = create().proposal().proposalId();
    contextActive.set(false);

    assertEquals("REQUEST_CONTEXT_INVALID", service.confirm(proposalId, PLAYER_ID).code());
    assertEquals(0, executions.get());
  }

  @Test
  void wrongPlayerCannotConsumeTheOwnersProposal() {
    var proposalId = create().proposal().proposalId();
    var attacker = UUID.randomUUID();

    assertEquals("PLAYER_MISMATCH", service.confirm(proposalId, attacker).code());
    assertEquals(
        ProposalConfirmationResult.Status.EXECUTED,
        service.confirm(proposalId, PLAYER_ID).status());
  }

  @Test
  void expiredProposalIsClaimedButNeverExecuted() {
    var proposalId = create().proposal().proposalId();
    clock.advance(Duration.ofMinutes(2));

    assertEquals("PROPOSAL_EXPIRED", service.confirm(proposalId, PLAYER_ID).code());
    assertEquals(0, executions.get());
  }

  @Test
  void proposalThatExpiresDuringDurableIntentAuditCannotExecute() {
    var proposalId = create().proposal().proposalId();
    expireDuringIntentAudit.set(true);

    assertEquals("PROPOSAL_EXPIRED", service.confirm(proposalId, PLAYER_ID).code());
    assertEquals(0, executions.get());
    assertEquals(
        ProposalRepository.State.REJECTED, repository.find(proposalId).orElseThrow().state());
  }

  @Test
  void offlineCleanupInvalidatesEveryProposal() {
    var first = create().proposal().proposalId();
    var second = create().proposal().proposalId();

    gate.transitionTo(AgentState.OFFLINE);
    service.quiesce(gate.epoch(), OfflineReason.MANUAL);
    assertEquals(
        ProposalRepository.State.INVALIDATED, repository.find(first).orElseThrow().state());
    assertEquals(
        ProposalRepository.State.INVALIDATED, repository.find(second).orElseThrow().state());

    gate.transitionTo(AgentState.ONLINE);
    assertEquals("PROPOSAL_ALREADY_HANDLED", service.confirm(first, PLAYER_ID).code());
    assertEquals(0, executions.get());
  }

  @Test
  void playerQuitInvalidatesOnlyThatPlayersProposals() {
    var first = create().proposal().proposalId();
    var secondPlayer = UUID.randomUUID();
    players.online.add(secondPlayer);
    players.operators.add(secondPlayer);
    players.permissions.add(secondPlayer);
    var secondRequest = request(secondPlayer, UUID.randomUUID(), UUID.randomUUID());
    var second = service.create(secondRequest).proposal().proposalId();

    service.invalidatePlayer(PLAYER_ID);

    assertEquals(
        ProposalRepository.State.INVALIDATED, repository.find(first).orElseThrow().state());
    assertEquals(ProposalRepository.State.PENDING, repository.find(second).orElseThrow().state());
  }

  @Test
  void mismatchedClaimedHashIsRejectedBeforeStorage() {
    var request = request(PLAYER_ID, REQUEST_ID, SESSION_ID);
    var mismatched =
        new ProposalRequest(
            request.serverId(),
            request.requestId(),
            request.sessionId(),
            request.playerUuid(),
            request.tool(),
            request.arguments(),
            "0".repeat(64));

    assertEquals("ARGUMENT_HASH_MISMATCH", service.create(mismatched).code());
    assertEquals(0, auditEvents.size());
  }

  @Test
  void persistedArgumentTamperingFailsTheHashCheck() {
    var tampering = new TamperingRepository(new InMemoryProposalRepository());
    repository = tampering;
    service = service(tampering);
    var proposalId = create().proposal().proposalId();
    tampering.tamper.set(true);

    assertEquals("ARGUMENT_HASH_MISMATCH", service.confirm(proposalId, PLAYER_ID).code());
    assertEquals(0, executions.get());
  }

  @Test
  void catalogGenerationAndExecutionGuardAreRechecked() {
    var staleCatalog = create().proposal().proposalId();
    currentTool.set(tool(8));
    assertEquals("TOOL_POLICY_CHANGED", service.confirm(staleCatalog, PLAYER_ID).code());

    currentTool.set(tool(7));
    var staleWorld = create().proposal().proposalId();
    guardValid.set(false);
    assertEquals("WORLD_STATE_CHANGED", service.confirm(staleWorld, PLAYER_ID).code());
    assertEquals(0, executions.get());
  }

  @Test
  void auditIntentFailurePreventsExecutionAndPermanentlyFailsClosed() {
    var proposalId = create().proposal().proposalId();
    failingAuditType.set(ProposalAuditEvent.Type.CLAIM_AUTHORIZED);

    assertEquals("AUDIT_UNAVAILABLE", service.confirm(proposalId, PLAYER_ID).code());
    assertEquals(0, executions.get());
    assertEquals("AUDIT_UNAVAILABLE", service.create(request()).code());
  }

  @Test
  void postExecutionAuditFailureTripsTheCircuitForFutureWork() {
    var proposalId = create().proposal().proposalId();
    failingAuditType.set(ProposalAuditEvent.Type.EXECUTED);

    assertEquals("EXECUTED_AUDIT_INCOMPLETE", service.confirm(proposalId, PLAYER_ID).code());
    assertEquals(1, executions.get());
    assertEquals("AUDIT_UNAVAILABLE", service.create(request()).code());
  }

  @Test
  void structuredAuditAndDebugStringsNeverContainSensitiveArgumentsOrTheirHash() {
    var request = request();
    var secret = request.arguments().get("secret").getAsString();
    var argumentHash = request.argumentHash();
    var proposalId = service.create(request).proposal().proposalId();
    service.confirm(proposalId, PLAYER_ID);

    var serialized = auditEvents.stream().map(ProposalAuditEvent::toJson).toList().toString();
    assertFalse(serialized.contains(secret));
    assertFalse(serialized.contains(argumentHash));
    assertFalse(request.toString().contains(secret));
    assertFalse(request.toString().contains(argumentHash));
    var componentNames =
        java.util.Arrays.stream(ProposalAuditEvent.class.getRecordComponents())
            .map(java.lang.reflect.RecordComponent::getName)
            .toList();
    assertFalse(componentNames.contains("arguments"));
    assertFalse(componentNames.contains("summary"));
    assertFalse(componentNames.contains("argumentHash"));
  }

  @Test
  void unsafeDisplayNameIsRejectedBeforeItCanReachAdventure() {
    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalArgumentException.class,
        () ->
            new TypedProposalTool<>(
                TOOL_ID,
                1,
                RiskLevel.WRITE_WORLD,
                PERMISSION,
                "Apply\nclick injection",
                ignored -> TypedProposalTool.DecodeResult.decoded(1),
                (context, arguments) -> TypedProposalTool.Decision.valid(),
                (context, arguments) -> TypedProposalTool.ExecutionResult.succeeded("DONE")));
  }

  private ProposalService service(ProposalRepository proposalRepository) {
    var authorizer = new ProposalAuthorizer(authorizationPolicy::get, players);
    ProposalContextPolicy contexts =
        context -> contextActive.get() && activeContexts.contains(context);
    ProposalAuditSink audit =
        event -> {
          if (event.type() == failingAuditType.get()) {
            throw new IllegalStateException("secret exception text must not be retained");
          }
          auditEvents.add(event);
          if (event.type() == ProposalAuditEvent.Type.CLAIM_AUTHORIZED
              && expireDuringIntentAudit.get()) {
            clock.advance(Duration.ofMinutes(2));
          }
        };
    return new ProposalService(
        SERVER_ID,
        proposalRepository,
        id -> TOOL_ID.equals(id) ? Optional.ofNullable(currentTool.get()) : Optional.empty(),
        authorizer,
        contexts,
        audit,
        gate,
        clock,
        Duration.ofMinutes(1),
        new IncrementingIds(PROPOSAL_ID));
  }

  private ProposalCreationResult create() {
    var result = service.create(request());
    assertEquals(ProposalCreationResult.Status.CREATED, result.status(), result.code());
    return result;
  }

  private ProposalRequest request() {
    return request(PLAYER_ID, REQUEST_ID, SESSION_ID);
  }

  private ProposalRequest request(UUID playerUuid, UUID requestId, UUID sessionId) {
    var arguments = new JsonObject();
    arguments.addProperty("amount", 2);
    arguments.addProperty("secret", "token-do-not-audit");
    var request =
        new ProposalRequest(
            SERVER_ID,
            requestId,
            sessionId,
            playerUuid,
            TOOL_ID,
            arguments,
            CanonicalArguments.hash(arguments));
    activeContexts.add(
        new ProposalContextPolicy.Context(requestId, sessionId, playerUuid, TOOL_ID, 7));
    return request;
  }

  private TypedProposalTool<WorldWrite> tool(long generation) {
    return new TypedProposalTool<>(
        TOOL_ID,
        generation,
        RiskLevel.WRITE_WORLD,
        PERMISSION,
        "Apply bounded world change",
        arguments -> {
          if (arguments.size() != 2
              || !arguments.has("amount")
              || !arguments.get("amount").isJsonPrimitive()
              || !arguments.has("secret")
              || !arguments.get("secret").isJsonPrimitive()) {
            return TypedProposalTool.DecodeResult.rejected("ARGUMENTS_INVALID");
          }
          return TypedProposalTool.DecodeResult.decoded(
              new WorldWrite(
                  arguments.get("amount").getAsInt(), arguments.get("secret").getAsString()));
        },
        (context, arguments) ->
            guardValid.get()
                ? TypedProposalTool.Decision.valid()
                : TypedProposalTool.Decision.rejected("WORLD_STATE_CHANGED"),
        (context, arguments) -> {
          assertEquals(2, arguments.amount());
          assertEquals("token-do-not-audit", arguments.secret());
          assertEquals(
              ProposalRepository.State.EXECUTING,
              repository.find(context.proposalId()).orElseThrow().state());
          executions.incrementAndGet();
          return TypedProposalTool.ExecutionResult.succeeded("WORLD_WRITE_APPLIED");
        });
  }

  private ProposalConfirmationResult confirmAfter(CountDownLatch start, UUID proposalId)
      throws InterruptedException {
    assertTrue(start.await(5, TimeUnit.SECONDS));
    return service.confirm(proposalId, PLAYER_ID);
  }

  private record WorldWrite(int amount, String secret) {}

  private static final class MutablePlayers implements ProposalAuthorizer.LivePlayerPolicy {
    private final Set<UUID> online = ConcurrentHashMap.newKeySet();
    private final Set<UUID> operators = ConcurrentHashMap.newKeySet();
    private final Set<UUID> permissions = ConcurrentHashMap.newKeySet();

    @Override
    public boolean isOnline(UUID playerUuid) {
      return online.contains(playerUuid);
    }

    @Override
    public boolean isOperator(UUID playerUuid) {
      return operators.contains(playerUuid);
    }

    @Override
    public boolean hasPermission(UUID playerUuid, String permission) {
      return permissions.contains(playerUuid);
    }
  }

  private static final class MutableClock extends Clock {
    private final AtomicReference<Instant> now;

    private MutableClock(Instant now) {
      this.now = new AtomicReference<>(now);
    }

    private void advance(Duration duration) {
      now.updateAndGet(current -> current.plus(duration));
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now.get();
    }
  }

  private static final class IncrementingIds implements java.util.function.Supplier<UUID> {
    private final AtomicReference<UUID> next;

    private IncrementingIds(UUID first) {
      next = new AtomicReference<>(first);
    }

    @Override
    public UUID get() {
      return next.getAndUpdate(
          current ->
              new UUID(current.getMostSignificantBits(), current.getLeastSignificantBits() + 1));
    }
  }

  private static final class TamperingRepository implements ProposalRepository {
    private final ProposalRepository delegate;
    private final AtomicBoolean tamper = new AtomicBoolean();

    private TamperingRepository(ProposalRepository delegate) {
      this.delegate = delegate;
    }

    @Override
    public boolean insert(StoredProposal proposal) {
      return delegate.insert(proposal);
    }

    @Override
    public Optional<Snapshot> find(UUID proposalId) {
      return delegate.find(proposalId).map(this::maybeTamper);
    }

    @Override
    public boolean transition(UUID proposalId, State expected, State updated) {
      return delegate.transition(proposalId, expected, updated);
    }

    @Override
    public List<StoredProposal> invalidateAll() {
      return delegate.invalidateAll();
    }

    @Override
    public List<StoredProposal> invalidatePlayer(UUID playerUuid) {
      return delegate.invalidatePlayer(playerUuid);
    }

    @Override
    public void prune(Instant now) {
      delegate.prune(now);
    }

    private Snapshot maybeTamper(Snapshot snapshot) {
      if (!tamper.get()) {
        return snapshot;
      }
      var original = snapshot.proposal();
      var changed =
          new StoredProposal(
              original.proposalId(),
              original.serverId(),
              original.requestId(),
              original.sessionId(),
              original.playerUuid(),
              original.tool(),
              original.catalogGeneration(),
              original.risk(),
              original.displayName(),
              CanonicalArguments.fromPersisted(
                  "{\"amount\":999,\"secret\":\"changed\"}", original.arguments().sha256()),
              original.createdAt(),
              original.expiresAt());
      return new Snapshot(changed, snapshot.state());
    }
  }
}
