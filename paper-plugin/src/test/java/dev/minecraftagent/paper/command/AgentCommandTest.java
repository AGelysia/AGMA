package dev.minecraftagent.paper.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.minecraftagent.paper.lifecycle.AgentHealth;
import dev.minecraftagent.paper.lifecycle.AgentState;
import dev.minecraftagent.paper.lifecycle.AgentStatus;
import dev.minecraftagent.paper.lifecycle.DesiredMode;
import dev.minecraftagent.paper.lifecycle.OfflineReason;
import dev.minecraftagent.paper.request.AgentModule;
import dev.minecraftagent.paper.request.AgentRequestGateway;
import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

class AgentCommandTest {
  @Test
  void rootCommandHasNoFrameworkPermissionThatCanHideAuthorizedToggles() {
    var command = command(offline(), new RecordingControl(), ignored -> true, Runnable::run);

    assertNull(command.getPermission());
  }

  @Test
  void offlineGatePrecedesPermissionsDoctorAndUsage() {
    var messages = new ArrayList<String>();
    var control = new RecordingControl();
    var authorizationCalls = new int[1];
    var command =
        command(
            offline(),
            control,
            ignored -> {
              authorizationCalls[0]++;
              return false;
            },
            Runnable::run);
    var sender = sender(messages, false);

    assertTrue(command.execute(sender, "agent", new String[0]));
    assertTrue(command.execute(sender, "agent", new String[] {"doctor"}));
    assertTrue(command.execute(sender, "agent", new String[] {"unknown"}));
    assertTrue(command.execute(sender, "agent", new String[] {"on", "extra"}));
    assertTrue(command.execute(sender, "agent", new String[] {"say", "secret"}));
    assertTrue(command.execute(sender, "agent", new String[] {"ui", "clear"}));
    assertTrue(
        command.execute(
            sender,
            "agent",
            new String[] {"ui", "remove", "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"}));
    assertTrue(
        command.execute(
            sender, "agent", new String[] {"confirm", "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"}));

    assertEquals(
        List.of(
            "AI offline",
            "AI offline",
            "AI offline",
            "AI offline",
            "AI offline",
            "AI offline",
            "AI offline",
            "AI offline"),
        messages);
    assertEquals(0, authorizationCalls[0]);
    assertEquals(0, control.offCalls);
    assertEquals(0, control.onCalls);
  }

  @Test
  void toggleAuthorizationIsStillRequiredWhileOffline() {
    var messages = new ArrayList<String>();
    var control = new RecordingControl();
    var command = command(offline(), control, ignored -> false, Runnable::run);
    var sender = sender(messages, true);

    command.execute(sender, "agent", new String[] {"off"});
    command.execute(sender, "agent", new String[] {"on"});

    assertEquals(
        List.of(
            "You do not have permission to use this command.",
            "You do not have permission to use this command."),
        messages);
    assertEquals(0, control.offCalls);
    assertEquals(0, control.onCalls);
  }

  @Test
  void turnOffIsImmediateAndIdempotentAtTheCommandBoundary() {
    var messages = new ArrayList<String>();
    var control = new RecordingControl();
    var command = command(offline(), control, ignored -> true, Runnable::run);

    command.execute(sender(messages, false), "agent", new String[] {"OFF"});

    assertEquals(1, control.offCalls);
    assertEquals(List.of("AI offline"), messages);
  }

  @Test
  void synchronousRecoveryDispositionsHaveStableMessages() {
    assertRecoveryMessage(AgentControl.RecoveryDisposition.ALREADY_ONLINE, "AI online");
    assertRecoveryMessage(
        AgentControl.RecoveryDisposition.ALREADY_STARTING, "AI startup check already in progress.");
    assertRecoveryMessage(
        AgentControl.RecoveryDisposition.UNAVAILABLE,
        "AI remains offline. Check the server console.");
  }

  @Test
  void startedRecoveryDispatchesSuccessReply() {
    var messages = new ArrayList<String>();
    var completion = new CompletableFuture<Boolean>();
    var control = new RecordingControl();
    control.recovery = recovery(AgentControl.RecoveryDisposition.STARTED, completion);
    var replies = new QueuedReplies();
    var command = command(offline(), control, ignored -> true, replies::dispatch);

    command.execute(sender(messages, false), "agent", new String[] {"on"});
    assertEquals(List.of("AI startup check started."), messages);

    completion.complete(true);
    assertEquals(List.of("AI startup check started."), messages);
    replies.drain();

    assertEquals(List.of("AI startup check started.", "AI online"), messages);
  }

  @Test
  void failedOrExceptionalRecoveryDispatchesTheSameSafeReply() {
    for (var exceptional : List.of(false, true)) {
      var messages = new ArrayList<String>();
      var completion = new CompletableFuture<Boolean>();
      var control = new RecordingControl();
      control.recovery = recovery(AgentControl.RecoveryDisposition.STARTED, completion);
      var replies = new QueuedReplies();
      var command = command(offline(), control, ignored -> true, replies::dispatch);

      command.execute(sender(messages, false), "agent", new String[] {"on"});
      if (exceptional) {
        completion.completeExceptionally(new IllegalStateException("sensitive detail"));
      } else {
        completion.complete(false);
      }
      replies.drain();

      assertEquals(
          List.of("AI startup check started.", "AI remains offline. Check the server console."),
          messages);
    }
  }

  @Test
  void onlineCommandsRequireTheOrdinaryPermission() {
    var messages = new ArrayList<String>();
    var command = command(healthy(), new RecordingControl(), ignored -> false, Runnable::run);

    command.execute(sender(messages, false), "agent", new String[0]);
    command.execute(sender(messages, false), "agent", new String[] {"doctor"});

    assertEquals(
        List.of(
            "You do not have permission to use this command.",
            "You do not have permission to use this command."),
        messages);
  }

  @Test
  void doctorMapsHealthAndReportsOnlyStableCodes() {
    var healthyMessages = new ArrayList<String>();
    command(healthy(), new RecordingControl(), ignored -> false, Runnable::run)
        .execute(sender(healthyMessages, true), "agent", new String[] {"doctor"});
    assertEquals(
        List.of(
            "AGMA health: ONLINE",
            "Paper component: unknown",
            "Runtime protocol: 1.0",
            "Clients: online=0 negotiated=0",
            "Client protocols: none",
            "Client features: none",
            "Litematica adapters: none",
            "Capabilities: generation=0 effective=0 disabled=0"),
        healthyMessages);

    var degradedMessages = new ArrayList<String>();
    var degraded =
        new AgentStatus(
            AgentState.ONLINE,
            DesiredMode.ENABLED,
            AgentHealth.DEGRADED,
            null,
            null,
            List.of("OPTIONAL_CAPABILITY_UNAVAILABLE"));
    command(degraded, new RecordingControl(), ignored -> false, Runnable::run)
        .execute(sender(degradedMessages, true), "agent", new String[] {"doctor"});

    assertEquals(
        List.of(
            "AGMA health: DEGRADED",
            "Paper component: unknown",
            "Runtime protocol: 1.0",
            "Clients: online=0 negotiated=0",
            "Client protocols: none",
            "Client features: none",
            "Litematica adapters: none",
            "Capabilities: generation=0 effective=0 disabled=0",
            "Warning: OPTIONAL_CAPABILITY_UNAVAILABLE"),
        degradedMessages);
  }

  @Test
  void onlineRootAndInvalidArgumentsUseStableOutput() {
    var messages = new ArrayList<String>();
    var command = command(healthy(), new RecordingControl(), ignored -> false, Runnable::run);
    var sender = sender(messages, true);

    command.execute(sender, "agent", new String[0]);
    command.execute(sender, "agent", new String[] {"unknown"});

    assertEquals(
        List.of(
            "AGMA status: ONLINE",
            "Desired mode: ENABLED",
            "Health: ONLINE",
            "Runtime: DISCONNECTED",
            "Protocol: 1.0",
            "Active requests: 0",
            usage()),
        messages);
  }

  @Test
  void ordinaryPlayerSayUsesActualUuidAndJoinsOnlyExplicitArguments() {
    var messages = new ArrayList<String>();
    var requests = new RecordingRequests();
    var playerId = UUID.randomUUID();
    var command =
        command(healthy(), new RecordingControl(), requests, ignored -> false, Runnable::run);

    command.execute(
        player(messages, playerId, Set.of(AgentCommand.USE_PERMISSION)),
        "agent",
        new String[] {"say", "private", "question"});

    assertEquals(playerId, requests.playerId);
    assertEquals("private question", requests.message);
    assertTrue(messages.isEmpty());
  }

  @Test
  void sayRequiresUsePermissionPlayerSenderAndMessage() {
    var deniedMessages = new ArrayList<String>();
    var command =
        command(
            healthy(),
            new RecordingControl(),
            new RecordingRequests(),
            ignored -> false,
            Runnable::run);
    command.execute(
        player(deniedMessages, UUID.randomUUID(), Set.of()),
        "agent",
        new String[] {"say", "hello"});
    assertEquals(List.of("You do not have permission to use this command."), deniedMessages);

    var consoleMessages = new ArrayList<String>();
    command.execute(sender(consoleMessages, true), "agent", new String[] {"say", "hello"});
    assertEquals(List.of("This command can only be used by a player."), consoleMessages);

    var emptyMessages = new ArrayList<String>();
    command.execute(
        player(emptyMessages, UUID.randomUUID(), Set.of(AgentCommand.USE_PERMISSION)),
        "agent",
        new String[] {"say"});
    assertEquals(List.of(usage()), emptyMessages);
  }

  @Test
  void resumeUsesActualPlayerIdentityAndRequiresCanonicalSessionId() {
    var messages = new ArrayList<String>();
    var requests = new RecordingRequests();
    var playerId = UUID.randomUUID();
    var sessionId = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    var command =
        command(healthy(), new RecordingControl(), requests, ignored -> false, Runnable::run);
    var player = player(messages, playerId, Set.of(AgentCommand.USE_PERMISSION));

    command.execute(player, "agent", new String[] {"resume", sessionId.toString()});
    assertEquals(playerId, requests.playerId);
    assertEquals(sessionId, requests.sessionId);
    assertEquals("resume", requests.operation);

    command.execute(player, "agent", new String[] {"resume", sessionId.toString().toUpperCase()});
    assertEquals(List.of("AI session ID must be a canonical UUID."), messages);
  }

  @Test
  void moduleListAndExplicitRouteUseTheOrdinaryModulePermission() {
    var messages = new ArrayList<String>();
    var requests = new RecordingRequests();
    var playerId = UUID.randomUUID();
    var command =
        command(healthy(), new RecordingControl(), requests, ignored -> false, Runnable::run);
    var player =
        player(
            messages,
            playerId,
            Set.of(AgentCommand.USE_PERMISSION, AgentCommand.MODULE_PERMISSION));

    command.execute(player, "agent", new String[] {"module", "list"});
    command.execute(player, "agent", new String[] {"module", "ReCiPe", "craft", "a", "comparator"});

    assertEquals(List.of("AI modules: general, recipe, guide, locate, build, project"), messages);
    assertEquals(playerId, requests.playerId);
    assertEquals(AgentModule.RECIPE, requests.module);
    assertEquals("craft a comparator", requests.message);
    assertEquals("module", requests.operation);
  }

  @Test
  void unknownModuleAndMissingModulePermissionNeverSubmit() {
    var messages = new ArrayList<String>();
    var requests = new RecordingRequests();
    var command =
        command(healthy(), new RecordingControl(), requests, ignored -> false, Runnable::run);

    command.execute(
        player(
            messages,
            UUID.randomUUID(),
            Set.of(AgentCommand.USE_PERMISSION, AgentCommand.MODULE_PERMISSION)),
        "agent",
        new String[] {"module", "unknown", "secret"});
    command.execute(
        player(messages, UUID.randomUUID(), Set.of(AgentCommand.USE_PERMISSION)),
        "agent",
        new String[] {"module", "recipe", "secret"});

    assertEquals(
        List.of(
            "Unknown AI module. Use /agent module list.",
            "You do not have permission to use this command."),
        messages);
    assertNull(requests.operation);
  }

  @Test
  void uiActionsUseActualPlayerIdentityAndRemainPresentationOnly() {
    var messages = new ArrayList<String>();
    var playerId = UUID.randomUUID();
    var viewId = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    var invocations = new ArrayList<String>();
    AgentUiControl ui =
        (actualPlayerId, action, actualViewId) -> {
          invocations.add(actualPlayerId + ":" + action.name() + ":" + actualViewId);
          return action == AgentUiControl.Action.CLEAR
              ? AgentUiControl.Result.CLIENT_UNAVAILABLE
              : AgentUiControl.Result.SENT;
        };
    var command =
        new AgentCommand(
            plugin(),
            AgentCommandTest::healthy,
            new RecordingControl(),
            new RecordingRequests(),
            ProposalResponseGateway.unavailable(),
            ui,
            ignored -> false,
            Runnable::run);
    var player = player(messages, playerId, Set.of(AgentCommand.UI_PERMISSION));

    command.execute(player, "agent", new String[] {"ui", "PIN"});
    command.execute(player, "agent", new String[] {"ui", "unpin"});
    command.execute(player, "agent", new String[] {"ui", "clear"});
    command.execute(player, "agent", new String[] {"ui", "preview", viewId.toString()});
    command.execute(player, "agent", new String[] {"ui", "remove", viewId.toString()});
    command.execute(player, "agent", new String[] {"ui", "materials", viewId.toString()});

    assertEquals(
        List.of(
            playerId + ":PIN:null",
            playerId + ":UNPIN:null",
            playerId + ":CLEAR:null",
            playerId + ":PREVIEW:" + viewId,
            playerId + ":REMOVE:" + viewId,
            playerId + ":MATERIALS:" + viewId),
        invocations);
    assertEquals(
        List.of(
            "AI client UI updated.",
            "AI client UI updated.",
            "AI client Mod is unavailable.",
            "AI client UI updated.",
            "AI client UI updated.",
            "AI client UI updated."),
        messages);
  }

  @Test
  void uiPreviewActionsRequireUiPermissionAndCanonicalTarget() {
    var messages = new ArrayList<String>();
    var invocations = new ArrayList<String>();
    AgentUiControl ui =
        (playerId, action, viewId) -> {
          invocations.add(action + ":" + viewId);
          return AgentUiControl.Result.SENT;
        };
    var command =
        new AgentCommand(
            plugin(),
            AgentCommandTest::healthy,
            new RecordingControl(),
            new RecordingRequests(),
            ProposalResponseGateway.unavailable(),
            ui,
            ignored -> false,
            Runnable::run);

    command.execute(
        player(messages, UUID.randomUUID(), Set.of()),
        "agent",
        new String[] {"ui", "preview", "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"});
    command.execute(
        player(messages, UUID.randomUUID(), Set.of(AgentCommand.UI_PERMISSION)),
        "agent",
        new String[] {"ui", "remove", "AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA"});

    assertTrue(invocations.isEmpty());
    assertEquals(
        List.of(
            "You do not have permission to use this command.", "View ID must be a canonical UUID."),
        messages);
  }

  @Test
  void uiTransportFailuresUseTheStableUnavailableReply() {
    var messages = new ArrayList<String>();
    var playerId = UUID.randomUUID();
    AgentUiControl failing =
        (actualPlayerId, action, viewId) -> {
          throw new IllegalStateException("sensitive transport detail");
        };
    var command =
        new AgentCommand(
            plugin(),
            AgentCommandTest::healthy,
            new RecordingControl(),
            new RecordingRequests(),
            ProposalResponseGateway.unavailable(),
            failing,
            ignored -> false,
            Runnable::run);

    command.execute(
        player(messages, playerId, Set.of(AgentCommand.UI_PERMISSION)),
        "agent",
        new String[] {"ui", "pin"});

    assertEquals(List.of("AI client Mod is unavailable."), messages);
  }

  @Test
  void sayMapsRequestAdmissionFailuresToStablePrivateOutput() {
    var expected =
        java.util.Map.of(
            AgentRequestGateway.Submission.ALREADY_ACTIVE,
            "AI request already in progress.",
            AgentRequestGateway.Submission.OFFLINE,
            "AI offline",
            AgentRequestGateway.Submission.RUNTIME_UNAVAILABLE,
            "AI unavailable. Try again later.",
            AgentRequestGateway.Submission.INVALID_MESSAGE,
            "AI message must contain 1 to 4096 characters.");
    for (var entry : expected.entrySet()) {
      var messages = new ArrayList<String>();
      AgentRequestGateway requests = (playerId, message) -> entry.getKey();
      command(healthy(), new RecordingControl(), requests, ignored -> false, Runnable::run)
          .execute(
              player(messages, UUID.randomUUID(), Set.of(AgentCommand.USE_PERMISSION)),
              "agent",
              new String[] {"say", "hello"});
      assertEquals(List.of(entry.getValue()), messages);
    }
  }

  @Test
  void proposalResponsesUseActualPlayerIdentityAndDispatchAsyncReplies() {
    var messages = new ArrayList<String>();
    var replies = new QueuedReplies();
    var proposals = new RecordingProposals();
    var confirm = new CompletableFuture<ProposalResponseGateway.Result>();
    var reject = new CompletableFuture<ProposalResponseGateway.Result>();
    proposals.confirmation = confirm;
    proposals.rejection = reject;
    var command =
        command(
            healthy(),
            new RecordingControl(),
            new RecordingRequests(),
            proposals,
            ignored -> false,
            replies::dispatch);
    var playerId = UUID.randomUUID();
    var firstProposalId = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    var secondProposalId = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
    var player = player(messages, playerId, Set.of(AgentCommand.PROPOSAL_PERMISSION));

    command.execute(player, "agent", new String[] {"CoNfIrM", firstProposalId.toString()});
    assertEquals(playerId, proposals.playerId);
    assertEquals(firstProposalId, proposals.proposalId);
    assertEquals("confirm", proposals.operation);
    assertTrue(messages.isEmpty());
    confirm.complete(ProposalResponseGateway.Result.CONFIRMED);
    assertTrue(messages.isEmpty());
    replies.drain();

    command.execute(player, "agent", new String[] {"reject", secondProposalId.toString()});
    assertEquals(playerId, proposals.playerId);
    assertEquals(secondProposalId, proposals.proposalId);
    assertEquals("reject", proposals.operation);
    reject.complete(ProposalResponseGateway.Result.REJECTED);
    replies.drain();

    assertEquals(List.of("Proposal confirmed.", "Proposal rejected."), messages);
  }

  @Test
  void proposalResponsesRequirePermissionPlayerAndExactCanonicalId() {
    var proposals = new RecordingProposals();
    var command =
        command(
            healthy(),
            new RecordingControl(),
            new RecordingRequests(),
            proposals,
            ignored -> false,
            Runnable::run);
    var proposalId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";

    var deniedMessages = new ArrayList<String>();
    command.execute(
        player(deniedMessages, UUID.randomUUID(), Set.of()),
        "agent",
        new String[] {"confirm", proposalId});
    assertEquals(List.of("You do not have permission to use this command."), deniedMessages);

    var consoleMessages = new ArrayList<String>();
    command.execute(sender(consoleMessages, true), "agent", new String[] {"reject", proposalId});
    assertEquals(List.of("This command can only be used by a player."), consoleMessages);

    var invalidMessages = new ArrayList<String>();
    var permitted =
        player(invalidMessages, UUID.randomUUID(), Set.of(AgentCommand.PROPOSAL_PERMISSION));
    command.execute(
        permitted,
        "agent",
        new String[] {"confirm", proposalId.toUpperCase(java.util.Locale.ROOT)});
    command.execute(permitted, "agent", new String[] {"reject"});
    assertEquals(List.of("Proposal ID must be a canonical UUID.", usage()), invalidMessages);
    assertNull(proposals.operation);
  }

  @Test
  void proposalResultsAndFailuresUseOnlyFixedSafeMessages() {
    var expected =
        java.util.Map.of(
            ProposalResponseGateway.Result.CONFIRMED,
            "Proposal confirmed.",
            ProposalResponseGateway.Result.REJECTED,
            "Proposal rejected.",
            ProposalResponseGateway.Result.UNAVAILABLE,
            "Proposal is unavailable.",
            ProposalResponseGateway.Result.FAILED,
            "Proposal response failed. Try again.");
    for (var entry : expected.entrySet()) {
      var messages = new ArrayList<String>();
      var proposals = new RecordingProposals();
      proposals.confirmation = CompletableFuture.completedFuture(entry.getKey());
      command(
              healthy(),
              new RecordingControl(),
              new RecordingRequests(),
              proposals,
              ignored -> false,
              Runnable::run)
          .execute(
              player(messages, UUID.randomUUID(), Set.of(AgentCommand.PROPOSAL_PERMISSION)),
              "agent",
              new String[] {"confirm", "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"});
      assertEquals(List.of(entry.getValue()), messages);
    }

    for (var synchronous : List.of(false, true)) {
      var messages = new ArrayList<String>();
      ProposalResponseGateway proposals =
          new ProposalResponseGateway() {
            @Override
            public CompletionStage<Result> confirm(UUID playerId, UUID proposalId) {
              if (synchronous) {
                throw new IllegalStateException("sensitive synchronous detail");
              }
              return CompletableFuture.failedFuture(
                  new IllegalStateException("sensitive asynchronous detail"));
            }
          };
      command(
              healthy(),
              new RecordingControl(),
              new RecordingRequests(),
              proposals,
              ignored -> false,
              Runnable::run)
          .execute(
              player(messages, UUID.randomUUID(), Set.of(AgentCommand.PROPOSAL_PERMISSION)),
              "agent",
              new String[] {"confirm", "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"});
      assertEquals(List.of("Proposal response failed. Try again."), messages);
    }
  }

  @Test
  void constructorsWithoutProposalGatewayDefaultToUnavailable() {
    var messages = new ArrayList<String>();
    command(healthy(), new RecordingControl(), ignored -> false, Runnable::run)
        .execute(
            player(messages, UUID.randomUUID(), Set.of(AgentCommand.PROPOSAL_PERMISSION)),
            "agent",
            new String[] {"confirm", "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"});

    assertEquals(List.of("Proposal is unavailable."), messages);
  }

  @Test
  void tabCompletionIsStatePermissionAndAuthorizationAware() {
    var permitted = sender(new ArrayList<>(), true);
    var denied = sender(new ArrayList<>(), false);

    var onlineAuthorized =
        command(healthy(), new RecordingControl(), ignored -> true, Runnable::run);
    assertEquals(
        List.of(
            "say", "resume", "ui", "module", "status", "doctor", "capabilities", "costs", "off"),
        onlineAuthorized.tabComplete(permitted, "agent", new String[] {""}));
    assertEquals(
        List.of("list", "general", "recipe", "guide", "locate", "build", "project"),
        onlineAuthorized.tabComplete(permitted, "agent", new String[] {"module", ""}));
    assertEquals(
        List.of("recipe"),
        onlineAuthorized.tabComplete(permitted, "agent", new String[] {"module", "re"}));
    assertEquals(
        List.of("pin", "unpin", "clear", "preview", "remove", "materials"),
        onlineAuthorized.tabComplete(permitted, "agent", new String[] {"ui", ""}));
    assertEquals(
        List.of("unpin"),
        onlineAuthorized.tabComplete(permitted, "agent", new String[] {"ui", "un"}));
    assertEquals(List.of("off"), onlineAuthorized.tabComplete(denied, "agent", new String[] {"o"}));

    var onlineUnauthorized =
        command(healthy(), new RecordingControl(), ignored -> false, Runnable::run);
    assertEquals(
        List.of("doctor"), onlineUnauthorized.tabComplete(permitted, "agent", new String[] {"d"}));
    assertEquals(List.of(), onlineUnauthorized.tabComplete(denied, "agent", new String[] {""}));

    var offlineAuthorized =
        command(offline(), new RecordingControl(), ignored -> true, Runnable::run);
    assertEquals(
        List.of("on", "off"), offlineAuthorized.tabComplete(denied, "agent", new String[] {"O"}));
    assertEquals(
        List.of(), offlineAuthorized.tabComplete(denied, "agent", new String[] {"on", "extra"}));
    assertEquals(
        List.of(), onlineAuthorized.tabComplete(permitted, "agent", new String[] {"confirm", ""}));
  }

  private static void assertRecoveryMessage(
      AgentControl.RecoveryDisposition disposition, String expected) {
    var messages = new ArrayList<String>();
    var control = new RecordingControl();
    control.recovery = recovery(disposition, CompletableFuture.completedFuture(false));
    var command = command(offline(), control, ignored -> true, Runnable::run);

    command.execute(sender(messages, false), "agent", new String[] {"on"});

    assertEquals(1, control.onCalls);
    assertEquals(List.of(expected), messages);
  }

  private static AgentControl.RecoveryRequest recovery(
      AgentControl.RecoveryDisposition disposition, CompletionStage<Boolean> completion) {
    return new AgentControl.RecoveryRequest(disposition, completion);
  }

  private static AgentCommand command(
      AgentStatus status,
      AgentControl control,
      ToggleAuthorizer authorizer,
      java.util.function.Consumer<Runnable> dispatcher) {
    return new AgentCommand(plugin(), () -> status, control, authorizer, dispatcher);
  }

  private static AgentCommand command(
      AgentStatus status,
      AgentControl control,
      AgentRequestGateway requests,
      ToggleAuthorizer authorizer,
      java.util.function.Consumer<Runnable> dispatcher) {
    return new AgentCommand(plugin(), () -> status, control, requests, authorizer, dispatcher);
  }

  private static AgentCommand command(
      AgentStatus status,
      AgentControl control,
      AgentRequestGateway requests,
      ProposalResponseGateway proposalResponses,
      ToggleAuthorizer authorizer,
      java.util.function.Consumer<Runnable> dispatcher) {
    return new AgentCommand(
        plugin(), () -> status, control, requests, proposalResponses, authorizer, dispatcher);
  }

  private static AgentStatus healthy() {
    return new AgentStatus(
        AgentState.ONLINE, DesiredMode.ENABLED, AgentHealth.HEALTHY, null, null, List.of());
  }

  private static String usage() {
    return "/agent [say <message>|resume [session]|module list|module <name> <message>|ui"
        + " <pin|unpin|clear>|ui <preview|remove|materials> <view-id>|confirm"
        + " <proposal>|reject <proposal>|status|doctor|reload|capabilities|costs|off|on]";
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

  private static CommandSender sender(List<String> messages, boolean permission) {
    return (CommandSender)
        Proxy.newProxyInstance(
            CommandSender.class.getClassLoader(),
            new Class<?>[] {CommandSender.class},
            (proxy, method, arguments) -> {
              if (method.getName().equals("hasPermission")) {
                return permission;
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

  private static Player player(List<String> messages, UUID playerId, Set<String> permissions) {
    return (Player)
        Proxy.newProxyInstance(
            Player.class.getClassLoader(),
            new Class<?>[] {Player.class},
            (proxy, method, arguments) -> {
              if (method.getName().equals("getUniqueId")) {
                return playerId;
              }
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
    if (type == byte.class || type == short.class || type == int.class || type == long.class) {
      return 0;
    }
    if (type == float.class || type == double.class) {
      return 0.0;
    }
    return null;
  }

  private static final class RecordingControl implements AgentControl {
    private int offCalls;
    private int onCalls;
    private RecoveryRequest recovery =
        recovery(RecoveryDisposition.UNAVAILABLE, CompletableFuture.completedFuture(false));

    @Override
    public void turnOff() {
      offCalls++;
    }

    @Override
    public RecoveryRequest turnOn() {
      onCalls++;
      return recovery;
    }
  }

  private static final class RecordingRequests implements AgentRequestGateway {
    private UUID playerId;
    private UUID sessionId;
    private AgentModule module;
    private String message;
    private String operation;

    @Override
    public Submission submit(UUID playerId, String message) {
      this.playerId = playerId;
      this.message = message;
      this.operation = "say";
      return Submission.ACCEPTED;
    }

    @Override
    public Submission submitModule(UUID playerId, AgentModule module, String message) {
      this.playerId = playerId;
      this.module = module;
      this.message = message;
      this.operation = "module";
      return Submission.ACCEPTED;
    }

    @Override
    public Submission resume(UUID playerId, UUID sessionId) {
      this.playerId = playerId;
      this.sessionId = sessionId;
      this.operation = "resume";
      return Submission.ACCEPTED;
    }
  }

  private static final class RecordingProposals implements ProposalResponseGateway {
    private UUID playerId;
    private UUID proposalId;
    private String operation;
    private CompletionStage<Result> confirmation =
        CompletableFuture.completedFuture(Result.UNAVAILABLE);
    private CompletionStage<Result> rejection =
        CompletableFuture.completedFuture(Result.UNAVAILABLE);

    @Override
    public CompletionStage<Result> confirm(UUID playerId, UUID proposalId) {
      this.playerId = playerId;
      this.proposalId = proposalId;
      this.operation = "confirm";
      return confirmation;
    }

    @Override
    public CompletionStage<Result> reject(UUID playerId, UUID proposalId) {
      this.playerId = playerId;
      this.proposalId = proposalId;
      this.operation = "reject";
      return rejection;
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
