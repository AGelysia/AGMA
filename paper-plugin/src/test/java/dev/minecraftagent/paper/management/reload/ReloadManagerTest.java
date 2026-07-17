package dev.minecraftagent.paper.management.reload;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.minecraftagent.paper.capability.model.CapabilityApproval;
import dev.minecraftagent.paper.lifecycle.AgentState;
import dev.minecraftagent.paper.lifecycle.OperationalGate;
import dev.minecraftagent.paper.proposal.ProposalAuthorizer;
import dev.minecraftagent.paper.runtime.RuntimeDeploymentMode;
import dev.minecraftagent.paper.startup.PaperStartupConfig;
import dev.minecraftagent.paper.startup.SecurityPolicy;
import dev.minecraftagent.paper.startup.StartupFailure;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class ReloadManagerTest {
  private static final UUID OWNER = UUID.fromString("11111111-1111-4111-8111-111111111111");

  @Test
  void publishesOwnersAndSecurityAsOneImmutableGeneration() {
    var worker = new QueuedExecutor();
    var changed = new TestConfig();
    changed.owners = Set.of(OWNER);
    changed.securityPolicy =
        new SecurityPolicy(
            1,
            SecurityPolicy.AccessLevel.OWNER,
            SecurityPolicy.AccessLevel.OP,
            SecurityPolicy.AccessLevel.OWNER,
            true);
    var manager = new ReloadManager(worker, () -> candidate(changed), new TestConfig().build());
    var initial = manager.snapshot();

    var request = manager.reload();

    assertEquals(ReloadManager.Disposition.STARTED, request.disposition());
    assertSame(initial, manager.snapshot());
    assertEquals(0, initial.generation());
    assertEquals(Set.of(), initial.owners());
    var concurrent = manager.reload();
    assertEquals(ReloadManager.Disposition.IN_PROGRESS, concurrent.disposition());
    assertEquals(
        ReloadResult.Code.RELOAD_IN_PROGRESS,
        concurrent.completion().toCompletableFuture().join().code());
    assertEquals(1, worker.size());

    worker.runNext();

    var result = request.completion().toCompletableFuture().join();
    assertEquals(ReloadResult.Status.APPLIED, result.status());
    assertEquals(ReloadResult.Code.RELOAD_APPLIED, result.code());
    assertEquals(1, result.generation());
    assertSame(result.snapshot(), manager.snapshot());
    assertEquals(Set.of(OWNER), result.snapshot().owners());
    assertEquals(SecurityPolicy.AccessLevel.OWNER, result.snapshot().securityPolicy().worldWrite());
    assertTrue(result.snapshot().adminPolicy().allowOpToggle());
    assertEquals(
        ProposalAuthorizer.WriteAccess.OWNER, result.snapshot().proposalPolicy().worldWrite());
    assertThrows(
        UnsupportedOperationException.class,
        () -> result.snapshot().owners().add(UUID.randomUUID()));
    assertFalse(result.snapshot().toString().contains(OWNER.toString()));
  }

  @Test
  void validUnchangedCandidateDoesNotAdvanceGeneration() {
    var startup = new TestConfig();
    var manager = new ReloadManager(Runnable::run, () -> candidate(startup), startup.build());
    var initial = manager.snapshot();

    var result = manager.reload().completion().toCompletableFuture().join();

    assertEquals(ReloadResult.Status.UNCHANGED, result.status());
    assertEquals(ReloadResult.Code.RELOAD_UNCHANGED, result.code());
    assertSame(initial, result.snapshot());
    assertSame(initial, manager.snapshot());
  }

  @Test
  void operationalEpochChangePreventsLatePolicyPublication() {
    var worker = new QueuedExecutor();
    var gate = new OperationalGate(AgentState.ONLINE);
    var changed = new TestConfig();
    changed.owners = Set.of(OWNER);
    var manager =
        new ReloadManager(worker, () -> candidate(changed), new TestConfig().build(), gate);
    var initial = manager.snapshot();

    var request = manager.reload();
    gate.transitionTo(AgentState.OFFLINE);
    worker.runNext();

    var result = request.completion().toCompletableFuture().join();
    assertEquals(ReloadResult.Status.STALE, result.status());
    assertEquals(ReloadResult.Code.RELOAD_STALE_COMPLETION, result.code());
    assertSame(initial, manager.snapshot());
    assertEquals(Set.of(), manager.snapshot().owners());
    assertEquals(ReloadManager.Disposition.NOT_ONLINE, manager.reload().disposition());
  }

  @Test
  void rejectsEveryRestartOnlyFieldWithoutReplacingPolicy() {
    var approval = new CapabilityApproval("example.reviewed", 1, "0123456789abcdef".repeat(4));
    var changes =
        List.of(
            change(
                ReloadResult.Code.RELOAD_RESTART_REQUIRED_SERVER_ID,
                config -> config.serverId = "creative-main"),
            change(
                ReloadResult.Code.RELOAD_RESTART_REQUIRED_RUNTIME_MODE,
                config -> config.runtimeMode = RuntimeDeploymentMode.MANAGED),
            change(
                ReloadResult.Code.RELOAD_RESTART_REQUIRED_RUNTIME_ENDPOINT,
                config -> config.runtimeEndpoint = URI.create("ws://127.0.0.1:38128/agent")),
            change(
                ReloadResult.Code.RELOAD_RESTART_REQUIRED_RUNTIME_TOKEN,
                config -> config.serverToken = "different-server-token-0123456789-ABCDEFGHIJKLMN"),
            change(
                ReloadResult.Code.RELOAD_RESTART_REQUIRED_RUNTIME_CONNECT_TIMEOUT,
                config -> config.connectTimeout = Duration.ofSeconds(3)),
            change(
                ReloadResult.Code.RELOAD_RESTART_REQUIRED_RUNTIME_HANDSHAKE_TIMEOUT,
                config -> config.handshakeTimeout = Duration.ofSeconds(3)),
            change(
                ReloadResult.Code.RELOAD_RESTART_REQUIRED_STATE_DIRECTORY,
                config -> config.stateDirectory = Path.of("/trusted/other-state")),
            change(
                ReloadResult.Code.RELOAD_RESTART_REQUIRED_CAPABILITY_DIRECTORY,
                config -> config.capabilityDirectory = Path.of("/trusted/other-capabilities")),
            change(
                ReloadResult.Code.RELOAD_RESTART_REQUIRED_CAPABILITY_APPROVALS,
                config -> config.capabilityApprovals = Set.of(approval)));

    for (var change : changes) {
      var startup = new TestConfig();
      var manager = new ReloadManager(Runnable::run, () -> change.candidate(), startup.build());
      var initial = manager.snapshot();

      var result = manager.reload().completion().toCompletableFuture().join();

      assertEquals(ReloadResult.Status.REJECTED, result.status());
      assertEquals(change.expected(), result.code());
      assertSame(initial, result.snapshot());
      assertSame(initial, manager.snapshot());
    }
  }

  @Test
  void strictConfigFailureRetainsOldSnapshotAndExposesOnlyStableCode() {
    var startup = new TestConfig();
    var manager =
        new ReloadManager(
            Runnable::run,
            () -> {
              throw new StartupFailure(
                  StartupFailure.Code.PAPER_CONFIG_INVALID, StartupFailure.Stage.CONFIG);
            },
            startup.build());
    var initial = manager.snapshot();

    var result = manager.reload().completion().toCompletableFuture().join();

    assertEquals(ReloadResult.Status.REJECTED, result.status());
    assertEquals(ReloadResult.Code.RELOAD_CONFIG_REJECTED, result.code());
    assertEquals(StartupFailure.Code.PAPER_CONFIG_INVALID, result.configFailure().orElseThrow());
    assertSame(initial, manager.snapshot());
  }

  @Test
  void independentlyValidatesCandidateSecurityPolicy() {
    var invalid = new TestConfig();
    invalid.securityPolicy =
        new SecurityPolicy(
            2,
            SecurityPolicy.AccessLevel.OP,
            SecurityPolicy.AccessLevel.OP,
            SecurityPolicy.AccessLevel.OWNER,
            false);
    var startup = new TestConfig();
    var manager = new ReloadManager(Runnable::run, () -> candidate(invalid), startup.build());

    var result = manager.reload().completion().toCompletableFuture().join();

    assertEquals(ReloadResult.Code.RELOAD_CONFIG_REJECTED, result.code());
    assertEquals(StartupFailure.Code.CORE_POLICY_INVALID, result.configFailure().orElseThrow());
    assertSame(manager.snapshot(), result.snapshot());
  }

  @Test
  void unexpectedCandidateFailureIsRedactedAndDoesNotPoisonNextReload() {
    var attempts = new java.util.concurrent.atomic.AtomicInteger();
    var startup = new TestConfig();
    var manager =
        new ReloadManager(
            Runnable::run,
            () -> {
              if (attempts.getAndIncrement() == 0) {
                throw new IllegalStateException("sensitive local detail");
              }
              return candidate(startup);
            },
            startup.build());
    var initial = manager.snapshot();

    var failed = manager.reload().completion().toCompletableFuture().join();
    var retried = manager.reload().completion().toCompletableFuture().join();

    assertEquals(ReloadResult.Code.RELOAD_CANDIDATE_LOAD_FAILED, failed.code());
    assertFalse(failed.toString().contains("sensitive local detail"));
    assertSame(initial, manager.snapshot());
    assertEquals(ReloadResult.Code.RELOAD_UNCHANGED, retried.code());
  }

  @Test
  void closeDuringCandidateLoadMakesCompletionStaleAndPreventsPublication() {
    var startup = new TestConfig();
    var changed = new TestConfig();
    changed.owners = Set.of(OWNER);
    var holder = new java.util.concurrent.atomic.AtomicReference<ReloadManager>();
    var manager =
        new ReloadManager(
            Runnable::run,
            () -> {
              holder.get().close();
              return candidate(changed);
            },
            startup.build());
    holder.set(manager);
    var initial = manager.snapshot();

    var request = manager.reload();
    var result = request.completion().toCompletableFuture().join();

    assertEquals(ReloadManager.Disposition.STARTED, request.disposition());
    assertEquals(ReloadResult.Status.STALE, result.status());
    assertEquals(ReloadResult.Code.RELOAD_STALE_COMPLETION, result.code());
    assertSame(initial, manager.snapshot());
    var afterClose = manager.reload();
    assertEquals(ReloadManager.Disposition.MANAGER_CLOSED, afterClose.disposition());
    assertEquals(
        ReloadResult.Code.RELOAD_MANAGER_CLOSED,
        afterClose.completion().toCompletableFuture().join().code());
  }

  @Test
  void rejectedWorkerReleasesAttemptAndRetainsSnapshot() {
    var startup = new TestConfig();
    var manager =
        new ReloadManager(
            ignored -> {
              throw new RejectedExecutionException("shutdown");
            },
            () -> candidate(startup),
            startup.build());
    var initial = manager.snapshot();

    var request = manager.reload();
    var result = request.completion().toCompletableFuture().join();

    assertEquals(ReloadManager.Disposition.WORKER_REJECTED, request.disposition());
    assertEquals(ReloadResult.Code.RELOAD_WORKER_REJECTED, result.code());
    assertSame(initial, manager.snapshot());
  }

  @Test
  void resultRejectsImpossibleStatusCodeAndConfigFailureCombinations() {
    var snapshot = candidate(new TestConfig()).policySnapshot(0);

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ReloadResult(
                ReloadResult.Status.APPLIED,
                ReloadResult.Code.RELOAD_UNCHANGED,
                snapshot,
                Optional.empty()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ReloadResult(
                ReloadResult.Status.REJECTED,
                ReloadResult.Code.RELOAD_CONFIG_REJECTED,
                snapshot,
                Optional.empty()));
  }

  private static ImmutableChange change(ReloadResult.Code expected, Consumer<TestConfig> mutation) {
    var config = new TestConfig();
    mutation.accept(config);
    return new ImmutableChange(expected, candidate(config));
  }

  private static ReloadCandidate candidate(TestConfig config) {
    return ReloadCandidate.from(config.build());
  }

  private record ImmutableChange(ReloadResult.Code expected, ReloadCandidate candidate) {}

  private static final class QueuedExecutor implements java.util.concurrent.Executor {
    private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();

    @Override
    public void execute(Runnable command) {
      tasks.add(command);
    }

    private int size() {
      return tasks.size();
    }

    private void runNext() {
      tasks.remove().run();
    }
  }

  private static final class TestConfig {
    private String serverId = "survival-main";
    private Set<UUID> owners = Set.of();
    private RuntimeDeploymentMode runtimeMode = RuntimeDeploymentMode.EXTERNAL;
    private URI runtimeEndpoint = URI.create("ws://127.0.0.1:38127/agent");
    private String serverToken = "test-server-token-0123456789-ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private Duration connectTimeout = Duration.ofSeconds(2);
    private Duration handshakeTimeout = Duration.ofSeconds(2);
    private Path stateDirectory = Path.of("/trusted/state");
    private SecurityPolicy securityPolicy =
        new SecurityPolicy(
            1,
            SecurityPolicy.AccessLevel.OP,
            SecurityPolicy.AccessLevel.OP,
            SecurityPolicy.AccessLevel.OWNER,
            false);
    private Path capabilityDirectory = Path.of("/trusted/capabilities");
    private Set<CapabilityApproval> capabilityApprovals = Set.of();

    private PaperStartupConfig build() {
      return new PaperStartupConfig(
          serverId,
          owners,
          new PaperStartupConfig.RuntimeSettings(
              runtimeMode, runtimeEndpoint, serverToken, connectTimeout, handshakeTimeout),
          stateDirectory,
          securityPolicy,
          capabilityDirectory,
          capabilityApprovals);
    }
  }
}
