package dev.minecraftagent.standalone.fabric;

import dev.minecraftagent.standalone.common.CancelReason;
import dev.minecraftagent.standalone.common.CatalogToolExecutor;
import dev.minecraftagent.standalone.common.ClientRuntimeController;
import dev.minecraftagent.standalone.common.LocalPlanPresentation;
import dev.minecraftagent.standalone.core.catalog.ResourceSearchIndex;
import dev.minecraftagent.standalone.core.contract.ResourceRef;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Search and disambiguation surface that remains usable when the Runtime is offline. */
public final class StandaloneCatalogScreen extends Screen {
  private static final int PANEL_MAXIMUM_WIDTH = 560;
  private static final int ROW_HEIGHT = 22;
  private static final int MAXIMUM_VISIBLE_RESULTS = 7;
  private static final int MAXIMUM_PLAN_AMOUNT = 999;
  private static final int PANEL_BACKGROUND = 0xEB15191D;
  private static final int PANEL_BORDER = 0xFF38434A;
  private static final int ACCENT = 0xFF4DAA91;
  private static final int PRIMARY_TEXT = 0xFFF2F5F6;
  private static final int SECONDARY_TEXT = 0xFFADB7BC;
  private static final int WARNING_TEXT = 0xFFE5B567;

  private final StandaloneCatalogService catalog;
  private final ClientRuntimeController runtime;
  private final CatalogToolExecutor tools;
  private final StandaloneUiState uiState;
  private EditBox queryBox;
  private String query = "";
  private List<ResourceSearchIndex.Candidate> candidates = List.of();
  private ResourceRef selected;
  private String status = "";
  private boolean planning;
  private boolean planFailed;

  public StandaloneCatalogScreen(
      StandaloneCatalogService catalog,
      ClientRuntimeController runtime,
      CatalogToolExecutor tools,
      StandaloneUiState uiState) {
    super(Component.translatable("screen.agma_standalone.catalog"));
    this.catalog = Objects.requireNonNull(catalog, "catalog");
    this.runtime = Objects.requireNonNull(runtime, "runtime");
    this.tools = Objects.requireNonNull(tools, "tools");
    this.uiState = Objects.requireNonNull(uiState, "uiState");
    selected = uiState.selected;
    if (selected != null) {
      query = selected.id();
    }
  }

  @Override
  protected void init() {
    var panelWidth = Math.min(PANEL_MAXIMUM_WIDTH, Math.max(280, width - 24));
    var left = (width - panelWidth) / 2;
    var panelHeight = Math.min(410, Math.max(180, height - 24));
    var top = Math.max(4, (height - panelHeight) / 2);
    var bottom = top + panelHeight;
    addTabs(left, top, panelWidth);
    var searchButtonWidth = 84;
    queryBox =
        new EditBox(
            font,
            left + 16,
            top + 52,
            panelWidth - searchButtonWidth - 40,
            20,
            Component.translatable("screen.agma_standalone.search"));
    queryBox.setMaxLength(256);
    queryBox.setValue(query);
    queryBox.setResponder(value -> query = value);
    queryBox.setHint(Component.translatable("screen.agma_standalone.search_hint"));
    addRenderableWidget(queryBox);
    addRenderableWidget(
        Button.builder(Component.translatable("screen.agma_standalone.search"), button -> search())
            .bounds(left + panelWidth - searchButtonWidth - 16, top + 52, searchButtonWidth, 20)
            .build());

    var context = catalog.context(minecraft);
    addRenderableWidget(
        contextButton(
            Component.translatable("screen.agma_standalone.use_held"),
            left + 16,
            top + 80,
            (panelWidth - 38) / 2,
            context.held().orElse(null)));
    addRenderableWidget(
        contextButton(
            Component.translatable("screen.agma_standalone.use_pointed"),
            left + 22 + (panelWidth - 38) / 2,
            top + 80,
            (panelWidth - 38) / 2,
            context.pointed().orElse(null)));

    var availableRows = Math.max(0, (bottom - top - 164) / ROW_HEIGHT);
    var visible = Math.min(Math.min(MAXIMUM_VISIBLE_RESULTS, availableRows), candidates.size());
    for (var index = 0; index < visible; index++) {
      var candidate = candidates.get(index);
      var label = candidate.resource().displayName() + "  [" + candidate.resource().id() + "]";
      var message = Component.literal(font.plainSubstrByWidth(label, panelWidth - 46));
      addRenderableWidget(
          Button.builder(message, button -> select(candidate.resource()))
              .bounds(left + 16, top + 114 + index * ROW_HEIGHT, panelWidth - 32, 20)
              .build());
    }
    if (candidates.isEmpty() && selected != null) {
      addAmountButtons(left, top, panelWidth);
    }
    if (candidates.isEmpty() && statePlanIsCurrent()) {
      if (panelHeight >= 206) {
        addRouteButtons(left, top, panelWidth);
      }
    } else if (candidates.isEmpty() && selected != null && !planning && !planFailed) {
      loadPlan();
    }
  }

  private void addRouteButtons(int left, int top, int panelWidth) {
    var routes = uiState.localPlan.planning().routes();
    uiState.selectedRoute = Math.max(0, Math.min(uiState.selectedRoute, routes.size() - 1));
    var routeWidth = Math.min(80, (panelWidth - 32) / routes.size());
    for (var index = 0; index < routes.size(); index++) {
      var routeIndex = index;
      var route =
          Button.builder(
                  Component.translatable("screen.agma_standalone.plan_route", index + 1),
                  ignored -> selectRoute(routeIndex))
              .bounds(left + 16 + index * routeWidth, top + 138, routeWidth, 20)
              .build();
      route.active = index != uiState.selectedRoute;
      addRenderableWidget(route);
    }
  }

  private void addAmountButtons(int left, int top, int panelWidth) {
    var controlsWidth = 136;
    var x = left + (panelWidth - controlsWidth) / 2;
    var decrease =
        Button.builder(Component.literal("-"), ignored -> adjustPlanAmount(-1))
            .bounds(x, top + 114, 24, 20)
            .build();
    decrease.active = uiState.localPlanAmount > 1 && !planning;
    addRenderableWidget(decrease);
    var amount =
        Button.builder(
                Component.translatable(
                    "screen.agma_standalone.plan_amount", uiState.localPlanAmount),
                ignored -> {})
            .bounds(x + 24, top + 114, 88, 20)
            .build();
    amount.active = false;
    addRenderableWidget(amount);
    var increase =
        Button.builder(Component.literal("+"), ignored -> adjustPlanAmount(1))
            .bounds(x + 112, top + 114, 24, 20)
            .build();
    increase.active = uiState.localPlanAmount < MAXIMUM_PLAN_AMOUNT && !planning;
    addRenderableWidget(increase);
  }

  private void addTabs(int left, int top, int panelWidth) {
    var tabWidth = (panelWidth - 32) / 3;
    var catalogTab =
        Button.builder(Component.translatable("screen.agma_standalone.tab_catalog"), ignored -> {})
            .bounds(left + 16, top + 8, tabWidth, 20)
            .build();
    catalogTab.active = false;
    addRenderableWidget(catalogTab);
    addRenderableWidget(
        Button.builder(
                Component.translatable("screen.agma_standalone.tab_ask"),
                ignored ->
                    minecraft.setScreen(
                        new StandaloneAssistantScreen(catalog, runtime, tools, uiState)))
            .bounds(left + 16 + tabWidth, top + 8, tabWidth, 20)
            .build());
    addRenderableWidget(
        Button.builder(
                Component.translatable("screen.agma_standalone.tab_settings"),
                ignored ->
                    minecraft.setScreen(
                        new StandaloneSettingsScreen(catalog, runtime, tools, uiState)))
            .bounds(left + 16 + tabWidth * 2, top + 8, panelWidth - 32 - tabWidth * 2, 20)
            .build());
  }

  private Button contextButton(
      Component label, int x, int y, int buttonWidth, ResourceRef resource) {
    var button =
        Button.builder(label, ignored -> select(resource)).bounds(x, y, buttonWidth, 20).build();
    button.active = resource != null;
    return button;
  }

  private void search() {
    if (query.isBlank()) {
      status = Component.translatable("screen.agma_standalone.query_required").getString();
      candidates = List.of();
      rebuildWidgets();
      return;
    }
    try {
      var result = catalog.search(query, 20);
      candidates = new ArrayList<>(result.candidates());
      selected =
          result.resolution() == ResourceSearchIndex.Resolution.EXACT
              ? result.candidates().getFirst().resource()
              : null;
      status =
          switch (result.resolution()) {
            case EXACT -> Component.translatable("screen.agma_standalone.exact_match").getString();
            case AMBIGUOUS ->
                Component.translatable("screen.agma_standalone.choose_match").getString();
            case NOT_FOUND -> Component.translatable("screen.agma_standalone.no_match").getString();
          };
    } catch (IllegalArgumentException | IllegalStateException failure) {
      candidates = List.of();
      selected = null;
      clearPlan();
      status = Component.translatable("screen.agma_standalone.catalog_unavailable").getString();
    }
    rebuildWidgets();
  }

  private void select(ResourceRef resource) {
    if (resource == null) {
      return;
    }
    cancelActiveRequest();
    selected = resource;
    uiState.selected = resource;
    clearPlan();
    query = resource.id();
    candidates = List.of();
    status = Component.translatable("screen.agma_standalone.selected", resource.id()).getString();
    rebuildWidgets();
  }

  private void cancelActiveRequest() {
    if (uiState.activeRequestId == null) {
      return;
    }
    runtime.cancel(uiState.activeRequestId, CancelReason.CONTEXT_CHANGED);
    uiState.activeRequestId = null;
    uiState.answer = "";
    uiState.sources = List.of();
    uiState.lastCostMicroUsd = 0;
    uiState.lastCostKind = null;
  }

  private void selectRoute(int routeIndex) {
    uiState.selectedRoute = routeIndex;
    uiState.localPlanScroll = 0;
    planFailed = false;
    rebuildWidgets();
  }

  private void adjustPlanAmount(int delta) {
    var next = Math.max(1, Math.min(MAXIMUM_PLAN_AMOUNT, uiState.localPlanAmount + delta));
    if (next == uiState.localPlanAmount) {
      return;
    }
    uiState.localPlanAmount = next;
    clearPlan();
    rebuildWidgets();
  }

  private void clearPlan() {
    uiState.localPlan = null;
    uiState.selectedRoute = 0;
    uiState.localPlanScroll = 0;
    planFailed = false;
  }

  private boolean statePlanIsCurrent() {
    return selected != null
        && uiState.localPlan != null
        && selected.equals(uiState.localPlan.target())
        && uiState
                .localPlan
                .requestedAmount()
                .compareTo(BigDecimal.valueOf(uiState.localPlanAmount))
            == 0
        && catalog
            .current()
            .map(snapshot -> snapshot.generationId().equals(uiState.localPlan.generationId()))
            .orElse(false);
  }

  private void loadPlan() {
    var target = selected;
    if (target == null) {
      return;
    }
    var amount = uiState.localPlanAmount;
    planning = true;
    status = Component.translatable("screen.agma_standalone.plan_loading").getString();
    tools
        .planLocal(target, BigDecimal.valueOf(amount))
        .whenComplete(
            (plan, failure) ->
                minecraft.execute(
                    () -> {
                      planning = false;
                      if (!target.equals(selected) || amount != uiState.localPlanAmount) {
                        if (minecraft.screen == this) {
                          rebuildWidgets();
                        }
                        return;
                      }
                      if (failure == null) {
                        uiState.localPlan = plan;
                        status =
                            Component.translatable("screen.agma_standalone.plan_ready").getString();
                      } else {
                        uiState.localPlan = null;
                        planFailed = true;
                        status =
                            Component.translatable("screen.agma_standalone.plan_unavailable")
                                .getString();
                      }
                      if (minecraft.screen == this) {
                        rebuildWidgets();
                      }
                    }));
  }

  @Override
  public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    renderTransparentBackground(graphics);
    var panelWidth = Math.min(PANEL_MAXIMUM_WIDTH, Math.max(280, width - 24));
    var left = (width - panelWidth) / 2;
    var panelHeight = Math.min(410, Math.max(180, height - 24));
    var top = Math.max(4, (height - panelHeight) / 2);
    var bottom = top + panelHeight;
    graphics.fill(left, top, left + panelWidth, bottom, PANEL_BACKGROUND);
    graphics.fill(left, top, left + panelWidth, top + 1, PANEL_BORDER);
    graphics.fill(left, top, left + 3, bottom, ACCENT);
    var snapshot = catalog.current();
    var catalogStatus =
        snapshot
            .map(
                value ->
                    Component.translatable(
                            "screen.agma_standalone.catalog_ready",
                            value.generationId(),
                            value.resources().size(),
                            value.processes().size())
                        .getString())
            .orElseGet(
                () ->
                    Component.translatable(
                            "screen.agma_standalone.catalog_state",
                            catalog.progress().phase().name().toLowerCase(java.util.Locale.ROOT))
                        .getString());
    graphics.drawString(font, catalogStatus, left + 16, top + 34, SECONDARY_TEXT, false);
    if (!status.isBlank()) {
      graphics.drawString(
          font,
          font.plainSubstrByWidth(status, panelWidth - 32),
          left + 16,
          bottom - 42,
          selected == null ? WARNING_TEXT : ACCENT,
          false);
    }
    if (selected != null) {
      var source =
          Component.translatable(
                  "screen.agma_standalone.local_confirmed",
                  selected.modName(),
                  selected.modVersion())
              .getString();
      graphics.drawString(
          font,
          font.plainSubstrByWidth(source, panelWidth - 32),
          left + 16,
          bottom - 28,
          SECONDARY_TEXT,
          false);
    }
    var viewer =
        OptionalViewerRegistry.selected()
            .map(adapter -> adapter.descriptor().id())
            .orElse("vanilla_client");
    var web = runtime.profileStore().isConfigured() && webConfigured() ? "configured" : "off";
    var sources =
        Component.translatable("screen.agma_standalone.data_sources", viewer, web).getString();
    graphics.drawString(
        font,
        font.plainSubstrByWidth(sources, panelWidth - 32),
        left + 16,
        bottom - 14,
        SECONDARY_TEXT,
        false);
    renderPlan(graphics, left, top, bottom, panelWidth);
    super.render(graphics, mouseX, mouseY, partialTick);
  }

  private void renderPlan(GuiGraphics graphics, int left, int top, int bottom, int panelWidth) {
    if (!candidates.isEmpty() || !statePlanIsCurrent()) {
      return;
    }
    var snapshot = catalog.current().orElse(null);
    if (snapshot == null) {
      return;
    }
    var lines = new ArrayList<String>();
    for (var section :
        LocalPlanPresentation.sections(uiState.localPlan, snapshot, uiState.selectedRoute)) {
      lines.add(Component.translatable(section.titleKey()).getString());
      section.entries().forEach(entry -> lines.add("  " + entry));
    }
    var startY = top + 164;
    var visibleLines = Math.max(0, (bottom - startY - 48) / 10);
    var maximumScroll = Math.max(0, lines.size() - visibleLines);
    uiState.localPlanScroll = Math.min(uiState.localPlanScroll, maximumScroll);
    for (var index = 0;
        index < Math.min(visibleLines, lines.size() - uiState.localPlanScroll);
        index++) {
      var line = lines.get(uiState.localPlanScroll + index);
      graphics.drawString(
          font,
          font.plainSubstrByWidth(line, panelWidth - 32),
          left + 16,
          startY + index * 10,
          line.startsWith("  ") ? PRIMARY_TEXT : ACCENT,
          false);
    }
  }

  @Override
  public boolean mouseScrolled(
      double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
    if (statePlanIsCurrent()) {
      uiState.localPlanScroll =
          Math.max(0, uiState.localPlanScroll - (int) Math.signum(verticalAmount));
      return true;
    }
    return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
  }

  @Override
  public boolean isPauseScreen() {
    return false;
  }

  private boolean webConfigured() {
    try {
      return runtime.profileStore().load().webEvidence() != null;
    } catch (RuntimeException failure) {
      return false;
    }
  }
}
