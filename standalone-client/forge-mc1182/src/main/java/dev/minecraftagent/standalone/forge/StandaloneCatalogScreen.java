package dev.minecraftagent.standalone.forge;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.minecraftagent.standalone.common.CancelReason;
import dev.minecraftagent.standalone.common.CatalogToolExecutor;
import dev.minecraftagent.standalone.common.ClientRuntimeController;
import dev.minecraftagent.standalone.common.LocalPlanPresentation;
import dev.minecraftagent.standalone.core.catalog.ResourceSearchIndex;
import dev.minecraftagent.standalone.core.contract.ResourceRef;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.network.chat.TranslatableComponent;

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
    super(new TranslatableComponent("screen.agma_standalone.catalog"));
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
            new TranslatableComponent("screen.agma_standalone.search"));
    queryBox.setMaxLength(256);
    queryBox.setValue(query);
    queryBox.setResponder(value -> query = value);
    queryBox.setSuggestion(
        new TranslatableComponent("screen.agma_standalone.search_hint").getString());
    addRenderableWidget(queryBox);
    addRenderableWidget(
        new Button(
            left + panelWidth - searchButtonWidth - 16,
            top + 52,
            searchButtonWidth,
            20,
            new TranslatableComponent("screen.agma_standalone.search"),
            button -> search()));

    var context = catalog.context(minecraft);
    addRenderableWidget(
        contextButton(
            new TranslatableComponent("screen.agma_standalone.use_held"),
            left + 16,
            top + 80,
            (panelWidth - 38) / 2,
            context.held().orElse(null)));
    addRenderableWidget(
        contextButton(
            new TranslatableComponent("screen.agma_standalone.use_pointed"),
            left + 22 + (panelWidth - 38) / 2,
            top + 80,
            (panelWidth - 38) / 2,
            context.pointed().orElse(null)));

    var availableRows = Math.max(0, (bottom - top - 164) / ROW_HEIGHT);
    var visible = Math.min(Math.min(MAXIMUM_VISIBLE_RESULTS, availableRows), candidates.size());
    for (var index = 0; index < visible; index++) {
      var candidate = candidates.get(index);
      var label = candidate.resource().displayName() + "  [" + candidate.resource().id() + "]";
      var message = new TextComponent(font.plainSubstrByWidth(label, panelWidth - 46));
      addRenderableWidget(
          new Button(
              left + 16,
              top + 114 + index * ROW_HEIGHT,
              panelWidth - 32,
              20,
              message,
              button -> select(candidate.resource())));
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
          button(
              left + 16 + index * routeWidth,
              top + 138,
              routeWidth,
              new TranslatableComponent("screen.agma_standalone.plan_route", index + 1),
              ignored -> selectRoute(routeIndex));
      route.active = index != uiState.selectedRoute;
      addRenderableWidget(route);
    }
  }

  private void addAmountButtons(int left, int top, int panelWidth) {
    var controlsWidth = 136;
    var x = left + (panelWidth - controlsWidth) / 2;
    var decrease =
        button(x, top + 114, 24, new TextComponent("-"), ignored -> adjustPlanAmount(-1));
    decrease.active = uiState.localPlanAmount > 1 && !planning;
    addRenderableWidget(decrease);
    var amount =
        button(
            x + 24,
            top + 114,
            88,
            new TranslatableComponent(
                "screen.agma_standalone.plan_amount", uiState.localPlanAmount),
            ignored -> {});
    amount.active = false;
    addRenderableWidget(amount);
    var increase =
        button(x + 112, top + 114, 24, new TextComponent("+"), ignored -> adjustPlanAmount(1));
    increase.active = uiState.localPlanAmount < MAXIMUM_PLAN_AMOUNT && !planning;
    addRenderableWidget(increase);
  }

  private void addTabs(int left, int top, int panelWidth) {
    var tabWidth = (panelWidth - 32) / 3;
    var catalogTab =
        new Button(
            left + 16,
            top + 8,
            tabWidth,
            20,
            new TranslatableComponent("screen.agma_standalone.tab_catalog"),
            ignored -> {});
    catalogTab.active = false;
    addRenderableWidget(catalogTab);
    addRenderableWidget(
        new Button(
            left + 16 + tabWidth,
            top + 8,
            tabWidth,
            20,
            new TranslatableComponent("screen.agma_standalone.tab_ask"),
            ignored ->
                minecraft.setScreen(
                    new StandaloneAssistantScreen(catalog, runtime, tools, uiState))));
    addRenderableWidget(
        new Button(
            left + 16 + tabWidth * 2,
            top + 8,
            panelWidth - 32 - tabWidth * 2,
            20,
            new TranslatableComponent("screen.agma_standalone.tab_settings"),
            ignored ->
                minecraft.setScreen(
                    new StandaloneSettingsScreen(catalog, runtime, tools, uiState))));
  }

  private Button contextButton(
      Component label, int x, int y, int buttonWidth, ResourceRef resource) {
    var button = new Button(x, y, buttonWidth, 20, label, ignored -> select(resource));
    button.active = resource != null;
    return button;
  }

  private void search() {
    if (query.isBlank()) {
      status = new TranslatableComponent("screen.agma_standalone.query_required").getString();
      candidates = List.of();
      rebuild();
      return;
    }
    try {
      var result = catalog.search(query, 20);
      candidates = new ArrayList<>(result.candidates());
      selected =
          result.resolution() == ResourceSearchIndex.Resolution.EXACT
              ? result.candidates().get(0).resource()
              : null;
      status =
          switch (result.resolution()) {
            case EXACT ->
                new TranslatableComponent("screen.agma_standalone.exact_match").getString();
            case AMBIGUOUS ->
                new TranslatableComponent("screen.agma_standalone.choose_match").getString();
            case NOT_FOUND ->
                new TranslatableComponent("screen.agma_standalone.no_match").getString();
          };
    } catch (IllegalArgumentException | IllegalStateException failure) {
      candidates = List.of();
      selected = null;
      clearPlan();
      status = new TranslatableComponent("screen.agma_standalone.catalog_unavailable").getString();
    }
    rebuild();
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
    status =
        new TranslatableComponent("screen.agma_standalone.selected", resource.id()).getString();
    rebuild();
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
    rebuild();
  }

  private void adjustPlanAmount(int delta) {
    var next = Math.max(1, Math.min(MAXIMUM_PLAN_AMOUNT, uiState.localPlanAmount + delta));
    if (next == uiState.localPlanAmount) {
      return;
    }
    uiState.localPlanAmount = next;
    clearPlan();
    rebuild();
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
    status = new TranslatableComponent("screen.agma_standalone.plan_loading").getString();
    tools
        .planLocal(target, BigDecimal.valueOf(amount))
        .whenComplete(
            (plan, failure) ->
                minecraft.execute(
                    () -> {
                      planning = false;
                      if (!target.equals(selected) || amount != uiState.localPlanAmount) {
                        if (minecraft.screen == this) {
                          rebuild();
                        }
                        return;
                      }
                      if (failure == null) {
                        uiState.localPlan = plan;
                        status =
                            new TranslatableComponent("screen.agma_standalone.plan_ready")
                                .getString();
                      } else {
                        uiState.localPlan = null;
                        planFailed = true;
                        status =
                            new TranslatableComponent("screen.agma_standalone.plan_unavailable")
                                .getString();
                      }
                      if (minecraft.screen == this) {
                        rebuild();
                      }
                    }));
  }

  private void rebuild() {
    clearWidgets();
    init();
  }

  @Override
  public void render(PoseStack pose, int mouseX, int mouseY, float partialTick) {
    renderBackground(pose);
    var panelWidth = Math.min(PANEL_MAXIMUM_WIDTH, Math.max(280, width - 24));
    var left = (width - panelWidth) / 2;
    var panelHeight = Math.min(410, Math.max(180, height - 24));
    var top = Math.max(4, (height - panelHeight) / 2);
    var bottom = top + panelHeight;
    fill(pose, left, top, left + panelWidth, bottom, PANEL_BACKGROUND);
    fill(pose, left, top, left + panelWidth, top + 1, PANEL_BORDER);
    fill(pose, left, top, left + 3, bottom, ACCENT);
    var snapshot = catalog.current();
    var catalogStatus =
        snapshot
            .map(
                value ->
                    new TranslatableComponent(
                            "screen.agma_standalone.catalog_ready",
                            value.generationId(),
                            value.resources().size(),
                            value.processes().size())
                        .getString())
            .orElseGet(
                () ->
                    new TranslatableComponent(
                            "screen.agma_standalone.catalog_state",
                            catalog.progress().phase().name().toLowerCase(Locale.ROOT))
                        .getString());
    font.draw(pose, catalogStatus, left + 16, top + 34, SECONDARY_TEXT);
    if (!status.isBlank()) {
      font.draw(
          pose,
          font.plainSubstrByWidth(status, panelWidth - 32),
          left + 16,
          bottom - 42,
          selected == null ? WARNING_TEXT : ACCENT);
    }
    if (selected != null) {
      var source =
          new TranslatableComponent(
                  "screen.agma_standalone.local_confirmed",
                  selected.modName(),
                  selected.modVersion())
              .getString();
      font.draw(
          pose,
          font.plainSubstrByWidth(source, panelWidth - 32),
          left + 16,
          bottom - 28,
          SECONDARY_TEXT);
    }
    var viewer =
        OptionalViewerRegistry.selected()
            .map(adapter -> adapter.descriptor().id())
            .orElse("vanilla_client");
    var web = runtime.profileStore().isConfigured() && webConfigured() ? "configured" : "off";
    var sources =
        new TranslatableComponent("screen.agma_standalone.data_sources", viewer, web).getString();
    font.draw(
        pose,
        font.plainSubstrByWidth(sources, panelWidth - 32),
        left + 16,
        bottom - 14,
        SECONDARY_TEXT);
    renderPlan(pose, left, top, bottom, panelWidth);
    super.render(pose, mouseX, mouseY, partialTick);
  }

  private void renderPlan(PoseStack pose, int left, int top, int bottom, int panelWidth) {
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
      lines.add(new TranslatableComponent(section.titleKey()).getString());
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
      font.draw(
          pose,
          font.plainSubstrByWidth(line, panelWidth - 32),
          left + 16,
          startY + index * 10,
          line.startsWith("  ") ? PRIMARY_TEXT : ACCENT);
    }
  }

  @Override
  public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
    if (statePlanIsCurrent()) {
      uiState.localPlanScroll = Math.max(0, uiState.localPlanScroll - (int) Math.signum(amount));
      return true;
    }
    return super.mouseScrolled(mouseX, mouseY, amount);
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

  private static Button button(int x, int y, int width, Component label, Button.OnPress action) {
    return new Button(x, y, width, 20, label, action);
  }
}
