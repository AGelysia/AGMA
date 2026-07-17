package dev.minecraftagent.paper.command;

import dev.minecraftagent.paper.lifecycle.AgentHealth;
import dev.minecraftagent.paper.lifecycle.AgentState;
import dev.minecraftagent.paper.lifecycle.AgentStatus;
import dev.minecraftagent.paper.request.AgentModule;
import dev.minecraftagent.paper.request.AgentRequestGateway;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginIdentifiableCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

public final class AgentCommand extends Command implements PluginIdentifiableCommand {
  public static final String PERMISSION = "minecraftagent.command.agent";
  public static final String USE_PERMISSION = "minecraftagent.use";
  public static final String MODULE_PERMISSION = "minecraftagent.module";
  public static final String STATUS_PERMISSION = "minecraftagent.admin.status";
  public static final String DOCTOR_PERMISSION = "minecraftagent.admin.doctor";
  public static final String RELOAD_PERMISSION = "minecraftagent.admin.reload";
  public static final String CAPABILITIES_PERMISSION = "minecraftagent.admin.capabilities";
  public static final String COSTS_PERMISSION = "minecraftagent.admin.costs";
  public static final String UI_PERMISSION = "minecraftagent.ui";
  public static final String PROPOSAL_PERMISSION = "minecraftagent.proposal.respond";
  public static final String TOGGLE_PERMISSION = "minecraftagent.admin.toggle";

  private static final String OFFLINE_MESSAGE = "AI offline";
  private static final String ONLINE_MESSAGE = "AI online";
  private static final String RECOVERY_STARTED_MESSAGE = "AI startup check started.";
  private static final String RECOVERY_ALREADY_STARTED_MESSAGE =
      "AI startup check already in progress.";
  private static final String RECOVERY_FAILED_MESSAGE =
      "AI remains offline. Check the server console.";
  private static final String PERMISSION_DENIED_MESSAGE =
      "You do not have permission to use this command.";
  private static final String PLAYER_ONLY_MESSAGE = "This command can only be used by a player.";
  private static final String REQUEST_ACTIVE_MESSAGE = "AI request already in progress.";
  private static final String REQUEST_UNAVAILABLE_MESSAGE = "AI unavailable. Try again later.";
  private static final String MESSAGE_INVALID_MESSAGE =
      "AI message must contain 1 to 4096 characters.";
  private static final String SESSION_INVALID_MESSAGE = "AI session ID must be a canonical UUID.";
  private static final String MODULE_UNKNOWN_MESSAGE = "Unknown AI module. Use /agent module list.";
  private static final String MODULE_LIST_PREFIX = "AI modules: ";
  private static final String PROPOSAL_ID_INVALID_MESSAGE = "Proposal ID must be a canonical UUID.";
  private static final String VIEW_ID_INVALID_MESSAGE = "View ID must be a canonical UUID.";
  private static final String PROPOSAL_CONFIRMED_MESSAGE = "Proposal confirmed.";
  private static final String PROPOSAL_REJECTED_MESSAGE = "Proposal rejected.";
  private static final String PROPOSAL_UNAVAILABLE_MESSAGE = "Proposal is unavailable.";
  private static final String PROPOSAL_FAILED_MESSAGE = "Proposal response failed. Try again.";
  private static final String UI_UPDATED_MESSAGE = "AI client UI updated.";
  private static final String UI_UNAVAILABLE_MESSAGE = "AI client Mod is unavailable.";

  private final Plugin plugin;
  private final Supplier<AgentStatus> status;
  private final AgentControl control;
  private final AgentRequestGateway requests;
  private final ProposalResponseGateway proposalResponses;
  private final AgentUiControl uiControl;
  private final AgentManagementGateway management;
  private final ReloadAuthorizer reloadAuthorizer;
  private final ToggleAuthorizer toggleAuthorizer;
  private final Consumer<Runnable> replyDispatcher;

  public AgentCommand(
      Plugin plugin,
      Supplier<AgentStatus> status,
      AgentControl control,
      ToggleAuthorizer toggleAuthorizer,
      Consumer<Runnable> replyDispatcher) {
    this(
        plugin,
        status,
        control,
        (playerId, message) -> AgentRequestGateway.Submission.RUNTIME_UNAVAILABLE,
        ProposalResponseGateway.unavailable(),
        AgentUiControl.unavailable(),
        AgentManagementGateway.unavailable(),
        ignored -> false,
        toggleAuthorizer,
        replyDispatcher);
  }

  public AgentCommand(
      Plugin plugin,
      Supplier<AgentStatus> status,
      AgentControl control,
      AgentRequestGateway requests,
      ToggleAuthorizer toggleAuthorizer,
      Consumer<Runnable> replyDispatcher) {
    this(
        plugin,
        status,
        control,
        requests,
        ProposalResponseGateway.unavailable(),
        AgentUiControl.unavailable(),
        AgentManagementGateway.unavailable(),
        ignored -> false,
        toggleAuthorizer,
        replyDispatcher);
  }

  public AgentCommand(
      Plugin plugin,
      Supplier<AgentStatus> status,
      AgentControl control,
      AgentRequestGateway requests,
      ProposalResponseGateway proposalResponses,
      ToggleAuthorizer toggleAuthorizer,
      Consumer<Runnable> replyDispatcher) {
    this(
        plugin,
        status,
        control,
        requests,
        proposalResponses,
        AgentUiControl.unavailable(),
        AgentManagementGateway.unavailable(),
        ignored -> false,
        toggleAuthorizer,
        replyDispatcher);
  }

  public AgentCommand(
      Plugin plugin,
      Supplier<AgentStatus> status,
      AgentControl control,
      AgentRequestGateway requests,
      ProposalResponseGateway proposalResponses,
      AgentUiControl uiControl,
      ToggleAuthorizer toggleAuthorizer,
      Consumer<Runnable> replyDispatcher) {
    this(
        plugin,
        status,
        control,
        requests,
        proposalResponses,
        uiControl,
        AgentManagementGateway.unavailable(),
        ignored -> false,
        toggleAuthorizer,
        replyDispatcher);
  }

  public AgentCommand(
      Plugin plugin,
      Supplier<AgentStatus> status,
      AgentControl control,
      AgentRequestGateway requests,
      ProposalResponseGateway proposalResponses,
      AgentUiControl uiControl,
      AgentManagementGateway management,
      ReloadAuthorizer reloadAuthorizer,
      ToggleAuthorizer toggleAuthorizer,
      Consumer<Runnable> replyDispatcher) {
    super(
        "agent",
        "Controls AGMA readiness",
        "/agent [say <message>|resume [session]|module list|module <name> <message>|ui"
            + " <pin|unpin|clear>|ui <preview|remove|materials> <view-id>|confirm"
            + " <proposal>|reject <proposal>|status|doctor|reload|capabilities|costs|off|on]",
        List.of());
    this.plugin = Objects.requireNonNull(plugin);
    this.status = Objects.requireNonNull(status);
    this.control = Objects.requireNonNull(control);
    this.requests = Objects.requireNonNull(requests);
    this.proposalResponses = Objects.requireNonNull(proposalResponses);
    this.uiControl = Objects.requireNonNull(uiControl);
    this.management = Objects.requireNonNull(management);
    this.reloadAuthorizer = Objects.requireNonNull(reloadAuthorizer);
    this.toggleAuthorizer = Objects.requireNonNull(toggleAuthorizer);
    this.replyDispatcher = Objects.requireNonNull(replyDispatcher);
  }

  @Override
  public Plugin getPlugin() {
    return plugin;
  }

  @Override
  public boolean execute(CommandSender sender, String commandLabel, String[] arguments) {
    var snapshot = status.get();
    var exactToggle = arguments.length == 1 && isToggle(arguments[0]);
    if (snapshot.state() != AgentState.ONLINE && !exactToggle) {
      sender.sendMessage(OFFLINE_MESSAGE);
      return true;
    }

    if (exactToggle) {
      if (!toggleAuthorizer.canToggle(sender)) {
        sender.sendMessage(PERMISSION_DENIED_MESSAGE);
        return true;
      }
      if (arguments[0].equalsIgnoreCase("off")) {
        control.turnOff();
        sender.sendMessage(OFFLINE_MESSAGE);
      } else {
        turnOn(sender);
      }
      return true;
    }

    if (arguments.length >= 1 && arguments[0].equalsIgnoreCase("say")) {
      say(sender, arguments);
      return true;
    }
    if (arguments.length >= 1 && arguments[0].equalsIgnoreCase("resume")) {
      resume(sender, arguments);
      return true;
    }
    if (arguments.length >= 1 && arguments[0].equalsIgnoreCase("module")) {
      module(sender, arguments);
      return true;
    }
    if (arguments.length >= 1 && arguments[0].equalsIgnoreCase("ui")) {
      ui(sender, arguments);
      return true;
    }
    if (arguments.length >= 1 && isProposalResponse(arguments[0])) {
      respondToProposal(sender, arguments);
      return true;
    }

    if (arguments.length == 0
        || (arguments.length == 1 && arguments[0].equalsIgnoreCase("status"))) {
      status(sender, snapshot);
      return true;
    }
    if (arguments.length == 1 && arguments[0].equalsIgnoreCase("doctor")) {
      doctor(sender, snapshot);
      return true;
    }
    if (arguments.length == 1 && arguments[0].equalsIgnoreCase("capabilities")) {
      capabilities(sender);
      return true;
    }
    if (arguments.length == 1 && arguments[0].equalsIgnoreCase("costs")) {
      costs(sender);
      return true;
    }
    if (arguments.length == 1 && arguments[0].equalsIgnoreCase("reload")) {
      reload(sender);
      return true;
    }
    sender.sendMessage(getUsage());
    return true;
  }

  @Override
  public List<String> tabComplete(CommandSender sender, String alias, String[] arguments)
      throws IllegalArgumentException {
    var online = status.get().state() == AgentState.ONLINE;
    if (arguments.length == 2
        && online
        && arguments[0].equalsIgnoreCase("module")
        && sender.hasPermission(MODULE_PERMISSION)) {
      var prefix = arguments[1].toLowerCase(Locale.ROOT);
      return java.util.stream.Stream.concat(
              java.util.stream.Stream.of("list"), AgentModule.protocolNames().stream())
          .filter(candidate -> candidate.startsWith(prefix))
          .toList();
    }
    if (arguments.length == 2
        && online
        && arguments[0].equalsIgnoreCase("ui")
        && sender.hasPermission(UI_PERMISSION)) {
      var prefix = arguments[1].toLowerCase(Locale.ROOT);
      return List.of("pin", "unpin", "clear", "preview", "remove", "materials").stream()
          .filter(candidate -> candidate.startsWith(prefix))
          .toList();
    }
    if (arguments.length != 1) {
      return List.of();
    }

    var candidates = new ArrayList<String>(12);
    if (online) {
      if (sender.hasPermission(USE_PERMISSION)) {
        candidates.add("say");
        candidates.add("resume");
      }
      if (sender.hasPermission(UI_PERMISSION)) {
        candidates.add("ui");
      }
      if (sender.hasPermission(MODULE_PERMISSION)) {
        candidates.add("module");
      }
      if (sender.hasPermission(STATUS_PERMISSION)) {
        candidates.add("status");
      }
      if (sender.hasPermission(DOCTOR_PERMISSION)) {
        candidates.add("doctor");
      }
      if (sender.hasPermission(CAPABILITIES_PERMISSION)) {
        candidates.add("capabilities");
      }
      if (sender.hasPermission(COSTS_PERMISSION)) {
        candidates.add("costs");
      }
      if (reloadAuthorizer.canReload(sender)) {
        candidates.add("reload");
      }
      if (toggleAuthorizer.canToggle(sender)) {
        candidates.add("off");
      }
    } else if (toggleAuthorizer.canToggle(sender)) {
      candidates.add("on");
      candidates.add("off");
    }

    var prefix = arguments[0].toLowerCase(Locale.ROOT);
    return candidates.stream().filter(candidate -> candidate.startsWith(prefix)).toList();
  }

  private void turnOn(CommandSender sender) {
    var request = control.turnOn();
    switch (request.disposition()) {
      case ALREADY_ONLINE -> sender.sendMessage(ONLINE_MESSAGE);
      case ALREADY_STARTING -> sender.sendMessage(RECOVERY_ALREADY_STARTED_MESSAGE);
      case UNAVAILABLE -> sender.sendMessage(RECOVERY_FAILED_MESSAGE);
      case STARTED -> {
        sender.sendMessage(RECOVERY_STARTED_MESSAGE);
        request
            .completion()
            .whenComplete(
                (online, error) ->
                    replyDispatcher.accept(
                        () ->
                            sender.sendMessage(
                                error == null && Boolean.TRUE.equals(online)
                                    ? ONLINE_MESSAGE
                                    : RECOVERY_FAILED_MESSAGE)));
      }
    }
  }

  private void say(CommandSender sender, String[] arguments) {
    if (!sender.hasPermission(USE_PERMISSION)) {
      sender.sendMessage(PERMISSION_DENIED_MESSAGE);
      return;
    }
    if (!(sender instanceof Player player)) {
      sender.sendMessage(PLAYER_ONLY_MESSAGE);
      return;
    }
    if (arguments.length < 2) {
      sender.sendMessage(getUsage());
      return;
    }

    var message = String.join(" ", Arrays.copyOfRange(arguments, 1, arguments.length));
    handleSubmission(sender, requests.submit(player.getUniqueId(), message));
  }

  private void resume(CommandSender sender, String[] arguments) {
    if (!sender.hasPermission(USE_PERMISSION)) {
      sender.sendMessage(PERMISSION_DENIED_MESSAGE);
      return;
    }
    if (!(sender instanceof Player player)) {
      sender.sendMessage(PLAYER_ONLY_MESSAGE);
      return;
    }
    if (arguments.length > 2) {
      sender.sendMessage(getUsage());
      return;
    }

    UUID sessionId = null;
    if (arguments.length == 2) {
      sessionId = canonicalUuid(arguments[1]);
      if (sessionId == null) {
        sender.sendMessage(SESSION_INVALID_MESSAGE);
        return;
      }
    }
    handleSubmission(sender, requests.resume(player.getUniqueId(), sessionId));
  }

  private void module(CommandSender sender, String[] arguments) {
    if (!sender.hasPermission(MODULE_PERMISSION)) {
      sender.sendMessage(PERMISSION_DENIED_MESSAGE);
      return;
    }
    if (arguments.length == 2 && arguments[1].equalsIgnoreCase("list")) {
      sender.sendMessage(MODULE_LIST_PREFIX + String.join(", ", AgentModule.protocolNames()));
      return;
    }
    if (!sender.hasPermission(USE_PERMISSION)) {
      sender.sendMessage(PERMISSION_DENIED_MESSAGE);
      return;
    }
    if (!(sender instanceof Player player)) {
      sender.sendMessage(PLAYER_ONLY_MESSAGE);
      return;
    }
    if (arguments.length < 3) {
      sender.sendMessage(getUsage());
      return;
    }

    var selected = AgentModule.fromProtocolName(arguments[1]);
    if (selected.isEmpty()) {
      sender.sendMessage(MODULE_UNKNOWN_MESSAGE);
      return;
    }
    var message = String.join(" ", Arrays.copyOfRange(arguments, 2, arguments.length));
    handleSubmission(
        sender, requests.submitModule(player.getUniqueId(), selected.orElseThrow(), message));
  }

  private void ui(CommandSender sender, String[] arguments) {
    if (!sender.hasPermission(UI_PERMISSION)) {
      sender.sendMessage(PERMISSION_DENIED_MESSAGE);
      return;
    }
    if (!(sender instanceof Player player)) {
      sender.sendMessage(PLAYER_ONLY_MESSAGE);
      return;
    }
    if (arguments.length < 2 || arguments.length > 3) {
      sender.sendMessage(getUsage());
      return;
    }
    AgentUiControl.Action action =
        switch (arguments[1].toLowerCase(Locale.ROOT)) {
          case "pin" -> AgentUiControl.Action.PIN;
          case "unpin" -> AgentUiControl.Action.UNPIN;
          case "clear" -> AgentUiControl.Action.CLEAR;
          case "preview" -> AgentUiControl.Action.PREVIEW;
          case "remove" -> AgentUiControl.Action.REMOVE;
          case "materials" -> AgentUiControl.Action.MATERIALS;
          default -> null;
        };
    if (action == null) {
      sender.sendMessage(getUsage());
      return;
    }
    boolean requiresViewId =
        action == AgentUiControl.Action.PREVIEW
            || action == AgentUiControl.Action.REMOVE
            || action == AgentUiControl.Action.MATERIALS;
    if (arguments.length != (requiresViewId ? 3 : 2)) {
      sender.sendMessage(getUsage());
      return;
    }
    UUID viewId = null;
    if (requiresViewId) {
      viewId = canonicalUuid(arguments[2]);
      if (viewId == null) {
        sender.sendMessage(VIEW_ID_INVALID_MESSAGE);
        return;
      }
    }
    AgentUiControl.Result result;
    try {
      result = uiControl.invoke(player.getUniqueId(), action, viewId);
    } catch (RuntimeException failure) {
      result = AgentUiControl.Result.CLIENT_UNAVAILABLE;
    }
    sender.sendMessage(
        result == AgentUiControl.Result.SENT ? UI_UPDATED_MESSAGE : UI_UNAVAILABLE_MESSAGE);
  }

  private void respondToProposal(CommandSender sender, String[] arguments) {
    if (!sender.hasPermission(PROPOSAL_PERMISSION)) {
      sender.sendMessage(PERMISSION_DENIED_MESSAGE);
      return;
    }
    if (!(sender instanceof Player player)) {
      sender.sendMessage(PLAYER_ONLY_MESSAGE);
      return;
    }
    if (arguments.length != 2) {
      sender.sendMessage(getUsage());
      return;
    }
    var proposalId = canonicalUuid(arguments[1]);
    if (proposalId == null) {
      sender.sendMessage(PROPOSAL_ID_INVALID_MESSAGE);
      return;
    }

    CompletionStage<ProposalResponseGateway.Result> completion;
    try {
      completion =
          arguments[0].equalsIgnoreCase("confirm")
              ? proposalResponses.confirm(player.getUniqueId(), proposalId)
              : proposalResponses.reject(player.getUniqueId(), proposalId);
      if (completion == null) {
        dispatchProposalReply(sender, ProposalResponseGateway.Result.FAILED);
        return;
      }
      completion.whenComplete(
          (result, error) ->
              dispatchProposalReply(
                  sender,
                  error == null && result != null
                      ? result
                      : ProposalResponseGateway.Result.FAILED));
    } catch (RuntimeException error) {
      dispatchProposalReply(sender, ProposalResponseGateway.Result.FAILED);
    }
  }

  private void dispatchProposalReply(CommandSender sender, ProposalResponseGateway.Result result) {
    var message =
        switch (result) {
          case CONFIRMED -> PROPOSAL_CONFIRMED_MESSAGE;
          case REJECTED -> PROPOSAL_REJECTED_MESSAGE;
          case UNAVAILABLE -> PROPOSAL_UNAVAILABLE_MESSAGE;
          case FAILED -> PROPOSAL_FAILED_MESSAGE;
        };
    try {
      replyDispatcher.accept(() -> sender.sendMessage(message));
    } catch (RuntimeException ignored) {
      // A failed reply dispatch must not expose gateway details or escape command execution.
    }
  }

  private void status(CommandSender sender, AgentStatus agentStatus) {
    if (!sender.hasPermission(STATUS_PERMISSION)) {
      sender.sendMessage(PERMISSION_DENIED_MESSAGE);
      return;
    }
    var snapshot = safeSnapshot();
    sender.sendMessage("AGMA status: " + agentStatus.state().name());
    sender.sendMessage("Desired mode: " + agentStatus.desiredMode().name());
    sender.sendMessage("Health: " + healthLabel(agentStatus.health()));
    sender.sendMessage("Runtime: " + (snapshot.runtimeConnected() ? "CONNECTED" : "DISCONNECTED"));
    sender.sendMessage("Protocol: " + snapshot.protocolVersion());
    sender.sendMessage("Active requests: " + snapshot.activeRequests());
  }

  private void doctor(CommandSender sender, AgentStatus agentStatus) {
    if (!sender.hasPermission(DOCTOR_PERMISSION)) {
      sender.sendMessage(PERMISSION_DENIED_MESSAGE);
      return;
    }
    var snapshot = safeSnapshot();
    sender.sendMessage("AGMA health: " + healthLabel(agentStatus.health()));
    sender.sendMessage("Paper component: " + snapshot.componentVersion());
    sender.sendMessage("Runtime protocol: " + snapshot.protocolVersion());
    sender.sendMessage(
        "Clients: online="
            + snapshot.clients().onlinePlayers()
            + " negotiated="
            + snapshot.clients().negotiatedClients());
    sender.sendMessage("Client protocols: " + counts(snapshot.clients().protocolCounts()));
    sender.sendMessage("Client features: " + counts(snapshot.clients().featureCounts()));
    sender.sendMessage(
        "Litematica adapters: " + counts(snapshot.clients().litematicaAdapterCounts()));
    for (var compatibility : snapshot.clients().litematicaCompatibility()) {
      sender.sendMessage(
          "Litematica compatibility: status="
              + compatibility.status()
              + " clients="
              + compatibility.clients()
              + " minecraft="
              + compatibility.minecraftVersion()
              + " fabric="
              + compatibility.fabricLoaderVersion()
              + " litematica="
              + compatibility.litematicaVersion()
              + " malilib="
              + compatibility.malilibVersion()
              + " adapter="
              + compatibility.adapterId());
    }
    if (snapshot.clients().omittedCompatibilityGroups() > 0) {
      sender.sendMessage(
          "Litematica compatibility groups omitted: "
              + snapshot.clients().omittedCompatibilityGroups());
    }
    sender.sendMessage(
        "Capabilities: generation="
            + snapshot.capabilities().generation()
            + " effective="
            + snapshot.capabilities().effective().size()
            + " disabled="
            + snapshot.capabilities().disabledCount());
    if (agentStatus.failureCode() != null) {
      sender.sendMessage("Core: " + agentStatus.failureCode());
    }
    for (var warning : agentStatus.warningCodes()) {
      sender.sendMessage("Warning: " + warning);
    }
  }

  private void capabilities(CommandSender sender) {
    if (!sender.hasPermission(CAPABILITIES_PERMISSION)) {
      sender.sendMessage(PERMISSION_DENIED_MESSAGE);
      return;
    }
    var summary = safeSnapshot().capabilities();
    sender.sendMessage(
        "Capability catalog: generation="
            + summary.generation()
            + " effective="
            + summary.effective().size()
            + " disabled="
            + summary.disabledCount());
    for (var capability : summary.effective()) {
      sender.sendMessage(
          "Capability: "
              + capability.id()
              + "@"
              + capability.version()
              + " sha256="
              + capability.sha256());
    }
    for (var diagnostic : summary.diagnosticCounts().entrySet()) {
      sender.sendMessage(
          "Capability diagnostic: " + diagnostic.getKey() + "=" + diagnostic.getValue());
    }
  }

  private void costs(CommandSender sender) {
    if (!sender.hasPermission(COSTS_PERMISSION)) {
      sender.sendMessage(PERMISSION_DENIED_MESSAGE);
      return;
    }
    try {
      var completion = management.costs();
      if (completion == null) {
        dispatchCosts(sender, AgentManagementGateway.CostsResult.failed());
        return;
      }
      completion.whenComplete(
          (result, error) ->
              dispatchCosts(
                  sender,
                  error == null && result != null
                      ? result
                      : AgentManagementGateway.CostsResult.failed()));
    } catch (RuntimeException error) {
      dispatchCosts(sender, AgentManagementGateway.CostsResult.failed());
    }
  }

  private void dispatchCosts(CommandSender sender, AgentManagementGateway.CostsResult result) {
    replyDispatcher.accept(
        () -> {
          if (!onlineNow()) {
            sender.sendMessage(OFFLINE_MESSAGE);
            return;
          }
          if (!sender.hasPermission(COSTS_PERMISSION)) {
            sender.sendMessage(PERMISSION_DENIED_MESSAGE);
            return;
          }
          if (result.status() != AgentManagementGateway.CostsStatus.AVAILABLE) {
            sender.sendMessage(
                result.status() == AgentManagementGateway.CostsStatus.UNAVAILABLE
                    ? "AI cost accounting is unavailable."
                    : "AI cost query failed. Try again.");
            return;
          }
          var snapshot = Objects.requireNonNull(result.snapshot());
          sender.sendMessage("AI costs: currency=USD");
          sender.sendMessage("Today: " + usage(snapshot.today()));
          sender.sendMessage("Month: " + usage(snapshot.month()));
          sender.sendMessage(
              "Monthly budget: usd="
                  + usd(snapshot.monthlyBudgetMicroUsd())
                  + " settledUsd="
                  + usd(snapshot.settledMonthlyCostMicroUsd())
                  + " activeReservationsUsd="
                  + usd(snapshot.activeReservationsMicroUsd())
                  + " remainingUsd="
                  + usd(snapshot.remainingMonthlyBudgetMicroUsd())
                  + " status="
                  + (snapshot.budgetExhausted() ? "EXHAUSTED" : "AVAILABLE"));
        });
  }

  private void reload(CommandSender sender) {
    if (!reloadAuthorizer.canReload(sender)) {
      sender.sendMessage(PERMISSION_DENIED_MESSAGE);
      return;
    }
    sender.sendMessage("AI reload started.");
    try {
      var completion = management.reload();
      if (completion == null) {
        dispatchReload(
            sender,
            new AgentManagementGateway.ReloadResult(AgentManagementGateway.ReloadStatus.FAILED));
        return;
      }
      completion.whenComplete(
          (result, error) ->
              dispatchReload(
                  sender,
                  error == null && result != null
                      ? result
                      : new AgentManagementGateway.ReloadResult(
                          AgentManagementGateway.ReloadStatus.FAILED)));
    } catch (RuntimeException error) {
      dispatchReload(
          sender,
          new AgentManagementGateway.ReloadResult(AgentManagementGateway.ReloadStatus.FAILED));
    }
  }

  private void dispatchReload(CommandSender sender, AgentManagementGateway.ReloadResult result) {
    var message =
        switch (result.status()) {
          case RELOADED -> "AI configuration reloaded.";
          case UNCHANGED -> "AI configuration unchanged.";
          case RESTART_REQUIRED -> "AI configuration change requires a server restart.";
          case INVALID_CONFIG ->
              "AI configuration is invalid; the previous configuration remains active.";
          case BUSY -> "AI reload already in progress.";
          case UNAVAILABLE -> "AI reload is unavailable.";
          case FAILED -> "AI reload failed; the previous configuration remains active.";
        };
    replyDispatcher.accept(
        () -> {
          if (!onlineNow()) {
            sender.sendMessage(OFFLINE_MESSAGE);
          } else if (!reloadAuthorizer.canReload(sender)) {
            sender.sendMessage(PERMISSION_DENIED_MESSAGE);
          } else {
            sender.sendMessage(message);
          }
        });
  }

  private boolean onlineNow() {
    try {
      var current = status.get();
      return current != null && current.state() == AgentState.ONLINE;
    } catch (RuntimeException error) {
      return false;
    }
  }

  private dev.minecraftagent.paper.management.ManagementSnapshot safeSnapshot() {
    try {
      var snapshot = management.snapshot();
      return snapshot == null
          ? dev.minecraftagent.paper.management.ManagementSnapshot.unavailable()
          : snapshot;
    } catch (RuntimeException error) {
      return dev.minecraftagent.paper.management.ManagementSnapshot.unavailable();
    }
  }

  private static String counts(java.util.Map<String, Integer> values) {
    return values.isEmpty()
        ? "none"
        : values.entrySet().stream()
            .map(entry -> entry.getKey() + "=" + entry.getValue())
            .collect(java.util.stream.Collectors.joining(","));
  }

  private static String usage(AgentManagementGateway.UsageWindow window) {
    return "period="
        + window.period()
        + " requests="
        + window.requests()
        + " providerCalls="
        + window.providerCalls()
        + " reported="
        + window.reportedProviderCalls()
        + " estimated="
        + window.estimatedProviderCalls()
        + " inputTokens="
        + window.inputTokens()
        + " outputTokens="
        + window.outputTokens()
        + " usd="
        + usd(window.costMicroUsd());
  }

  private static String usd(long microUsd) {
    return (microUsd / 1_000_000L)
        + "."
        + String.format(Locale.ROOT, "%06d", microUsd % 1_000_000L);
  }

  private static void handleSubmission(
      CommandSender sender, AgentRequestGateway.Submission submission) {
    switch (submission) {
      case ACCEPTED -> {
        // The terminal response is delivered privately by AgentRequestService.
      }
      case ALREADY_ACTIVE -> sender.sendMessage(REQUEST_ACTIVE_MESSAGE);
      case OFFLINE -> sender.sendMessage(OFFLINE_MESSAGE);
      case RUNTIME_UNAVAILABLE -> sender.sendMessage(REQUEST_UNAVAILABLE_MESSAGE);
      case INVALID_MESSAGE -> sender.sendMessage(MESSAGE_INVALID_MESSAGE);
    }
  }

  private static UUID canonicalUuid(String value) {
    try {
      var parsed = UUID.fromString(value);
      return parsed.toString().equals(value) ? parsed : null;
    } catch (IllegalArgumentException error) {
      return null;
    }
  }

  private static boolean isToggle(String argument) {
    return argument.equalsIgnoreCase("on") || argument.equalsIgnoreCase("off");
  }

  private static boolean isProposalResponse(String argument) {
    return argument.equalsIgnoreCase("confirm") || argument.equalsIgnoreCase("reject");
  }

  private static String healthLabel(AgentHealth health) {
    return switch (health) {
      case HEALTHY -> "ONLINE";
      case DEGRADED -> "DEGRADED";
      case UNAVAILABLE -> "UNAVAILABLE";
    };
  }
}
