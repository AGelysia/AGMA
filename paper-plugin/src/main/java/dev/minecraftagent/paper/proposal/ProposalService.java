package dev.minecraftagent.paper.proposal;

import dev.minecraftagent.paper.lifecycle.OfflineCleanup;
import dev.minecraftagent.paper.lifecycle.OfflineReason;
import dev.minecraftagent.paper.lifecycle.OperationalGate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** Owns proposal identity, expiry, atomic claims, live reauthorization, and redacted auditing. */
public final class ProposalService implements OfflineCleanup.Control {
  private static final Duration MAX_TTL = Duration.ofMinutes(10);
  private static final int ID_ATTEMPTS = 4;

  private final String serverId;
  private final ProposalRepository repository;
  private final ProposalToolCatalog catalog;
  private final ProposalAuthorizer authorizer;
  private final ProposalContextPolicy contextPolicy;
  private final ProposalAuditSink audit;
  private final OperationalGate operationalGate;
  private final Clock clock;
  private final Duration ttl;
  private final Supplier<UUID> proposalIds;
  private final AtomicBoolean auditHealthy = new AtomicBoolean(true);

  public ProposalService(
      String serverId,
      ProposalRepository repository,
      ProposalToolCatalog catalog,
      ProposalAuthorizer authorizer,
      ProposalContextPolicy contextPolicy,
      ProposalAuditSink audit,
      OperationalGate operationalGate,
      Clock clock,
      Duration ttl) {
    this(
        serverId,
        repository,
        catalog,
        authorizer,
        contextPolicy,
        audit,
        operationalGate,
        clock,
        ttl,
        UUID::randomUUID);
  }

  public ProposalService(
      String serverId,
      ProposalRepository repository,
      ProposalToolCatalog catalog,
      ProposalAuthorizer authorizer,
      ProposalContextPolicy contextPolicy,
      ProposalAuditSink audit,
      OperationalGate operationalGate,
      Clock clock,
      Duration ttl,
      Supplier<UUID> proposalIds) {
    this.serverId = Objects.requireNonNull(serverId);
    this.repository = Objects.requireNonNull(repository);
    this.catalog = Objects.requireNonNull(catalog);
    this.authorizer = Objects.requireNonNull(authorizer);
    this.contextPolicy = Objects.requireNonNull(contextPolicy);
    this.audit = Objects.requireNonNull(audit);
    this.operationalGate = Objects.requireNonNull(operationalGate);
    this.clock = Objects.requireNonNull(clock);
    this.ttl = Objects.requireNonNull(ttl);
    this.proposalIds = Objects.requireNonNull(proposalIds);
    if (serverId.isBlank() || serverId.length() > 64) {
      throw new IllegalArgumentException("Invalid server id");
    }
    if (ttl.isNegative() || ttl.isZero() || ttl.compareTo(MAX_TTL) > 0) {
      throw new IllegalArgumentException("Proposal TTL must be between zero and ten minutes");
    }
  }

  public ProposalCreationResult create(ProposalRequest request) {
    Objects.requireNonNull(request);
    if (!auditHealthy.get()) {
      return ProposalCreationResult.rejected("AUDIT_UNAVAILABLE");
    }
    var permit = operationalGate.tryAcquire();
    if (permit.isEmpty()) {
      return ProposalCreationResult.rejected("AI_OFFLINE");
    }
    if (!operationalGate.revalidate(permit.orElseThrow())) {
      return ProposalCreationResult.rejected("AI_OFFLINE");
    }
    if (!serverId.equals(request.serverId())) {
      return ProposalCreationResult.rejected("SERVER_MISMATCH");
    }
    final CanonicalArguments.Frozen frozen;
    try {
      frozen = CanonicalArguments.freeze(request.arguments());
    } catch (RuntimeException error) {
      return ProposalCreationResult.rejected("ARGUMENTS_INVALID");
    }
    if (!CanonicalArguments.hashesEqual(request.argumentHash(), frozen.sha256())) {
      return ProposalCreationResult.rejected("ARGUMENT_HASH_MISMATCH");
    }
    var tool = catalog.find(request.tool()).orElse(null);
    if (tool == null || !request.tool().equals(tool.id())) {
      return ProposalCreationResult.rejected("TOOL_UNAVAILABLE");
    }
    if (!contextActive(request, tool.catalogGeneration())) {
      return ProposalCreationResult.rejected("REQUEST_CONTEXT_INVALID");
    }
    var authorization =
        authorizer.authorize(request.playerUuid(), tool.risk(), tool.requiredPermission());
    if (!authorization.allowed()) {
      return ProposalCreationResult.rejected(authorization.code());
    }
    var argumentRejection = tool.validate(request.arguments());
    if (argumentRejection != null) {
      return ProposalCreationResult.rejected(argumentRejection);
    }
    var now = clock.instant();
    repository.prune(now);
    var proposal = insert(request, tool, frozen, now, now.plus(ttl), permit.orElseThrow());
    if (proposal == null) {
      return ProposalCreationResult.rejected("PROPOSAL_ID_UNAVAILABLE");
    }
    if (!appendAudit(event(proposal, ProposalAuditEvent.Type.CREATED, now, "PROPOSAL_CREATED"))) {
      repository.transition(
          proposal.proposalId(),
          ProposalRepository.State.PENDING,
          ProposalRepository.State.INVALIDATED);
      return ProposalCreationResult.rejected("AUDIT_UNAVAILABLE");
    }
    return ProposalCreationResult.created(ProposalView.from(proposal));
  }

  public ProposalConfirmationResult confirm(UUID proposalId, UUID actualPlayerUuid) {
    Objects.requireNonNull(proposalId);
    Objects.requireNonNull(actualPlayerUuid);
    if (!auditHealthy.get()) {
      return ProposalConfirmationResult.rejected("AUDIT_UNAVAILABLE");
    }
    var permit = operationalGate.tryAcquire();
    if (permit.isEmpty()) {
      return ProposalConfirmationResult.rejected("AI_OFFLINE");
    }
    if (!operationalGate.revalidate(permit.orElseThrow())) {
      return ProposalConfirmationResult.rejected("AI_OFFLINE");
    }
    var initial = repository.find(proposalId).orElse(null);
    if (initial == null) {
      return ProposalConfirmationResult.rejected("PROPOSAL_NOT_FOUND");
    }
    if (!initial.proposal().playerUuid().equals(actualPlayerUuid)) {
      return ProposalConfirmationResult.rejected("PLAYER_MISMATCH");
    }
    if (!repository.transition(
        proposalId, ProposalRepository.State.PENDING, ProposalRepository.State.CLAIMED)) {
      return ProposalConfirmationResult.rejected("PROPOSAL_ALREADY_HANDLED");
    }
    var claimed = repository.find(proposalId).orElse(null);
    if (claimed == null || claimed.state() != ProposalRepository.State.CLAIMED) {
      return ProposalConfirmationResult.rejected("PROPOSAL_INVALIDATED");
    }
    return executeClaimed(claimed.proposal(), actualPlayerUuid, permit.orElseThrow());
  }

  public ProposalConfirmationResult reject(UUID proposalId, UUID actualPlayerUuid) {
    Objects.requireNonNull(proposalId);
    Objects.requireNonNull(actualPlayerUuid);
    if (!auditHealthy.get()) {
      return ProposalConfirmationResult.rejected("AUDIT_UNAVAILABLE");
    }
    if (operationalGate.tryAcquire().isEmpty()) {
      return ProposalConfirmationResult.rejected("AI_OFFLINE");
    }
    var snapshot = repository.find(proposalId).orElse(null);
    if (snapshot == null) {
      return ProposalConfirmationResult.rejected("PROPOSAL_NOT_FOUND");
    }
    if (!snapshot.proposal().playerUuid().equals(actualPlayerUuid)) {
      return ProposalConfirmationResult.rejected("PLAYER_MISMATCH");
    }
    if (!repository.transition(
        proposalId, ProposalRepository.State.PENDING, ProposalRepository.State.REJECTED)) {
      return ProposalConfirmationResult.rejected("PROPOSAL_ALREADY_HANDLED");
    }
    if (!appendAudit(
        event(
            snapshot.proposal(),
            ProposalAuditEvent.Type.REJECTED,
            clock.instant(),
            "PLAYER_REJECTED"))) {
      return ProposalConfirmationResult.failed("AUDIT_UNAVAILABLE");
    }
    return ProposalConfirmationResult.rejected("PLAYER_REJECTED");
  }

  /** Invalidates all proposals owned by a player on quit. */
  public void invalidatePlayer(UUID playerUuid) {
    Objects.requireNonNull(playerUuid);
    auditInvalidations(repository.invalidatePlayer(playerUuid), "PLAYER_OFFLINE");
  }

  /** Adapter for the proposal slot of {@link dev.minecraftagent.paper.lifecycle.OfflineCleanup}. */
  @Override
  public void quiesce(long epoch, OfflineReason reason) {
    Objects.requireNonNull(reason);
    auditInvalidations(repository.invalidateAll(), "AGENT_OFFLINE");
  }

  private StoredProposal insert(
      ProposalRequest request,
      TypedProposalTool<?> tool,
      CanonicalArguments.Frozen arguments,
      Instant createdAt,
      Instant expiresAt,
      OperationalGate.Permit permit) {
    for (int attempt = 0; attempt < ID_ATTEMPTS; attempt++) {
      if (!operationalGate.revalidate(permit)) {
        return null;
      }
      var proposal =
          new StoredProposal(
              proposalIds.get(),
              serverId,
              request.requestId(),
              request.sessionId(),
              request.playerUuid(),
              tool.id(),
              tool.catalogGeneration(),
              tool.risk(),
              tool.displayName(),
              arguments,
              createdAt,
              expiresAt);
      if (repository.insert(proposal)) {
        if (operationalGate.revalidate(permit)
            && contextActive(request, tool.catalogGeneration())) {
          return proposal;
        }
        repository.transition(
            proposal.proposalId(),
            ProposalRepository.State.PENDING,
            ProposalRepository.State.INVALIDATED);
        return null;
      }
    }
    return null;
  }

  private ProposalConfirmationResult executeClaimed(
      StoredProposal proposal, UUID actualPlayerUuid, OperationalGate.Permit permit) {
    var now = clock.instant();
    if (!serverId.equals(proposal.serverId())) {
      return rejectClaimed(proposal, "SERVER_MISMATCH", now);
    }
    if (!now.isBefore(proposal.expiresAt())) {
      return rejectClaimed(proposal, "PROPOSAL_EXPIRED", now);
    }
    if (!proposal.arguments().verified()) {
      return rejectClaimed(proposal, "ARGUMENT_HASH_MISMATCH", now);
    }
    var tool = currentTool(proposal);
    if (tool == null) {
      return rejectClaimed(proposal, "TOOL_POLICY_CHANGED", now);
    }
    if (!contextActive(proposal)) {
      return rejectClaimed(proposal, "REQUEST_CONTEXT_INVALID", now);
    }
    var authorization =
        authorizer.authorize(actualPlayerUuid, tool.risk(), tool.requiredPermission());
    if (!authorization.allowed()) {
      return rejectClaimed(proposal, authorization.code(), now);
    }
    if (!operationalGate.revalidate(permit)) {
      return rejectClaimed(proposal, "AI_OFFLINE", now);
    }

    // The durable intent event is the final fail-closed boundary before any side effect.
    if (!appendAudit(
        event(proposal, ProposalAuditEvent.Type.CLAIM_AUTHORIZED, now, "EXECUTION_AUTHORIZED"))) {
      return rejectClaimed(proposal, "AUDIT_UNAVAILABLE", now);
    }

    now = clock.instant();
    if (!now.isBefore(proposal.expiresAt())) {
      return rejectClaimed(proposal, "PROPOSAL_EXPIRED", now);
    }
    tool = currentTool(proposal);
    authorization =
        tool == null
            ? ProposalAuthorizer.Decision.rejected("TOOL_POLICY_CHANGED")
            : authorizer.authorize(actualPlayerUuid, tool.risk(), tool.requiredPermission());
    if (tool == null || !authorization.allowed()) {
      return rejectClaimed(
          proposal, tool == null ? "TOOL_POLICY_CHANGED" : authorization.code(), clock.instant());
    }
    if (!contextActive(proposal)) {
      return rejectClaimed(proposal, "REQUEST_CONTEXT_INVALID", clock.instant());
    }
    if (!auditHealthy.get()) {
      return rejectClaimed(proposal, "AUDIT_UNAVAILABLE", clock.instant());
    }
    if (!operationalGate.revalidate(permit)) {
      return rejectClaimed(proposal, "AI_OFFLINE", clock.instant());
    }
    if (!repository.transition(
        proposal.proposalId(),
        ProposalRepository.State.CLAIMED,
        ProposalRepository.State.EXECUTING)) {
      return ProposalConfirmationResult.rejected("PROPOSAL_INVALIDATED");
    }
    var executionTime = clock.instant();
    if (!executionTime.isBefore(proposal.expiresAt())) {
      return rejectExecuting(proposal, "PROPOSAL_EXPIRED", executionTime);
    }
    if (!operationalGate.revalidate(permit)) {
      return rejectExecuting(proposal, "AI_OFFLINE", executionTime);
    }

    var context =
        new TypedProposalTool.ExecutionContext(
            proposal.serverId(),
            proposal.requestId(),
            proposal.sessionId(),
            proposal.proposalId(),
            actualPlayerUuid,
            proposal.arguments().sha256(),
            proposal.createdAt(),
            proposal.expiresAt(),
            executionTime);
    var result = tool.execute(context, proposal.arguments().arguments());
    var terminal = terminalState(result.status());
    if (!repository.transition(
        proposal.proposalId(), ProposalRepository.State.EXECUTING, terminal)) {
      return ProposalConfirmationResult.rejected("PROPOSAL_INVALIDATED");
    }
    boolean auditComplete =
        appendAudit(
            event(proposal, ProposalAuditEvent.Type.EXECUTED, clock.instant(), result.code()));
    if (result.status() == TypedProposalTool.ExecutionResult.Status.SUCCEEDED) {
      return ProposalConfirmationResult.executed(
          auditComplete ? result.code() : "EXECUTED_AUDIT_INCOMPLETE");
    }
    return result.status() == TypedProposalTool.ExecutionResult.Status.REJECTED
        ? ProposalConfirmationResult.rejected(result.code())
        : ProposalConfirmationResult.failed(result.code());
  }

  private TypedProposalTool<?> currentTool(StoredProposal proposal) {
    var current = catalog.find(proposal.tool()).orElse(null);
    return current != null
            && current.id().equals(proposal.tool())
            && current.catalogGeneration() == proposal.catalogGeneration()
            && current.risk() == proposal.risk()
        ? current
        : null;
  }

  private ProposalConfirmationResult rejectClaimed(
      StoredProposal proposal, String code, Instant timestamp) {
    repository.transition(
        proposal.proposalId(), ProposalRepository.State.CLAIMED, ProposalRepository.State.REJECTED);
    appendAudit(event(proposal, ProposalAuditEvent.Type.REJECTED, timestamp, code));
    return ProposalConfirmationResult.rejected(code);
  }

  private ProposalConfirmationResult rejectExecuting(
      StoredProposal proposal, String code, Instant timestamp) {
    repository.transition(
        proposal.proposalId(),
        ProposalRepository.State.EXECUTING,
        ProposalRepository.State.REJECTED);
    appendAudit(event(proposal, ProposalAuditEvent.Type.REJECTED, timestamp, code));
    return ProposalConfirmationResult.rejected(code);
  }

  private void auditInvalidations(List<StoredProposal> proposals, String code) {
    boolean failed = false;
    var now = clock.instant();
    for (var proposal : proposals) {
      if (!appendAudit(event(proposal, ProposalAuditEvent.Type.INVALIDATED, now, code))) {
        failed = true;
      }
    }
    if (failed) {
      throw new IllegalStateException("Proposal invalidation audit unavailable");
    }
  }

  private static ProposalRepository.State terminalState(
      TypedProposalTool.ExecutionResult.Status status) {
    return switch (status) {
      case SUCCEEDED -> ProposalRepository.State.EXECUTED;
      case REJECTED -> ProposalRepository.State.REJECTED;
      case FAILED -> ProposalRepository.State.FAILED;
    };
  }

  private static ProposalAuditEvent event(
      StoredProposal proposal,
      ProposalAuditEvent.Type type,
      Instant timestamp,
      String outcomeCode) {
    return new ProposalAuditEvent(
        type,
        timestamp,
        proposal.proposalId(),
        proposal.requestId(),
        proposal.playerUuid(),
        proposal.tool(),
        proposal.risk(),
        proposal.catalogGeneration(),
        outcomeCode);
  }

  private synchronized boolean appendAudit(ProposalAuditEvent event) {
    if (!auditHealthy.get()) {
      return false;
    }
    try {
      audit.append(event);
      return true;
    } catch (RuntimeException error) {
      auditHealthy.set(false);
      return false;
    }
  }

  private boolean contextActive(ProposalRequest request, long catalogGeneration) {
    try {
      return contextPolicy.isActive(
          new ProposalContextPolicy.Context(
              request.requestId(),
              request.sessionId(),
              request.playerUuid(),
              request.tool(),
              catalogGeneration));
    } catch (RuntimeException error) {
      return false;
    }
  }

  private boolean contextActive(StoredProposal proposal) {
    try {
      return contextPolicy.isActive(
          new ProposalContextPolicy.Context(
              proposal.requestId(),
              proposal.sessionId(),
              proposal.playerUuid(),
              proposal.tool(),
              proposal.catalogGeneration()));
    } catch (RuntimeException error) {
      return false;
    }
  }
}
