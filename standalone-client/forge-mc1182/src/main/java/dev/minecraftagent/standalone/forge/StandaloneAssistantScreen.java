package dev.minecraftagent.standalone.forge;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.minecraftagent.standalone.common.CatalogToolExecutor;
import dev.minecraftagent.standalone.common.ClientLifecycleState;
import dev.minecraftagent.standalone.common.ClientRuntimeController;
import dev.minecraftagent.standalone.common.RuntimeStatus;
import dev.minecraftagent.standalone.common.TextCompletion;
import dev.minecraftagent.standalone.common.TextRequest;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.network.chat.TranslatableComponent;

/** Runtime controls and bounded question/answer surface for Minecraft 1.18.2. */
public final class StandaloneAssistantScreen extends Screen {
  private static final int PANEL_MAXIMUM_WIDTH = 620;
  private static final int PANEL_BACKGROUND = 0xEB15191D;
  private static final int PANEL_BORDER = 0xFF38434A;
  private static final int ACCENT = 0xFF4DAA91;
  private static final int PRIMARY_TEXT = 0xFFF2F5F6;
  private static final int SECONDARY_TEXT = 0xFFADB7BC;
  private static final int WARNING_TEXT = 0xFFE5B567;
  private static final int STATUS_BACKGROUND = 0xF022292E;

  private final StandaloneCatalogService catalog;
  private final ClientRuntimeController runtime;
  private final CatalogToolExecutor tools;
  private final StandaloneUiState state;
  private EditBox questionBox;
  private RuntimeStatus liveRuntimeStatus;
  private boolean runtimeStatusUnavailable;
  private boolean runtimeStatusPending;
  private int runtimeStatusRefreshTicks = 40;

  public StandaloneAssistantScreen(
      StandaloneCatalogService catalog,
      ClientRuntimeController runtime,
      CatalogToolExecutor tools,
      StandaloneUiState state) {
    super(new TranslatableComponent("screen.agma_standalone.ask"));
    this.catalog = Objects.requireNonNull(catalog, "catalog");
    this.runtime = Objects.requireNonNull(runtime, "runtime");
    this.tools = Objects.requireNonNull(tools, "tools");
    this.state = Objects.requireNonNull(state, "state");
  }

  @Override
  protected void init() {
    var panelWidth = Math.min(PANEL_MAXIMUM_WIDTH, Math.max(300, width - 24));
    var panelHeight = Math.min(350, Math.max(250, height - 24));
    var left = (width - panelWidth) / 2;
    var top = (height - panelHeight) / 2;
    addTabs(left, top, panelWidth);
    var runtimePanel = RuntimePanelStatus.from(runtime.view(), runtimeStatusUnavailable);
    questionBox =
        new EditBox(
            font,
            left + 16,
            top + 68,
            panelWidth - 116,
            20,
            new TranslatableComponent("screen.agma_standalone.question"));
    questionBox.setMaxLength(TextRequest.MAXIMUM_TEXT_LENGTH);
    questionBox.setValue(state.question);
    questionBox.setResponder(value -> state.question = value);
    questionBox.setSuggestion(
        new TranslatableComponent("screen.agma_standalone.question_hint").getString());
    addRenderableWidget(questionBox);
    var ask =
        button(
            left + panelWidth - 94,
            top + 68,
            78,
            tr("screen.agma_standalone.send"),
            ignored -> send());
    ask.active =
        runtimePanel.state() == RuntimePanelStatus.State.RUNNING && state.activeRequestId == null;
    addRenderableWidget(ask);

    var half = (panelWidth - 38) / 2;
    var web = button(left + 16, top + 94, half, webLabel(), ignored -> toggleWeb());
    web.active = webConfigured() && state.activeRequestId == null;
    addRenderableWidget(web);
    var inventory =
        button(left + 22 + half, top + 94, half, inventoryLabel(), ignored -> toggleInventory());
    inventory.active =
        state.selected != null && catalog.current().isPresent() && state.activeRequestId == null;
    addRenderableWidget(inventory);

    if (!state.sources.isEmpty()) {
      var gap = 2;
      var sourceWidth = (panelWidth - 32 - gap * (state.sources.size() - 1)) / state.sources.size();
      for (var index = 0; index < state.sources.size(); index++) {
        var source = state.sources.get(index);
        var label = (index + 1) + " " + source.title();
        addRenderableWidget(
            button(
                left + 16 + index * (sourceWidth + gap),
                top + panelHeight - 54,
                sourceWidth,
                new TextComponent(font.plainSubstrByWidth(label, sourceWidth - 8)),
                ignored -> openSource(source)));
      }
    }

    var runtimeAction =
        button(
            left + 16,
            top + panelHeight - 28,
            120,
            tr(runtimePanel.action().labelKey()),
            ignored -> handleRuntimeAction(runtimePanel.action()));
    runtimeAction.active = runtimePanel.action() != RuntimePanelStatus.Action.WAIT;
    addRenderableWidget(runtimeAction);
    if (state.activeRequestId != null) {
      addRenderableWidget(
          button(
              left + 142,
              top + panelHeight - 28,
              86,
              tr("screen.agma_standalone.cancel"),
              ignored -> cancel()));
    }
    addRenderableWidget(
        button(
            left + panelWidth - 58,
            top + panelHeight - 28,
            20,
            new TextComponent("^"),
            ignored -> scroll(-1)));
    addRenderableWidget(
        button(
            left + panelWidth - 36,
            top + panelHeight - 28,
            20,
            new TextComponent("v"),
            ignored -> scroll(1)));
  }

  private void handleRuntimeAction(RuntimePanelStatus.Action action) {
    switch (action) {
      case CONFIGURE ->
          minecraft.setScreen(new StandaloneSettingsScreen(catalog, runtime, tools, state));
      case START, RETRY -> startRuntime();
      case CANCEL_START, STOP -> stopRuntime();
      case RESTART -> restartRuntime();
      case WAIT -> {
        // The disabled action documents the current transition without accepting input.
      }
    }
  }

  private void addTabs(int left, int top, int panelWidth) {
    var tabWidth = (panelWidth - 32) / 3;
    addRenderableWidget(
        button(
            left + 16,
            top + 12,
            tabWidth,
            tr("screen.agma_standalone.tab_catalog"),
            ignored ->
                minecraft.setScreen(new StandaloneCatalogScreen(catalog, runtime, tools, state))));
    var ask =
        button(
            left + 16 + tabWidth,
            top + 12,
            tabWidth,
            tr("screen.agma_standalone.tab_ask"),
            ignored -> {});
    ask.active = false;
    addRenderableWidget(ask);
    addRenderableWidget(
        button(
            left + 16 + tabWidth * 2,
            top + 12,
            panelWidth - 32 - tabWidth * 2,
            tr("screen.agma_standalone.tab_settings"),
            ignored ->
                minecraft.setScreen(new StandaloneSettingsScreen(catalog, runtime, tools, state))));
  }

  private void startRuntime() {
    liveRuntimeStatus = null;
    runtimeStatusUnavailable = false;
    var startup = runtime.start(tools);
    rebuild();
    startup.whenComplete(
        (snapshot, failure) ->
            minecraft.execute(
                () -> {
                  runtimeStatusUnavailable = failure != null;
                  rebuild();
                }));
  }

  private void stopRuntime() {
    liveRuntimeStatus = null;
    runtimeStatusUnavailable = false;
    runtime
        .stop()
        .whenComplete(
            (snapshot, failure) ->
                minecraft.execute(
                    () -> {
                      state.activeRequestId = null;
                      runtimeStatusUnavailable = failure != null;
                      rebuild();
                    }));
    rebuild();
  }

  private void restartRuntime() {
    liveRuntimeStatus = null;
    runtimeStatusUnavailable = false;
    runtime
        .stop()
        .whenComplete(
            (snapshot, failure) ->
                minecraft.execute(
                    () -> {
                      if (failure != null) {
                        runtimeStatusUnavailable = true;
                        rebuild();
                        return;
                      }
                      startRuntime();
                    }));
    rebuild();
  }

  private void send() {
    if (state.question.isBlank() || state.activeRequestId != null) {
      state.status = tr("screen.agma_standalone.question_required").getString();
      return;
    }
    var requestId = UUID.randomUUID();
    TextRequest.InventoryAuthorization inventoryAuthorization = null;
    try {
      var canonicalTarget =
          state.selected == null
              ? null
              : tools.canonicalPlanningTarget(state.selected).orElse(null);
      if (state.inventoryOnce && canonicalTarget == null) {
        throw new IllegalStateException("inventory requires a current canonical target");
      }
      if (state.inventoryOnce) {
        var resourceIds = tools.inventoryResourceIdsForPlan(canonicalTarget.id());
        var grant =
            tools.authorizeInventoryOnce(
                requestId,
                runtime.view().profile().installationId(),
                resourceIds,
                Duration.ofMinutes(5));
        inventoryAuthorization =
            new TextRequest.InventoryAuthorization(
                grant.authorizationId(), grant.generationId(), grant.resourceIds());
      }
      var webAuthorization =
          state.webOnce ? TextRequest.WebAuthorization.ONCE : TextRequest.WebAuthorization.OFF;
      TextRequest.Target target =
          canonicalTarget == null
              ? null
              : new TextRequest.Target(
                  canonicalTarget.id(),
                  canonicalTarget.displayName(),
                  canonicalTarget.modId(),
                  canonicalTarget.modVersion());
      TextRequest.LocalContext localContext = null;
      var snapshot = catalog.current().orElse(null);
      if (target != null
          && snapshot != null
          && snapshot.generationId().equals(canonicalTarget.source().generationId())) {
        localContext = new TextRequest.LocalContext("1.18.2", snapshot.generationId(), target);
      }
      TextRequest.WebContext webContext = null;
      if (state.webOnce) {
        webContext = new TextRequest.WebContext("1.18.2", target, null);
      }
      var timeout = runtime.profileStore().load().model().timeoutSeconds();
      var sessionId =
          runtime.profileStore().load().privacy().storeConversations() ? state.sessionId : null;
      var request =
          new TextRequest(
              requestId,
              sessionId,
              state.question,
              Duration.ofSeconds(timeout),
              localContext,
              webAuthorization,
              webContext,
              inventoryAuthorization);
      state.activeRequestId = requestId;
      state.answer = "";
      state.lastCostMicroUsd = 0;
      state.lastCostKind = null;
      state.sources = List.of();
      state.answerScroll = 0;
      state.status = tr("screen.agma_standalone.request_running").getString();
      state.webOnce = false;
      state.inventoryOnce = false;
      runtime
          .request(request)
          .whenComplete(
              (completion, failure) ->
                  minecraft.execute(() -> complete(requestId, completion, failure)));
      rebuild();
    } catch (RuntimeException failure) {
      tools.revokeInventoryAuthorizations();
      state.status = tr("screen.agma_standalone.request_rejected").getString();
      state.webOnce = false;
      state.inventoryOnce = false;
      rebuild();
    }
  }

  private void complete(UUID requestId, TextCompletion completion, Throwable failure) {
    if (!requestId.equals(state.activeRequestId)) {
      return;
    }
    state.activeRequestId = null;
    if (failure != null || completion == null) {
      state.status = tr("screen.agma_standalone.request_failed").getString();
    } else if (completion.status() == TextCompletion.Status.COMPLETED) {
      state.answer = completion.text();
      state.sessionId = completion.sessionId();
      state.lastCostMicroUsd = completion.costMicroUsd();
      state.lastCostKind = completion.costKind();
      state.sources = completion.sources();
      state.status = tr("screen.agma_standalone.request_complete").getString();
    } else {
      state.lastCostMicroUsd = 0;
      state.lastCostKind = null;
      state.sources = List.of();
      state.status = completion.errorCode();
    }
    rebuild();
  }

  private void cancel() {
    if (state.activeRequestId != null) {
      runtime.cancel(state.activeRequestId);
      state.status = tr("screen.agma_standalone.request_cancelling").getString();
    }
  }

  private void toggleWeb() {
    state.webOnce = !state.webOnce;
    rebuild();
  }

  private void toggleInventory() {
    state.inventoryOnce = !state.inventoryOnce;
    rebuild();
  }

  private Component webLabel() {
    return tr(
        state.webOnce
            ? "screen.agma_standalone.web_once_on"
            : "screen.agma_standalone.web_once_off");
  }

  private Component inventoryLabel() {
    return tr(
        state.inventoryOnce
            ? "screen.agma_standalone.inventory_once_on"
            : "screen.agma_standalone.inventory_once_off");
  }

  private boolean webConfigured() {
    try {
      return runtime.profileStore().isConfigured()
          && runtime.profileStore().load().webEvidence() != null;
    } catch (RuntimeException failure) {
      return false;
    }
  }

  private void scroll(int delta) {
    state.answerScroll = Math.max(0, state.answerScroll + delta);
  }

  private void openSource(TextCompletion.Source source) {
    try {
      Util.getPlatform().openUri(source.url());
    } catch (RuntimeException failure) {
      state.status = tr("screen.agma_standalone.source_open_failed").getString();
    }
  }

  private void rebuild() {
    clearWidgets();
    init();
  }

  @Override
  public void tick() {
    super.tick();
    if (questionBox != null) {
      questionBox.tick();
    }
    runtimeStatusRefreshTicks++;
    if (runtimeStatusRefreshTicks >= 40) {
      runtimeStatusRefreshTicks = 0;
      refreshRuntimeStatus();
    }
  }

  private void refreshRuntimeStatus() {
    if (runtimeStatusPending) {
      return;
    }
    var view = runtime.view();
    if (view.profile().state() != ClientLifecycleState.READY) {
      liveRuntimeStatus = null;
      runtimeStatusUnavailable = false;
      return;
    }
    runtimeStatusPending = true;
    runtime
        .queryStatus()
        .whenComplete(
            (status, failure) ->
                minecraft.execute(
                    () -> {
                      runtimeStatusPending = false;
                      if (minecraft.screen != this) {
                        return;
                      }
                      var wasUnavailable = runtimeStatusUnavailable;
                      liveRuntimeStatus = failure == null ? status : null;
                      runtimeStatusUnavailable =
                          failure != null
                              || status == null
                              || status.state() != RuntimeStatus.State.READY;
                      if (wasUnavailable != runtimeStatusUnavailable) {
                        rebuild();
                      }
                    }));
  }

  @Override
  public void render(PoseStack pose, int mouseX, int mouseY, float partialTick) {
    renderBackground(pose);
    var panelWidth = Math.min(PANEL_MAXIMUM_WIDTH, Math.max(300, width - 24));
    var panelHeight = Math.min(350, Math.max(250, height - 24));
    var left = (width - panelWidth) / 2;
    var top = (height - panelHeight) / 2;
    fill(pose, left, top, left + panelWidth, top + panelHeight, PANEL_BACKGROUND);
    fill(pose, left, top, left + panelWidth, top + 1, PANEL_BORDER);
    fill(pose, left, top, left + 3, top + panelHeight, ACCENT);
    renderRuntimePanel(pose, left, top, panelWidth);
    if (state.lastCostKind != null) {
      var cost =
          new TranslatableComponent(
              "screen.agma_standalone.request_cost",
              BigDecimal.valueOf(state.lastCostMicroUsd, 6).toPlainString(),
              tr(costKindKey(state.lastCostKind)));
      font.draw(
          pose,
          font.plainSubstrByWidth(cost.getString(), panelWidth - 32),
          left + 16,
          top + 132,
          SECONDARY_TEXT);
    }
    var selected =
        state.selected == null
            ? tr("screen.agma_standalone.no_target").getString()
            : state.selected.displayName() + " [" + state.selected.id() + "]";
    font.draw(
        pose,
        font.plainSubstrByWidth(selected, panelWidth - 32),
        left + 16,
        top + 120,
        SECONDARY_TEXT);
    var answerTop = state.lastCostKind == null ? 142 : 154;
    var lines = font.split(new TextComponent(state.answer), panelWidth - 44);
    var maximumLines =
        Math.max(
            2,
            (panelHeight
                    - (state.sources.isEmpty() ? 190 : 216)
                    - (state.lastCostKind == null ? 0 : 12))
                / 10);
    var maximumScroll = Math.max(0, lines.size() - maximumLines);
    state.answerScroll = Math.min(state.answerScroll, maximumScroll);
    for (var index = 0;
        index < Math.min(maximumLines, lines.size() - state.answerScroll);
        index++) {
      font.draw(
          pose,
          lines.get(state.answerScroll + index),
          left + 16,
          top + answerTop + index * 10,
          PRIMARY_TEXT);
    }
    if (!state.status.isBlank()) {
      font.draw(
          pose,
          font.plainSubstrByWidth(state.status, Math.max(20, panelWidth - 266)),
          left + 238,
          top + panelHeight - 22,
          state.status.endsWith("FAILED") ? WARNING_TEXT : ACCENT);
    }
    super.render(pose, mouseX, mouseY, partialTick);
  }

  private void renderRuntimePanel(PoseStack pose, int left, int top, int panelWidth) {
    var panel = RuntimePanelStatus.from(runtime.view(), runtimeStatusUnavailable);
    fill(pose, left + 16, top + 38, left + panelWidth - 16, top + 62, STATUS_BACKGROUND);
    fill(pose, left + 22, top + 45, left + 29, top + 52, panel.state().color());
    font.draw(
        pose,
        tr(panel.state().titleKey()).withStyle(ChatFormatting.BOLD),
        left + 36,
        top + 40,
        panel.state().color());
    var detail =
        panel.state() == RuntimePanelStatus.State.RUNNING && liveRuntimeStatus != null
            ? new TranslatableComponent(
                "screen.agma_standalone.runtime_running_counts",
                liveRuntimeStatus.activeRequests(),
                liveRuntimeStatus.queuedRequests())
            : tr(panel.state().detailKey());
    font.draw(
        pose,
        font.plainSubstrByWidth(detail.getString(), panelWidth - 56),
        left + 36,
        top + 51,
        SECONDARY_TEXT);
  }

  private static TranslatableComponent tr(String key) {
    return new TranslatableComponent(key);
  }

  private static Button button(int x, int y, int width, Component label, Button.OnPress action) {
    return new Button(x, y, width, 20, label, action);
  }

  @Override
  public boolean isPauseScreen() {
    return false;
  }

  private static String costKindKey(TextCompletion.CostKind kind) {
    return switch (kind) {
      case REPORTED -> "screen.agma_standalone.cost_reported";
      case ESTIMATED -> "screen.agma_standalone.cost_estimated";
      case MIXED -> "screen.agma_standalone.cost_mixed";
    };
  }
}
