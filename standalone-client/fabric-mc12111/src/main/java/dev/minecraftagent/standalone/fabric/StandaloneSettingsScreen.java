package dev.minecraftagent.standalone.fabric;

import dev.minecraftagent.standalone.common.CatalogToolExecutor;
import dev.minecraftagent.standalone.common.ClientDiagnosticExporter;
import dev.minecraftagent.standalone.common.ClientLifecycleState;
import dev.minecraftagent.standalone.common.ClientRuntimeController;
import dev.minecraftagent.standalone.common.ClientSetup;
import java.math.BigDecimal;
import java.net.URI;
import java.util.List;
import java.util.Objects;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

/** First-run and maintenance configuration with secret values always visually masked. */
public final class StandaloneSettingsScreen extends Screen {
  private static final List<String> PROVIDERS =
      List.of("openai", "anthropic", "deepseek", "gemini", "openai-compatible");
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
  private Page page = Page.MODEL;
  private String provider = "openai";
  private String model = "";
  private String baseUrl = "";
  private String modelKey = "";
  private String dailyRequests = "100";
  private String modelBudgetUsd = "5.000000";
  private String inputPriceUsd = "0";
  private String outputPriceUsd = "0";
  private boolean storeConversations;
  private String retentionDays = "30";
  private boolean confirmDelete;
  private boolean confirmUninstall;
  private boolean webEnabled;
  private String searchKey = "";
  private String searchCostUsd = "0.000001";
  private String searchBudgetUsd = "0.100000";
  private String country = "CN";
  private String searchLanguage = "zh-cn";
  private String localStatus = "";

  public StandaloneSettingsScreen(
      StandaloneCatalogService catalog,
      ClientRuntimeController runtime,
      CatalogToolExecutor tools,
      StandaloneUiState state) {
    super(Component.translatable("screen.agma_standalone.settings"));
    this.catalog = Objects.requireNonNull(catalog, "catalog");
    this.runtime = Objects.requireNonNull(runtime, "runtime");
    this.tools = Objects.requireNonNull(tools, "tools");
    this.state = Objects.requireNonNull(state, "state");
    loadExisting();
  }

  @Override
  protected void init() {
    var panelWidth = Math.min(PANEL_MAXIMUM_WIDTH, Math.max(300, width - 24));
    var panelHeight = Math.min(360, Math.max(270, height - 24));
    var left = (width - panelWidth) / 2;
    var top = (height - panelHeight) / 2;
    addTabs(left, top, panelWidth);
    var innerWidth = (panelWidth - 32) / 4;
    var modelTab =
        Button.builder(
                Component.translatable("screen.agma_standalone.model_settings"),
                ignored -> show(Page.MODEL))
            .bounds(left + 16, top + 38, innerWidth, 20)
            .build();
    modelTab.active = page != Page.MODEL;
    addRenderableWidget(modelTab);
    var limitsTab =
        Button.builder(
                Component.translatable("screen.agma_standalone.limit_settings"),
                ignored -> show(Page.LIMITS))
            .bounds(left + 16 + innerWidth, top + 38, innerWidth, 20)
            .build();
    limitsTab.active = page != Page.LIMITS;
    addRenderableWidget(limitsTab);
    var privacyTab =
        Button.builder(
                Component.translatable("screen.agma_standalone.privacy_settings"),
                ignored -> show(Page.PRIVACY))
            .bounds(left + 16 + innerWidth * 2, top + 38, innerWidth, 20)
            .build();
    privacyTab.active = page != Page.PRIVACY;
    addRenderableWidget(privacyTab);
    var webTab =
        Button.builder(
                Component.translatable("screen.agma_standalone.web_settings"),
                ignored -> show(Page.WEB))
            .bounds(left + 16 + innerWidth * 3, top + 38, panelWidth - 32 - innerWidth * 3, 20)
            .build();
    webTab.active = page != Page.WEB;
    addRenderableWidget(webTab);
    if (page == Page.MODEL) {
      addModelFields(left, top, panelWidth);
    } else if (page == Page.LIMITS) {
      addLimitFields(left, top, panelWidth);
    } else if (page == Page.PRIVACY) {
      addPrivacyFields(left, top, panelWidth);
    } else {
      addWebFields(left, top, panelWidth);
    }
    var save =
        Button.builder(Component.translatable("screen.agma_standalone.save"), ignored -> save())
            .bounds(left + 16, top + panelHeight - 28, 100, 20)
            .build();
    var lifecycle = runtime.view().profile().state();
    save.active =
        lifecycle != ClientLifecycleState.STARTING
            && lifecycle != ClientLifecycleState.READY
            && lifecycle != ClientLifecycleState.STOPPING;
    addRenderableWidget(save);
    addRenderableWidget(
        Button.builder(
                Component.translatable("screen.agma_standalone.export_diagnostics"),
                ignored -> exportDiagnostics())
            .bounds(left + 122, top + panelHeight - 28, 120, 20)
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
    addRenderableWidget(
        Button.builder(
                Component.translatable("screen.agma_standalone.tab_ask"),
                ignored ->
                    minecraft.setScreen(
                        new StandaloneAssistantScreen(catalog, runtime, tools, state)))
            .bounds(left + 16 + tabWidth, top + 12, tabWidth, 20)
            .build());
    var settings =
        Button.builder(Component.translatable("screen.agma_standalone.tab_settings"), ignored -> {})
            .bounds(left + 16 + tabWidth * 2, top + 12, panelWidth - 32 - tabWidth * 2, 20)
            .build();
    settings.active = false;
    addRenderableWidget(settings);
  }

  private void addModelFields(int left, int top, int panelWidth) {
    addRenderableWidget(
        Button.builder(Component.literal(provider), ignored -> cycleProvider())
            .bounds(left + 154, top + 66, panelWidth - 170, 20)
            .build());
    addField(left, top + 90, panelWidth, model, 128, value -> model = value, false);
    addField(left, top + 114, panelWidth, baseUrl, 2048, value -> baseUrl = value, false);
    addField(left, top + 138, panelWidth, modelKey, 8192, value -> modelKey = value, true);
  }

  private void addLimitFields(int left, int top, int panelWidth) {
    addField(left, top + 66, panelWidth, dailyRequests, 7, value -> dailyRequests = value, false);
    addField(
        left, top + 96, panelWidth, modelBudgetUsd, 24, value -> modelBudgetUsd = value, false);
    addField(left, top + 126, panelWidth, inputPriceUsd, 24, value -> inputPriceUsd = value, false);
    addField(
        left, top + 156, panelWidth, outputPriceUsd, 24, value -> outputPriceUsd = value, false);
  }

  private void addWebFields(int left, int top, int panelWidth) {
    addRenderableWidget(
        Button.builder(
                Component.translatable(
                    webEnabled
                        ? "screen.agma_standalone.web_configured_on"
                        : "screen.agma_standalone.web_configured_off"),
                ignored -> {
                  webEnabled = !webEnabled;
                  rebuildWidgets();
                })
            .bounds(left + 154, top + 66, panelWidth - 170, 20)
            .build());
    addField(left, top + 96, panelWidth, searchKey, 8192, value -> searchKey = value, true);
    addField(left, top + 126, panelWidth, searchCostUsd, 24, value -> searchCostUsd = value, false);
    addField(
        left, top + 156, panelWidth, searchBudgetUsd, 24, value -> searchBudgetUsd = value, false);
    addSmallField(
        left + 154,
        top + 186,
        (panelWidth - 178) / 2,
        country,
        2,
        value -> country = value.toUpperCase(java.util.Locale.ROOT));
    addSmallField(
        left + 166 + (panelWidth - 178) / 2,
        top + 186,
        panelWidth - 182 - (panelWidth - 178) / 2,
        searchLanguage,
        8,
        value -> searchLanguage = value.toLowerCase(java.util.Locale.ROOT));
  }

  private void addPrivacyFields(int left, int top, int panelWidth) {
    addRenderableWidget(
        Button.builder(
                Component.translatable(
                    storeConversations
                        ? "screen.agma_standalone.conversations_on"
                        : "screen.agma_standalone.conversations_off"),
                ignored -> {
                  storeConversations = !storeConversations;
                  rebuildWidgets();
                })
            .bounds(left + 154, top + 66, panelWidth - 170, 20)
            .build());
    addField(left, top + 96, panelWidth, retentionDays, 4, value -> retentionDays = value, false);
    var delete =
        Button.builder(
                Component.translatable(
                    confirmDelete
                        ? "screen.agma_standalone.confirm_delete"
                        : "screen.agma_standalone.delete_private_data"),
                ignored -> deletePrivateData())
            .bounds(left + 154, top + 126, panelWidth - 170, 20)
            .build();
    var lifecycle = runtime.view().profile().state();
    delete.active =
        lifecycle != ClientLifecycleState.STARTING
            && lifecycle != ClientLifecycleState.READY
            && lifecycle != ClientLifecycleState.STOPPING;
    addRenderableWidget(delete);
    var uninstall =
        Button.builder(
                Component.translatable(
                    confirmUninstall
                        ? "screen.agma_standalone.confirm_uninstall"
                        : "screen.agma_standalone.uninstall_runtime"),
                ignored -> uninstallRuntime())
            .bounds(left + 154, top + 150, panelWidth - 170, 20)
            .build();
    uninstall.active = delete.active;
    addRenderableWidget(uninstall);
  }

  private void deletePrivateData() {
    if (!confirmDelete) {
      confirmDelete = true;
      rebuildWidgets();
      return;
    }
    try {
      runtime.deleteConfiguration();
      state.sessionId = null;
      state.answer = "";
      state.sources = java.util.List.of();
      state.status = "";
      modelKey = "";
      searchKey = "";
      localStatus =
          Component.translatable("screen.agma_standalone.private_data_deleted").getString();
    } catch (RuntimeException failure) {
      localStatus = "CONFIG_DELETE_FAILED";
    }
    confirmDelete = false;
    rebuildWidgets();
  }

  private void uninstallRuntime() {
    if (!confirmUninstall) {
      confirmUninstall = true;
      rebuildWidgets();
      return;
    }
    try {
      runtime.uninstallManagedRuntime();
      localStatus =
          Component.translatable("screen.agma_standalone.runtime_uninstalled").getString();
    } catch (RuntimeException failure) {
      localStatus = "RUNTIME_UNINSTALL_FAILED";
    }
    confirmUninstall = false;
    rebuildWidgets();
  }

  private void addField(
      int left,
      int y,
      int panelWidth,
      String value,
      int maximum,
      java.util.function.Consumer<String> responder,
      boolean secret) {
    var field = new EditBox(font, left + 154, y, panelWidth - 170, 20, Component.empty());
    field.setMaxLength(maximum);
    field.setValue(value);
    field.setResponder(responder);
    if (secret) {
      field.addFormatter(
          (text, offset) -> FormattedCharSequence.forward("*".repeat(text.length()), Style.EMPTY));
    }
    addRenderableWidget(field);
  }

  private void addSmallField(
      int x,
      int y,
      int fieldWidth,
      String value,
      int maximum,
      java.util.function.Consumer<String> responder) {
    var field = new EditBox(font, x, y, fieldWidth, 20, Component.empty());
    field.setMaxLength(maximum);
    field.setValue(value);
    field.setResponder(responder);
    addRenderableWidget(field);
  }

  private void cycleProvider() {
    provider = PROVIDERS.get((PROVIDERS.indexOf(provider) + 1) % PROVIDERS.size());
    rebuildWidgets();
  }

  private void show(Page next) {
    page = next;
    rebuildWidgets();
  }

  private void save() {
    try {
      var url = baseUrl.isBlank() ? null : URI.create(baseUrl.strip());
      ClientSetup.WebSearchSetup web = null;
      if (webEnabled) {
        web =
            new ClientSetup.WebSearchSetup(
                searchKey,
                microUsd(searchCostUsd),
                microUsd(searchBudgetUsd),
                country,
                searchLanguage);
      }
      runtime.configure(
          new ClientSetup(
              provider,
              url,
              model,
              modelKey,
              microUsd(inputPriceUsd),
              microUsd(outputPriceUsd),
              Integer.parseInt(dailyRequests),
              microUsd(modelBudgetUsd),
              web,
              storeConversations,
              storeConversations ? Integer.parseInt(retentionDays) : 0));
      modelKey = "";
      searchKey = "";
      localStatus = Component.translatable("screen.agma_standalone.saved").getString();
      rebuildWidgets();
    } catch (RuntimeException failure) {
      localStatus = Component.translatable("screen.agma_standalone.invalid_settings").getString();
    }
  }

  private void loadExisting() {
    if (!runtime.profileStore().isConfigured()) {
      return;
    }
    try {
      var profile = runtime.profileStore().load();
      provider = profile.model().provider();
      model = profile.model().model();
      baseUrl = profile.model().baseUrl() == null ? "" : profile.model().baseUrl().toString();
      dailyRequests = Integer.toString(profile.limits().dailyRequests());
      modelBudgetUsd = usd(profile.limits().monthlyBudgetMicroUsd());
      inputPriceUsd = usd(profile.model().inputMicroUsdPerMillionTokens());
      outputPriceUsd = usd(profile.model().outputMicroUsdPerMillionTokens());
      storeConversations = profile.privacy().storeConversations();
      retentionDays = Integer.toString(storeConversations ? profile.privacy().retentionDays() : 30);
      if (profile.webEvidence() != null) {
        webEnabled = true;
        searchCostUsd = usd(profile.webEvidence().requestCostMicroUsd());
        searchBudgetUsd = usd(profile.webEvidence().monthlyBudgetMicroUsd());
        country = profile.webEvidence().country();
        searchLanguage = profile.webEvidence().searchLanguage();
      }
    } catch (RuntimeException failure) {
      localStatus = "CONFIG_FILE_INVALID";
    }
  }

  private void exportDiagnostics() {
    try {
      var view = catalog.catalogView();
      var snapshot = view.snapshot();
      var viewer =
          OptionalViewerRegistry.selected()
              .map(adapter -> adapter.descriptor().id())
              .orElse("vanilla_client");
      new ClientDiagnosticExporter()
          .write(
              runtime.profileStore().root(),
              runtime.view(),
              new ClientDiagnosticExporter.CatalogDiagnostic(
                  "1.21.11",
                  view.state().name(),
                  view.visibility().name(),
                  view.completeness().name(),
                  snapshot == null ? 0 : snapshot.resources().size(),
                  snapshot == null ? 0 : snapshot.processes().size(),
                  viewer));
      localStatus =
          Component.translatable("screen.agma_standalone.diagnostics_exported").getString();
    } catch (RuntimeException failure) {
      localStatus = "DIAGNOSTIC_EXPORT_FAILED";
    }
    rebuildWidgets();
  }

  private static long microUsd(String value) {
    return new BigDecimal(value.strip()).movePointRight(6).longValueExact();
  }

  private static String usd(long microUsd) {
    return BigDecimal.valueOf(microUsd, 6).stripTrailingZeros().toPlainString();
  }

  @Override
  public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    renderTransparentBackground(graphics);
    var panelWidth = Math.min(PANEL_MAXIMUM_WIDTH, Math.max(300, width - 24));
    var panelHeight = Math.min(360, Math.max(270, height - 24));
    var left = (width - panelWidth) / 2;
    var top = (height - panelHeight) / 2;
    graphics.fill(left, top, left + panelWidth, top + panelHeight, PANEL_BACKGROUND);
    graphics.fill(left, top, left + panelWidth, top + 1, PANEL_BORDER);
    graphics.fill(left, top, left + 3, top + panelHeight, ACCENT);
    if (page == Page.MODEL) {
      label(graphics, left, top + 72, "screen.agma_standalone.provider");
      label(graphics, left, top + 96, "screen.agma_standalone.model");
      label(graphics, left, top + 120, "screen.agma_standalone.base_url");
      label(graphics, left, top + 144, "screen.agma_standalone.model_api_key");
    } else if (page == Page.LIMITS) {
      label(graphics, left, top + 72, "screen.agma_standalone.daily_limit");
      label(graphics, left, top + 102, "screen.agma_standalone.model_budget");
      label(graphics, left, top + 132, "screen.agma_standalone.input_price");
      label(graphics, left, top + 162, "screen.agma_standalone.output_price");
    } else if (page == Page.PRIVACY) {
      label(graphics, left, top + 72, "screen.agma_standalone.conversation_storage");
      label(graphics, left, top + 102, "screen.agma_standalone.retention_days");
      graphics.drawString(
          font,
          font.plainSubstrByWidth(
              Component.translatable("screen.agma_standalone.local_secret_warning").getString(),
              panelWidth - 32),
          left + 16,
          top + 184,
          WARNING_TEXT,
          false);
    } else {
      label(graphics, left, top + 72, "screen.agma_standalone.web_provider");
      label(graphics, left, top + 102, "screen.agma_standalone.search_api_key");
      label(graphics, left, top + 132, "screen.agma_standalone.search_cost");
      label(graphics, left, top + 162, "screen.agma_standalone.search_budget");
      label(graphics, left, top + 192, "screen.agma_standalone.locale");
    }
    if (!localStatus.isBlank()) {
      graphics.drawString(
          font,
          font.plainSubstrByWidth(localStatus, Math.max(20, panelWidth - 264)),
          left + 248,
          top + panelHeight - 22,
          localStatus.equals("CONFIG_FILE_INVALID") ? WARNING_TEXT : ACCENT,
          false);
    }
    super.render(graphics, mouseX, mouseY, partialTick);
  }

  private void label(GuiGraphics graphics, int left, int y, String key) {
    graphics.drawString(
        font,
        font.plainSubstrByWidth(Component.translatable(key).getString(), 128),
        left + 16,
        y,
        SECONDARY_TEXT,
        false);
  }

  @Override
  public boolean isPauseScreen() {
    return false;
  }

  private enum Page {
    MODEL,
    LIMITS,
    PRIVACY,
    WEB
  }
}
