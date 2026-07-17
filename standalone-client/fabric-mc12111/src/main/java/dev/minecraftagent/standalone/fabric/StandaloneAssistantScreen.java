package dev.minecraftagent.standalone.fabric;

import dev.minecraftagent.standalone.common.CatalogToolExecutor;
import dev.minecraftagent.standalone.common.ClientLifecycleState;
import dev.minecraftagent.standalone.common.ClientRuntimeController;
import dev.minecraftagent.standalone.common.TextCompletion;
import dev.minecraftagent.standalone.common.TextRequest;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;

/** Runtime controls and bounded question/answer surface. */
public final class StandaloneAssistantScreen extends Screen {
  private static final int PANEL_MAXIMUM_WIDTH = 620;
  private static final int PANEL_BACKGROUND = 0xEB15191D;
  private static final int PANEL_BORDER = 0xFF38434A;
  private static final int ACCENT = 0xFF4DAA91;
  private static final int PRIMARY_TEXT = 0xFFF2F5F6;
  private static final int SECONDARY_TEXT = 0xFFADB7BC;
  private static final int WARNING_TEXT = 0xFFE5B567;

  private final StandaloneCatalogService catalog;
  private final ClientRuntimeController runtime;
  private final CatalogToolExecutor tools;
  private final StandaloneUiState state;
  private EditBox questionBox;

  public StandaloneAssistantScreen(
      StandaloneCatalogService catalog,
      ClientRuntimeController runtime,
      CatalogToolExecutor tools,
      StandaloneUiState state) {
    super(Component.translatable("screen.agma_standalone.ask"));
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

    questionBox =
        new EditBox(
            font,
            left + 16,
            top + 68,
            panelWidth - 116,
            20,
            Component.translatable("screen.agma_standalone.question"));
    questionBox.setMaxLength(TextRequest.MAXIMUM_TEXT_LENGTH);
    questionBox.setValue(state.question);
    questionBox.setResponder(value -> state.question = value);
    questionBox.setHint(Component.translatable("screen.agma_standalone.question_hint"));
    addRenderableWidget(questionBox);
    var ask =
        Button.builder(Component.translatable("screen.agma_standalone.send"), ignored -> send())
            .bounds(left + panelWidth - 94, top + 68, 78, 20)
            .build();
    ask.active =
        runtime.view().profile().state() == ClientLifecycleState.READY
            && state.activeRequestId == null;
    addRenderableWidget(ask);

    var half = (panelWidth - 38) / 2;
    var web =
        Button.builder(webLabel(), ignored -> toggleWeb())
            .bounds(left + 16, top + 94, half, 20)
            .build();
    web.active = webConfigured() && state.activeRequestId == null;
    addRenderableWidget(web);
    var inventory =
        Button.builder(inventoryLabel(), ignored -> toggleInventory())
            .bounds(left + 22 + half, top + 94, half, 20)
            .build();
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
            Button.builder(
                    Component.literal(font.plainSubstrByWidth(label, sourceWidth - 8)),
                    ignored -> openSource(source))
                .bounds(
                    left + 16 + index * (sourceWidth + gap),
                    top + panelHeight - 54,
                    sourceWidth,
                    20)
                .build());
      }
    }

    var lifecycle = runtime.view().profile().state();
    if (lifecycle == ClientLifecycleState.READY
        || lifecycle == ClientLifecycleState.STARTING
        || lifecycle == ClientLifecycleState.STOPPING) {
      var stop =
          Button.builder(
                  Component.translatable("screen.agma_standalone.stop_runtime"),
                  ignored -> stopRuntime())
              .bounds(left + 16, top + panelHeight - 28, 120, 20)
              .build();
      stop.active = lifecycle != ClientLifecycleState.STOPPING;
      addRenderableWidget(stop);
    } else {
      var start =
          Button.builder(
                  Component.translatable("screen.agma_standalone.start_runtime"),
                  ignored -> startRuntime())
              .bounds(left + 16, top + panelHeight - 28, 120, 20)
              .build();
      start.active = lifecycle != ClientLifecycleState.UNCONFIGURED;
      addRenderableWidget(start);
    }
    if (state.activeRequestId != null) {
      addRenderableWidget(
          Button.builder(
                  Component.translatable("screen.agma_standalone.cancel"), ignored -> cancel())
              .bounds(left + 142, top + panelHeight - 28, 86, 20)
              .build());
    }
    addRenderableWidget(
        Button.builder(Component.literal("^"), ignored -> scroll(-1))
            .bounds(left + panelWidth - 58, top + panelHeight - 28, 20, 20)
            .build());
    addRenderableWidget(
        Button.builder(Component.literal("v"), ignored -> scroll(1))
            .bounds(left + panelWidth - 36, top + panelHeight - 28, 20, 20)
            .build());
  }

  private void addTabs(int left, int top, int panelWidth) {
    var tabWidth = (panelWidth - 32) / 3;
    addRenderableWidget(
        Button.builder(
                Component.translatable("screen.agma_standalone.tab_catalog"),
                ignored ->
                    minecraft.setScreen(
                        new StandaloneCatalogScreen(catalog, runtime, tools, state)))
            .bounds(left + 16, top + 12, tabWidth, 20)
            .build());
    var ask =
        Button.builder(Component.translatable("screen.agma_standalone.tab_ask"), ignored -> {})
            .bounds(left + 16 + tabWidth, top + 12, tabWidth, 20)
            .build();
    ask.active = false;
    addRenderableWidget(ask);
    addRenderableWidget(
        Button.builder(
                Component.translatable("screen.agma_standalone.tab_settings"),
                ignored ->
                    minecraft.setScreen(
                        new StandaloneSettingsScreen(catalog, runtime, tools, state)))
            .bounds(left + 16 + tabWidth * 2, top + 12, panelWidth - 32 - tabWidth * 2, 20)
            .build());
  }

  private void startRuntime() {
    state.status = Component.translatable("screen.agma_standalone.runtime_starting").getString();
    rebuildWidgets();
    runtime
        .start(tools)
        .whenComplete(
            (snapshot, failure) ->
                minecraft.execute(
                    () -> {
                      state.status =
                          failure == null
                              ? Component.translatable("screen.agma_standalone.runtime_ready")
                                  .getString()
                              : safeRuntimeFailure();
                      rebuildWidgets();
                    }));
  }

  private void stopRuntime() {
    state.status = Component.translatable("screen.agma_standalone.runtime_stopping").getString();
    runtime
        .stop()
        .whenComplete(
            (snapshot, failure) ->
                minecraft.execute(
                    () -> {
                      state.activeRequestId = null;
                      state.status =
                          Component.translatable("screen.agma_standalone.runtime_stopped")
                              .getString();
                      rebuildWidgets();
                    }));
    rebuildWidgets();
  }

  private void send() {
    if (state.question.isBlank() || state.activeRequestId != null) {
      state.status = Component.translatable("screen.agma_standalone.question_required").getString();
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
        var profile = runtime.view().profile();
        var resourceIds = tools.inventoryResourceIdsForPlan(canonicalTarget.id());
        var grant =
            tools.authorizeInventoryOnce(
                requestId, profile.installationId(), resourceIds, Duration.ofMinutes(5));
        inventoryAuthorization =
            new TextRequest.InventoryAuthorization(
                grant.authorizationId(), grant.generationId(), grant.resourceIds());
      }
      TextRequest.WebAuthorization webAuthorization =
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
        localContext = new TextRequest.LocalContext("1.21.11", snapshot.generationId(), target);
      }
      TextRequest.WebContext webContext = null;
      if (state.webOnce) {
        webContext = new TextRequest.WebContext("1.21.11", target, null);
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
      state.status = Component.translatable("screen.agma_standalone.request_running").getString();
      state.webOnce = false;
      state.inventoryOnce = false;
      runtime
          .request(request)
          .whenComplete(
              (completion, failure) ->
                  minecraft.execute(() -> complete(requestId, completion, failure)));
      rebuildWidgets();
    } catch (RuntimeException failure) {
      tools.revokeInventoryAuthorizations();
      state.status = Component.translatable("screen.agma_standalone.request_rejected").getString();
      state.webOnce = false;
      state.inventoryOnce = false;
      rebuildWidgets();
    }
  }

  private void complete(UUID requestId, TextCompletion completion, Throwable failure) {
    if (!requestId.equals(state.activeRequestId)) {
      return;
    }
    state.activeRequestId = null;
    if (failure != null || completion == null) {
      state.status = Component.translatable("screen.agma_standalone.request_failed").getString();
    } else if (completion.status() == TextCompletion.Status.COMPLETED) {
      state.answer = completion.text();
      state.sessionId = completion.sessionId();
      state.lastCostMicroUsd = completion.costMicroUsd();
      state.lastCostKind = completion.costKind();
      state.sources = completion.sources();
      state.status = Component.translatable("screen.agma_standalone.request_complete").getString();
    } else {
      state.lastCostMicroUsd = 0;
      state.lastCostKind = null;
      state.sources = List.of();
      state.status = completion.errorCode();
    }
    rebuildWidgets();
  }

  private void cancel() {
    if (state.activeRequestId != null) {
      runtime.cancel(state.activeRequestId);
      state.status =
          Component.translatable("screen.agma_standalone.request_cancelling").getString();
    }
  }

  private void toggleWeb() {
    state.webOnce = !state.webOnce;
    rebuildWidgets();
  }

  private void toggleInventory() {
    state.inventoryOnce = !state.inventoryOnce;
    rebuildWidgets();
  }

  private Component webLabel() {
    return Component.translatable(
        state.webOnce
            ? "screen.agma_standalone.web_once_on"
            : "screen.agma_standalone.web_once_off");
  }

  private Component inventoryLabel() {
    return Component.translatable(
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

  private String safeRuntimeFailure() {
    var code = runtime.view().startupFailureCode();
    return code == null ? "RUNTIME_START_FAILED" : code;
  }

  private void scroll(int delta) {
    state.answerScroll = Math.max(0, state.answerScroll + delta);
  }

  private void openSource(TextCompletion.Source source) {
    try {
      Util.getPlatform().openUri(source.url());
    } catch (RuntimeException failure) {
      state.status =
          Component.translatable("screen.agma_standalone.source_open_failed").getString();
    }
  }

  @Override
  public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    renderTransparentBackground(graphics);
    var panelWidth = Math.min(PANEL_MAXIMUM_WIDTH, Math.max(300, width - 24));
    var panelHeight = Math.min(350, Math.max(250, height - 24));
    var left = (width - panelWidth) / 2;
    var top = (height - panelHeight) / 2;
    graphics.fill(left, top, left + panelWidth, top + panelHeight, PANEL_BACKGROUND);
    graphics.fill(left, top, left + panelWidth, top + 1, PANEL_BORDER);
    graphics.fill(left, top, left + 3, top + panelHeight, ACCENT);
    var runtimeState = runtime.view().profile().state().name().toLowerCase(java.util.Locale.ROOT);
    graphics.drawString(
        font,
        Component.translatable("screen.agma_standalone.runtime_state", runtimeState),
        left + 16,
        top + 42,
        SECONDARY_TEXT,
        false);
    if (state.lastCostKind != null) {
      var cost =
          Component.translatable(
              "screen.agma_standalone.request_cost",
              BigDecimal.valueOf(state.lastCostMicroUsd, 6).toPlainString(),
              Component.translatable(costKindKey(state.lastCostKind)));
      graphics.drawString(
          font,
          font.plainSubstrByWidth(cost.getString(), panelWidth - 32),
          left + 16,
          top + 54,
          SECONDARY_TEXT,
          false);
    }
    var selected =
        state.selected == null
            ? Component.translatable("screen.agma_standalone.no_target").getString()
            : state.selected.displayName() + " [" + state.selected.id() + "]";
    graphics.drawString(
        font,
        font.plainSubstrByWidth(selected, panelWidth - 32),
        left + 16,
        top + 120,
        SECONDARY_TEXT,
        false);
    var lines = font.split(Component.literal(state.answer), panelWidth - 44);
    var maximumLines = Math.max(2, (panelHeight - (state.sources.isEmpty() ? 190 : 216)) / 10);
    var maximumScroll = Math.max(0, lines.size() - maximumLines);
    state.answerScroll = Math.min(state.answerScroll, maximumScroll);
    for (var index = 0;
        index < Math.min(maximumLines, lines.size() - state.answerScroll);
        index++) {
      graphics.drawString(
          font,
          lines.get(state.answerScroll + index),
          left + 16,
          top + 142 + index * 10,
          PRIMARY_TEXT,
          false);
    }
    if (!state.status.isBlank()) {
      graphics.drawString(
          font,
          font.plainSubstrByWidth(state.status, panelWidth - 266),
          left + 238,
          top + panelHeight - 22,
          state.status.endsWith("FAILED") ? WARNING_TEXT : ACCENT,
          false);
    }
    super.render(graphics, mouseX, mouseY, partialTick);
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
