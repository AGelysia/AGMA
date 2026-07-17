package dev.minecraftagent.paper.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.minecraftagent.paper.lifecycle.AgentHealth;
import dev.minecraftagent.paper.lifecycle.AgentState;
import dev.minecraftagent.paper.lifecycle.AgentStatus;
import dev.minecraftagent.paper.lifecycle.DesiredMode;
import dev.minecraftagent.paper.lifecycle.OfflineReason;
import dev.minecraftagent.paper.management.ManagementSnapshot;
import dev.minecraftagent.paper.request.AgentRequestGateway;
import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

class AgentCommandManagementTest {
  private static final String HASH = "a".repeat(64);

  @Test
  void offlineGateShortCircuitsEveryManagementOperationBeforeAuthorization() {
    var messages = new ArrayList<String>();
    var gateway = new RecordingManagement();
    var reloadAuthorizationCalls = new AtomicInteger();
    var command =
        command(
            offline(),
            gateway,
            ignored -> {
              reloadAuthorizationCalls.incrementAndGet();
              return true;
            },
            Runnable::run);
    var sender =
        sender(
            messages,
            Set.of(
                AgentCommand.STATUS_PERMISSION,
                AgentCommand.DOCTOR_PERMISSION,
                AgentCommand.RELOAD_PERMISSION,
                AgentCommand.CAPABILITIES_PERMISSION,
                AgentCommand.COSTS_PERMISSION,
                AgentCommand.UI_PERMISSION));

    for (var arguments :
        List.of(
            new String[] {"status"},
            new String[] {"doctor"},
            new String[] {"reload"},
            new String[] {"capabilities"},
            new String[] {"costs"},
            new String[] {"ui", "clear"})) {
      assertTrue(command.execute(sender, "agent", arguments));
    }

    assertEquals(
        List.of("AI offline", "AI offline", "AI offline", "AI offline", "AI offline", "AI offline"),
        messages);
    assertEquals(0, gateway.snapshotCalls);
    assertEquals(0, gateway.costCalls);
    assertEquals(0, gateway.reloadCalls);
    assertEquals(0, reloadAuthorizationCalls.get());
  }

  @Test
  void queryPermissionsAreIndependentAndReloadRequiresOwnerAuthorization() {
    var commands =
        Map.of(
            "status", AgentCommand.STATUS_PERMISSION,
            "doctor", AgentCommand.DOCTOR_PERMISSION,
            "capabilities", AgentCommand.CAPABILITIES_PERMISSION,
            "costs", AgentCommand.COSTS_PERMISSION);
    for (var entry : commands.entrySet()) {
      var deniedMessages = new ArrayList<String>();
      command(online(), new RecordingManagement(), ignored -> false, Runnable::run)
          .execute(sender(deniedMessages, Set.of()), "agent", new String[] {entry.getKey()});
      assertEquals(List.of("You do not have permission to use this command."), deniedMessages);

      var allowedMessages = new ArrayList<String>();
      command(online(), new RecordingManagement(), ignored -> false, Runnable::run)
          .execute(
              sender(allowedMessages, Set.of(entry.getValue())),
              "agent",
              new String[] {entry.getKey()});
      assertFalse(allowedMessages.contains("You do not have permission to use this command."));
    }

    var reloadMessages = new ArrayList<String>();
    command(online(), new RecordingManagement(), ignored -> false, Runnable::run)
        .execute(
            sender(reloadMessages, Set.of(AgentCommand.RELOAD_PERMISSION)),
            "agent",
            new String[] {"reload"});
    assertEquals(List.of("You do not have permission to use this command."), reloadMessages);
  }

  @Test
  void statusDoctorAndCapabilitiesRenderOnlyBoundedRedactedSnapshotData() {
    var messages = new ArrayList<String>();
    var gateway = new RecordingManagement();
    gateway.snapshot = snapshot();
    var command = command(online(), gateway, ignored -> false, Runnable::run);
    var sender =
        sender(
            messages,
            Set.of(
                AgentCommand.STATUS_PERMISSION,
                AgentCommand.DOCTOR_PERMISSION,
                AgentCommand.CAPABILITIES_PERMISSION));

    command.execute(sender, "agent", new String[] {"status"});
    command.execute(sender, "agent", new String[] {"doctor"});
    command.execute(sender, "agent", new String[] {"capabilities"});

    assertTrue(messages.contains("Runtime: CONNECTED"));
    assertTrue(messages.contains("Active requests: 2"));
    assertTrue(messages.contains("Client features: overlay@0=1,overlay@1=2,recipeView@2=2"));
    assertTrue(messages.contains("Litematica adapters: READY=1,UNSUPPORTED_VERSION=2"));
    assertTrue(
        messages.contains(
            "Litematica compatibility: status=UNSUPPORTED_VERSION clients=2 minecraft=1.21.11 fabric=0.18.4 litematica=0.26.0 malilib=0.27.0 adapter=none"));
    assertTrue(messages.contains("Capability: worldedit.selection@1 sha256=" + HASH));
    assertTrue(messages.contains("Capability diagnostic: APPROVAL_REQUIRED=1"));
    var rendered = String.join("\n", messages);
    assertFalse(rendered.contains("/home/"));
    assertFalse(rendered.contains("11111111-1111-4111-8111-111111111111"));
  }

  @Test
  void costsDispatchesDurableAggregateWithoutPlayerIdentity() {
    var messages = new ArrayList<String>();
    var replies = new QueuedReplies();
    var gateway = new RecordingManagement();
    var costs = new CompletableFuture<AgentManagementGateway.CostsResult>();
    gateway.costs = costs;
    var command = command(online(), gateway, ignored -> false, replies::dispatch);

    command.execute(
        sender(messages, Set.of(AgentCommand.COSTS_PERMISSION)), "agent", new String[] {"costs"});
    assertTrue(messages.isEmpty());

    costs.complete(
        AgentManagementGateway.CostsResult.available(
            new AgentManagementGateway.CostsSnapshot(
                new AgentManagementGateway.UsageWindow("2026-07-14", 2, 2, 1, 1, 100, 20, 1_234),
                new AgentManagementGateway.UsageWindow("2026-07", 9, 4, 3, 1, 500, 70, 7_890),
                "2026-07",
                25_000_000,
                7_890,
                2_000,
                24_990_110,
                false)));
    assertTrue(messages.isEmpty());
    replies.drain();

    assertEquals(
        List.of(
            "AI costs: currency=USD",
            "Today: period=2026-07-14 requests=2 providerCalls=2 reported=1 estimated=1 inputTokens=100 outputTokens=20 usd=0.001234",
            "Month: period=2026-07 requests=9 providerCalls=4 reported=3 estimated=1 inputTokens=500 outputTokens=70 usd=0.007890",
            "Monthly budget: usd=25.000000 settledUsd=0.007890 activeReservationsUsd=0.002000 remainingUsd=24.990110 status=AVAILABLE"),
        messages);
    assertFalse(String.join("\n", messages).contains("player"));
  }

  @Test
  void reloadMapsEveryStableOutcomeAndNeverRendersFailureDetails() {
    var expected =
        Map.of(
            AgentManagementGateway.ReloadStatus.RELOADED,
            "AI configuration reloaded.",
            AgentManagementGateway.ReloadStatus.UNCHANGED,
            "AI configuration unchanged.",
            AgentManagementGateway.ReloadStatus.RESTART_REQUIRED,
            "AI configuration change requires a server restart.",
            AgentManagementGateway.ReloadStatus.INVALID_CONFIG,
            "AI configuration is invalid; the previous configuration remains active.",
            AgentManagementGateway.ReloadStatus.BUSY,
            "AI reload already in progress.",
            AgentManagementGateway.ReloadStatus.UNAVAILABLE,
            "AI reload is unavailable.",
            AgentManagementGateway.ReloadStatus.FAILED,
            "AI reload failed; the previous configuration remains active.");
    for (var entry : expected.entrySet()) {
      var messages = new ArrayList<String>();
      var replies = new QueuedReplies();
      var gateway = new RecordingManagement();
      gateway.reload =
          CompletableFuture.completedFuture(
              new AgentManagementGateway.ReloadResult(entry.getKey()));

      command(online(), gateway, ignored -> true, replies::dispatch)
          .execute(sender(messages, Set.of()), "agent", new String[] {"reload"});
      assertEquals(List.of("AI reload started."), messages);
      replies.drain();
      assertEquals(List.of("AI reload started.", entry.getValue()), messages);
    }

    var messages = new ArrayList<String>();
    var replies = new QueuedReplies();
    var gateway = new RecordingManagement();
    gateway.reload =
        CompletableFuture.failedFuture(
            new IllegalStateException("/home/server/config.yml contained secret-token"));
    command(online(), gateway, ignored -> true, replies::dispatch)
        .execute(sender(messages, Set.of()), "agent", new String[] {"reload"});
    replies.drain();
    assertEquals(
        List.of(
            "AI reload started.", "AI reload failed; the previous configuration remains active."),
        messages);
  }

  @Test
  void asynchronousManagementRepliesRevalidateOfflineAndAuthorization() {
    var messages = new ArrayList<String>();
    var replies = new QueuedReplies();
    var gateway = new RecordingManagement();
    var costs = new CompletableFuture<AgentManagementGateway.CostsResult>();
    gateway.costs = costs;
    var status = new AtomicReference<>(online());
    var reloadAllowed = new java.util.concurrent.atomic.AtomicBoolean(true);
    var command = command(status::get, gateway, ignored -> reloadAllowed.get(), replies::dispatch);
    var sender =
        sender(messages, Set.of(AgentCommand.COSTS_PERMISSION, AgentCommand.RELOAD_PERMISSION));

    command.execute(sender, "agent", new String[] {"costs"});
    costs.complete(AgentManagementGateway.CostsResult.unavailable());
    status.set(offline());
    replies.drain();
    assertEquals(List.of("AI offline"), messages);

    status.set(online());
    gateway.reload =
        CompletableFuture.completedFuture(
            new AgentManagementGateway.ReloadResult(AgentManagementGateway.ReloadStatus.UNCHANGED));
    command.execute(sender, "agent", new String[] {"reload"});
    reloadAllowed.set(false);
    replies.drain();
    assertEquals(
        List.of(
            "AI offline", "AI reload started.", "You do not have permission to use this command."),
        messages);
  }

  private static ManagementSnapshot snapshot() {
    return new ManagementSnapshot(
        "0.1.0",
        "1.0",
        true,
        2,
        new ManagementSnapshot.CapabilitySummary(
            3,
            List.of(new ManagementSnapshot.CapabilityEntry("worldedit.selection", 1, HASH)),
            1,
            Map.of("APPROVAL_REQUIRED", 1)),
        new ManagementSnapshot.ClientSummary(
            4,
            3,
            Map.of("1.0", 3),
            Map.of("overlay@0", 1, "overlay@1", 2, "recipeView@2", 2),
            Map.of("READY", 1, "UNSUPPORTED_VERSION", 2),
            List.of(
                new ManagementSnapshot.LitematicaCompatibility(
                    "UNSUPPORTED_VERSION", "1.21.11", "0.18.4", "0.26.0", "0.27.0", "none", 2)),
            0));
  }

  private static AgentCommand command(
      AgentStatus status,
      AgentManagementGateway management,
      ReloadAuthorizer reloadAuthorizer,
      java.util.function.Consumer<Runnable> dispatcher) {
    return command(() -> status, management, reloadAuthorizer, dispatcher);
  }

  private static AgentCommand command(
      Supplier<AgentStatus> status,
      AgentManagementGateway management,
      ReloadAuthorizer reloadAuthorizer,
      java.util.function.Consumer<Runnable> dispatcher) {
    return new AgentCommand(
        plugin(),
        status,
        new NoOpControl(),
        (playerId, message) -> AgentRequestGateway.Submission.RUNTIME_UNAVAILABLE,
        ProposalResponseGateway.unavailable(),
        AgentUiControl.unavailable(),
        management,
        reloadAuthorizer,
        ignored -> false,
        dispatcher);
  }

  private static AgentStatus online() {
    return new AgentStatus(
        AgentState.ONLINE, DesiredMode.ENABLED, AgentHealth.HEALTHY, null, null, List.of());
  }

  private static AgentStatus offline() {
    return new AgentStatus(
        AgentState.OFFLINE,
        DesiredMode.DISABLED,
        AgentHealth.UNAVAILABLE,
        OfflineReason.MANUAL,
        null,
        List.of());
  }

  private static Plugin plugin() {
    return (Plugin)
        Proxy.newProxyInstance(
            Plugin.class.getClassLoader(),
            new Class<?>[] {Plugin.class},
            (proxy, method, arguments) -> defaultValue(method.getReturnType()));
  }

  private static CommandSender sender(List<String> messages, Set<String> permissions) {
    return (CommandSender)
        Proxy.newProxyInstance(
            CommandSender.class.getClassLoader(),
            new Class<?>[] {CommandSender.class},
            (proxy, method, arguments) -> {
              if (method.getName().equals("hasPermission")) {
                return arguments != null
                    && arguments.length == 1
                    && permissions.contains(String.valueOf(arguments[0]));
              }
              if (method.getName().equals("sendMessage") && arguments != null) {
                for (var argument : arguments) {
                  if (argument instanceof String message) {
                    messages.add(message);
                  } else if (argument instanceof String[] batch) {
                    messages.addAll(List.of(batch));
                  }
                }
              }
              return defaultValue(method.getReturnType());
            });
  }

  private static Object defaultValue(Class<?> type) {
    if (!type.isPrimitive()) {
      return null;
    }
    if (type == boolean.class) {
      return false;
    }
    if (type == char.class) {
      return '\0';
    }
    if (type == float.class || type == double.class) {
      return 0.0;
    }
    return 0;
  }

  private static final class NoOpControl implements AgentControl {
    @Override
    public void turnOff() {}

    @Override
    public RecoveryRequest turnOn() {
      return new RecoveryRequest(
          RecoveryDisposition.UNAVAILABLE, CompletableFuture.completedFuture(false));
    }
  }

  private static final class RecordingManagement implements AgentManagementGateway {
    private ManagementSnapshot snapshot = ManagementSnapshot.unavailable();
    private CompletionStage<CostsResult> costs =
        CompletableFuture.completedFuture(CostsResult.unavailable());
    private CompletionStage<ReloadResult> reload =
        CompletableFuture.completedFuture(new ReloadResult(ReloadStatus.UNAVAILABLE));
    private int snapshotCalls;
    private int costCalls;
    private int reloadCalls;

    @Override
    public ManagementSnapshot snapshot() {
      snapshotCalls++;
      return snapshot;
    }

    @Override
    public CompletionStage<CostsResult> costs() {
      costCalls++;
      return costs;
    }

    @Override
    public CompletionStage<ReloadResult> reload() {
      reloadCalls++;
      return reload;
    }
  }

  private static final class QueuedReplies {
    private final Queue<Runnable> replies = new ArrayDeque<>();

    void dispatch(Runnable reply) {
      replies.add(reply);
    }

    void drain() {
      while (!replies.isEmpty()) {
        replies.remove().run();
      }
    }
  }
}
