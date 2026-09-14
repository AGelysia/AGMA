package dev.minecraftagent.standalone.fabric;

import dev.minecraftagent.standalone.common.CancelReason;
import dev.minecraftagent.standalone.common.CatalogToolExecutor;
import dev.minecraftagent.standalone.common.ClientRuntimeController;
import dev.minecraftagent.standalone.common.ClientToolHandler;
import dev.minecraftagent.standalone.common.LocalPlanPresentation;
import dev.minecraftagent.standalone.common.OptionalViewerRegistry;
import dev.minecraftagent.standalone.common.RouteTreeModel;
import dev.minecraftagent.standalone.common.StandaloneUiState;
import dev.minecraftagent.standalone.core.catalog.ResourceSearchIndex;
import dev.minecraftagent.standalone.core.contract.ResourceRef;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/** Search and disambiguation surface that remains usable when the Runtime is offline. */
public final class StandaloneCatalogScreen extends Screen {
  private static final int PANEL_MAXIMUM_WIDTH = 560;
  private static final int ROW_HEIGHT = 22;
  private static final int PLAN_ROW_HEIGHT = 12;
  private static final int TREE_INDENT = 10;
  private static final int MAXIMUM_VISIBLE_RESULTS = 7;
  private static final int MAXIMUM_PLAN_AMOUNT = 999;
  private static final int PANEL_BACKGROUND = 0xEB15191D;
  private static final int PANEL_BORDER = 0xFF38434A;
  private static final int ACCENT = 0xFF4DAA91;
  private static final int PRIMARY_TEXT = 0xFFF2F5F6;
  private static final int SECONDARY_TEXT = 0xFFADB7BC;
  private static final int WARNING_TEXT = 0xFFE5B567;
  private static final int MISSING_ITEM = 0xFFFF5C6C;
  private static final int TREE_LINE = 0xFF4A565E;
  private static final int FLUID_MARKER = 0xFF5B9BD5;
  private static final int STEP_MARKER = 0xFF4DAA91;

  private final StandaloneCatalogService catalog;
  private final ClientRuntimeController runtime;
  private final CatalogToolExecutor tools;
  private final ClientToolHandler toolRouter;
  private final StandaloneUiState uiState;
  private EditBox queryBox;
  private String query = "";
  private List<ResourceSearchIndex.Candidate> candidates = List.of();
  private ResourceRef selected;
  private String status = "";
  private boolean planning;
  private boolean planFailed;
  private List<PlanLine> planLines;

  public StandaloneCatalogScreen(
      StandaloneCatalogService catalog,
      ClientRuntimeController runtime,
      CatalogToolExecutor tools,
      ClientToolHandler toolRouter,
      StandaloneUiState uiState) {
    super(Component.translatable("screen.agma_standalone.catalog"));
    this.catalog = Objects.requireNonNull(catalog, "catalog");
    this.runtime = Objects.requireNonNull(runtime, "runtime");
    this.tools = Objects.requireNonNull(tools, "tools");
    this.toolRouter = Objects.requireNonNull(toolRouter, "toolRouter");
    this.uiState = Objects.requireNonNull(uiState, "uiState");
    selected = uiState.selected;
    if (selected != null) {
      query = selected.id();
    }
  }

  @Override
  protected void init() {
    planLines = null;
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
                        new StandaloneAssistantScreen(
                            catalog, runtime, tools, toolRouter, uiState)))
            .bounds(left + 16 + tabWidth, top + 8, tabWidth, 20)
            .build());
    addRenderableWidget(
        Button.builder(
                Component.translatable("screen.agma_standalone.tab_settings"),
                ignored ->
                    minecraft.setScreen(
                        new StandaloneSettingsScreen(catalog, runtime, tools, toolRouter, uiState)))
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
              ? result.candidates().get(0).resource()
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
    renderBackground(graphics);
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
    renderPlan(graphics, left, top, bottom, panelWidth, mouseX, mouseY);
    super.render(graphics, mouseX, mouseY, partialTick);
  }

  private void renderPlan(
      GuiGraphics graphics, int left, int top, int bottom, int panelWidth, int mouseX, int mouseY) {
    if (!candidates.isEmpty() || !statePlanIsCurrent()) {
      return;
    }
    var snapshot = catalog.current().orElse(null);
    if (snapshot == null) {
      return;
    }
    if (planLines == null) {
      planLines = buildPlanLines(snapshot);
    }
    var startY = top + 164;
    var visibleLines = Math.max(0, (bottom - startY - 48) / PLAN_ROW_HEIGHT);
    var maximumScroll = Math.max(0, planLines.size() - visibleLines);
    uiState.localPlanScroll = Math.min(uiState.localPlanScroll, maximumScroll);
    var iconCache = new HashMap<String, ItemStack>();
    for (var index = 0;
        index < Math.min(visibleLines, planLines.size() - uiState.localPlanScroll);
        index++) {
      var line = planLines.get(uiState.localPlanScroll + index);
      var rowY = startY + index * PLAN_ROW_HEIGHT;
      if (line instanceof PlanLine.TextLine text) {
        graphics.drawString(
            font,
            font.plainSubstrByWidth(text.text(), panelWidth - 32),
            left + 16,
            rowY + 1,
            text.header() ? ACCENT : PRIMARY_TEXT,
            false);
      } else if (line instanceof PlanLine.TreeLine tree) {
        renderTreeRow(graphics, tree.row(), left + 16, rowY, panelWidth, mouseX, mouseY, iconCache);
      }
    }
  }

  private List<PlanLine> buildPlanLines(
      dev.minecraftagent.standalone.core.catalog.CatalogSnapshot snapshot) {
    var lines = new ArrayList<PlanLine>();
    for (var section :
        LocalPlanPresentation.sections(uiState.localPlan, snapshot, uiState.selectedRoute)) {
      lines.add(
          new PlanLine.TextLine(Component.translatable(section.titleKey()).getString(), true));
      if (section.titleKey().equals("screen.agma_standalone.plan_steps")) {
        for (var row : RouteTreeModel.rows(uiState.localPlan, snapshot, uiState.selectedRoute)) {
          lines.add(new PlanLine.TreeLine(row));
        }
      } else {
        section.entries().forEach(entry -> lines.add(new PlanLine.TextLine("  " + entry, false)));
      }
    }
    return lines;
  }

  private void renderTreeRow(
      GuiGraphics graphics,
      RouteTreeModel.Row row,
      int x,
      int y,
      int panelWidth,
      int mouseX,
      int mouseY,
      Map<String, ItemStack> iconCache) {
    var guides = row.guides();
    var depth = guides.depth();
    for (var level = 0; level < depth; level++) {
      if (guides.ancestorContinues().get(level)) {
        graphics.fill(
            x + level * TREE_INDENT + 1,
            y,
            x + level * TREE_INDENT + 2,
            y + PLAN_ROW_HEIGHT,
            TREE_LINE);
      }
    }
    if (depth > 0) {
      var connectorX = x + (depth - 1) * TREE_INDENT + 1;
      graphics.fill(
          connectorX,
          y,
          connectorX + 1,
          y + (guides.hasNextSibling() ? PLAN_ROW_HEIGHT : PLAN_ROW_HEIGHT / 2),
          TREE_LINE);
      graphics.fill(
          connectorX,
          y + PLAN_ROW_HEIGHT / 2,
          connectorX + TREE_INDENT - 2,
          y + PLAN_ROW_HEIGHT / 2 + 1,
          TREE_LINE);
    }
    var contentX = x + depth * TREE_INDENT + 3;
    var textWidth = Math.max(8, panelWidth - 48 - depth * TREE_INDENT);
    if (row instanceof RouteTreeModel.TargetRow target) {
      graphics.fill(contentX + 1, y + 4, contentX + 7, y + 10, ACCENT);
      var text =
          decimal(target.requested())
              + " x "
              + target.resource().displayName()
              + " ["
              + target.resource().id()
              + "]";
      graphics.drawString(
          font, font.plainSubstrByWidth(text, textWidth), contentX + 11, y + 2, ACCENT, false);
    } else if (row instanceof RouteTreeModel.StepRow step) {
      graphics.fill(contentX + 1, y + 4, contentX + 7, y + 10, STEP_MARKER);
      var text = decimal(step.batches()) + " × " + step.displayName() + "  " + step.categoryId();
      graphics.drawString(
          font,
          font.plainSubstrByWidth(text, textWidth),
          contentX + 11,
          y + 2,
          step.plannable() ? PRIMARY_TEXT : WARNING_TEXT,
          false);
    } else if (row instanceof RouteTreeModel.ResourceRow resource) {
      var textX =
          contentX
              + drawResourceIcon(
                  graphics, resource.resource(), contentX, y, mouseX, mouseY, iconCache);
      var text =
          decimal(resource.amount())
              + " x "
              + resource.resource().displayName()
              + stateSuffix(resource.state());
      graphics.drawString(
          font,
          font.plainSubstrByWidth(text, textWidth),
          textX,
          y + 2,
          stateColor(resource.state()),
          false);
    } else if (row instanceof RouteTreeModel.InfoRow info) {
      graphics.drawString(
          font,
          font.plainSubstrByWidth(info.text(), textWidth),
          contentX + 11,
          y + 2,
          SECONDARY_TEXT,
          false);
    }
  }

  private int drawResourceIcon(
      GuiGraphics graphics,
      ResourceRef resource,
      int x,
      int y,
      int mouseX,
      int mouseY,
      Map<String, ItemStack> iconCache) {
    if (resource.kind() == ResourceRef.Kind.ITEM) {
      var stack = iconCache.computeIfAbsent(resource.id(), StandaloneCatalogScreen::resolveStack);
      if (mouseX >= x && mouseX < x + 12 && mouseY >= y && mouseY < y + PLAN_ROW_HEIGHT) {
        graphics.renderTooltip(
            font,
            stack.isEmpty()
                ? Component.literal("Missing item: " + resource.id())
                : stack.getHoverName(),
            mouseX,
            mouseY);
      }
      if (!stack.isEmpty()) {
        graphics.pose().pushPose();
        graphics.pose().translate(x, y, 0);
        graphics.pose().scale(0.75f, 0.75f, 1.0f);
        graphics.renderItem(stack, 0, 0);
        graphics.pose().popPose();
        return 14;
      }
      graphics.fill(x + 2, y + 2, x + 10, y + 10, MISSING_ITEM);
      graphics.drawString(font, "?", x + 4, y + 2, PANEL_BACKGROUND, false);
      return 14;
    }
    var color = resource.kind() == ResourceRef.Kind.FLUID ? FLUID_MARKER : SECONDARY_TEXT;
    graphics.fill(x + 3, y + 3, x + 9, y + 9, color);
    return 14;
  }

  private static ItemStack resolveStack(String id) {
    var identifier = ResourceLocation.tryParse(id);
    if (identifier == null) {
      return ItemStack.EMPTY;
    }
    var item = BuiltInRegistries.ITEM.get(identifier);
    return item == null ? ItemStack.EMPTY : new ItemStack(item);
  }

  private static int stateColor(RouteTreeModel.ResourceState state) {
    return switch (state) {
      case INVENTORY -> ACCENT;
      case UNRESOLVED -> WARNING_TEXT;
      case LEAF -> SECONDARY_TEXT;
      default -> PRIMARY_TEXT;
    };
  }

  private static String stateSuffix(RouteTreeModel.ResourceState state) {
    return switch (state) {
      case INVENTORY -> " (inventory)";
      case UNRESOLVED -> " (unresolved)";
      default -> "";
    };
  }

  private static String decimal(BigDecimal value) {
    return value.stripTrailingZeros().toPlainString();
  }

  private sealed interface PlanLine {
    record TextLine(String text, boolean header) implements PlanLine {}

    record TreeLine(RouteTreeModel.Row row) implements PlanLine {}
  }

  @Override
  public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
    if (statePlanIsCurrent()) {
      uiState.localPlanScroll = Math.max(0, uiState.localPlanScroll - (int) Math.signum(delta));
      return true;
    }
    return super.mouseScrolled(mouseX, mouseY, delta);
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
