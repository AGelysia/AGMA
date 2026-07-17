package dev.minecraftagent.paper;

import dev.minecraftagent.paper.audit.FileProposalAuditLog;
import dev.minecraftagent.paper.capability.PaperInstalledPluginInventory;
import dev.minecraftagent.paper.capability.load.CapabilityPackLoader;
import dev.minecraftagent.paper.capability.model.CapabilityDiagnostic;
import dev.minecraftagent.paper.capability.registry.CapabilityRegistry;
import dev.minecraftagent.paper.client.ClientConnectionRegistry;
import dev.minecraftagent.paper.client.ClientPayloadCodec;
import dev.minecraftagent.paper.client.ClientStateCoordinator;
import dev.minecraftagent.paper.client.ClientTransferManager;
import dev.minecraftagent.paper.client.ClientUiCommandGateway;
import dev.minecraftagent.paper.client.ClientViewPublisher;
import dev.minecraftagent.paper.client.ClientViewSchemaRegistry;
import dev.minecraftagent.paper.client.ClientViewSelector;
import dev.minecraftagent.paper.client.PaperClientChannel;
import dev.minecraftagent.paper.command.AdminToggleAuthorizer;
import dev.minecraftagent.paper.command.AgentCommand;
import dev.minecraftagent.paper.command.AgentControl;
import dev.minecraftagent.paper.command.AgentManagementGateway;
import dev.minecraftagent.paper.command.AgentUiControl;
import dev.minecraftagent.paper.command.OwnerReloadAuthorizer;
import dev.minecraftagent.paper.command.PaperCommandRegistration;
import dev.minecraftagent.paper.command.ProposalResponseGateway;
import dev.minecraftagent.paper.landmark.LandmarkCatalog;
import dev.minecraftagent.paper.landmark.LandmarkCatalogLoader;
import dev.minecraftagent.paper.lifecycle.AdminPolicy;
import dev.minecraftagent.paper.lifecycle.AgentHealth;
import dev.minecraftagent.paper.lifecycle.AgentStatus;
import dev.minecraftagent.paper.lifecycle.CoreReadiness;
import dev.minecraftagent.paper.lifecycle.OfflineCleanup;
import dev.minecraftagent.paper.lifecycle.OfflineReason;
import dev.minecraftagent.paper.lifecycle.OperationalGate;
import dev.minecraftagent.paper.lifecycle.PaperStartupCoordinator;
import dev.minecraftagent.paper.management.ManagementSnapshotFactory;
import dev.minecraftagent.paper.management.reload.PaperReloadCandidateSource;
import dev.minecraftagent.paper.management.reload.ReloadManager;
import dev.minecraftagent.paper.preview.BuildPreviewArtifactRepository;
import dev.minecraftagent.paper.preview.PaperBuildPreviewService;
import dev.minecraftagent.paper.proposal.InMemoryProposalRepository;
import dev.minecraftagent.paper.proposal.ProposalAuthorizer;
import dev.minecraftagent.paper.proposal.ProposalConfirmationResult;
import dev.minecraftagent.paper.proposal.ProposalService;
import dev.minecraftagent.paper.request.AgentPlayerListener;
import dev.minecraftagent.paper.request.AgentRequestService;
import dev.minecraftagent.paper.runtime.DeploymentRuntimeSupervisor;
import dev.minecraftagent.paper.runtime.EmbeddedManagedRuntimeProvisioner;
import dev.minecraftagent.paper.runtime.LoopbackRuntimeHealthProbe;
import dev.minecraftagent.paper.runtime.ManagedRuntimeSetupProbe;
import dev.minecraftagent.paper.runtime.ManagedRuntimeSupervisor;
import dev.minecraftagent.paper.runtime.ProcessFactory;
import dev.minecraftagent.paper.runtime.RuntimeDeploymentMode;
import dev.minecraftagent.paper.runtime.install.ManagedRuntimeInstaller;
import dev.minecraftagent.paper.setup.AgmaSetupCommand;
import dev.minecraftagent.paper.setup.IndependentCommandRegistration;
import dev.minecraftagent.paper.setup.RedactedSetupSnapshot;
import dev.minecraftagent.paper.setup.SetupGateway;
import dev.minecraftagent.paper.setup.SetupState;
import dev.minecraftagent.paper.startup.LocalStartupChecks;
import dev.minecraftagent.paper.startup.StartupFailure;
import dev.minecraftagent.paper.startup.StartupWarning;
import dev.minecraftagent.paper.state.FileDesiredModeStore;
import dev.minecraftagent.paper.tool.BukkitReadToolExecutor;
import dev.minecraftagent.paper.tool.ReadToolRegistry;
import dev.minecraftagent.paper.transport.JavaHttpRuntimeConnector;
import dev.minecraftagent.paper.transport.RuntimeConnectionSettings;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public final class MinecraftAgentPlugin extends JavaPlugin {
  private static final int MAX_DEFAULT_CONFIG_BYTES = 64 * 1024;
  private static final java.util.Set<java.nio.file.attribute.PosixFilePermission>
      PRIVATE_DIRECTORY_PERMISSIONS = PosixFilePermissions.fromString("rwx------");
  private static final java.util.Set<java.nio.file.attribute.PosixFilePermission>
      PRIVATE_FILE_PERMISSIONS = PosixFilePermissions.fromString("rw-------");

  private final AtomicReference<PaperStartupCoordinator> coordinatorReference =
      new AtomicReference<>();
  private final AtomicReference<AgentRequestService> requestServiceReference =
      new AtomicReference<>();
  private final AtomicReference<PaperClientChannel> clientChannelReference =
      new AtomicReference<>();
  private final AtomicReference<ProposalService> proposalServiceReference = new AtomicReference<>();
  private final AtomicReference<ProposalAuthorizer.Policy> proposalPolicyReference =
      new AtomicReference<>(lockedProposalPolicy());
  private final AtomicReference<ReloadManager> reloadManagerReference = new AtomicReference<>();
  private final AtomicReference<IndependentCommandRegistration> setupRegistrationReference =
      new AtomicReference<>();
  private final CapabilityRegistry capabilityRegistry = new CapabilityRegistry();

  @Override
  public void onEnable() {
    var staleSetupRegistration = setupRegistrationReference.getAndSet(null);
    if (staleSetupRegistration != null) {
      staleSetupRegistration.close();
    }
    clientChannelReference.set(null);
    proposalServiceReference.set(null);
    proposalPolicyReference.set(lockedProposalPolicy());
    var staleReloadManager = reloadManagerReference.getAndSet(null);
    if (staleReloadManager != null) {
      staleReloadManager.close();
    }
    var dataDirectory = getDataFolder().toPath().toAbsolutePath().normalize();
    var configPath = dataDirectory.resolve("config.yml");
    var managedDirectory = dataDirectory.resolve("managed");
    var managedConfigPath = managedDirectory.resolve("config.yml");
    var minecraftVersion = getServer().getMinecraftVersion();
    var componentVersion = getPluginMeta().getVersion();
    var managedInstallDirectory =
        managedDirectory.resolve("runtime/current").resolve(componentVersion);
    var managedServerToken = generateManagedServerToken();
    var startupEnvironment = new HashMap<>(System.getenv());
    startupEnvironment.put("AGMA_MANAGED_SERVER_TOKEN", managedServerToken);
    var checkedStartupEnvironment = Map.copyOf(startupEnvironment);
    var runtimeModeReference = new AtomicReference<RuntimeDeploymentMode>();
    var defaultConfigResource = hasEmbeddedManagedRuntime() ? "config-managed.yml" : "config.yml";
    var buildPreviewPublishingEnabled =
        "true".equals(System.getenv("MINECRAFT_AGENT_BUILD_PREVIEW_ENABLED"));
    var stateDirectoryReference = new AtomicReference<Path>();
    var desiredModeStoreReference = new AtomicReference<FileDesiredModeStore>();
    var landmarkCatalogReference = new AtomicReference<>(LandmarkCatalog.empty());
    var serverIdReference = new AtomicReference<String>();
    var worker =
        Executors.newSingleThreadScheduledExecutor(
            runnable -> {
              var thread = new Thread(runnable, "agma-paper-io");
              return thread;
            });
    var managedSupervisor =
        new ManagedRuntimeSupervisor(
            worker,
            new EmbeddedManagedRuntimeProvisioner(
                MinecraftAgentPlugin.class.getClassLoader(),
                new ManagedRuntimeInstaller(),
                managedDirectory.resolve("runtime"),
                componentVersion,
                ProcessFactory.system()),
            new LoopbackRuntimeHealthProbe(),
            new ManagedRuntimeSupervisor.Settings(
                managedInstallDirectory.resolve("bin/node"),
                managedInstallDirectory.resolve("app/dist/bootstrap/index.js"),
                managedConfigPath,
                managedInstallDirectory,
                managedDirectory.resolve("logs/runtime.out.log"),
                managedDirectory.resolve("logs/runtime.err.log"),
                Duration.ofMillis(250),
                Duration.ofSeconds(120),
                Duration.ofSeconds(5),
                Map.of(
                    "NODE_ENV",
                    "production",
                    "LANG",
                    "C.UTF-8",
                    "AGMA_MANAGED_SERVER_TOKEN",
                    managedServerToken)));
    var runtimeSupervisor = new DeploymentRuntimeSupervisor(managedSupervisor);
    var localChecks = new LocalStartupChecks();
    var pluginInventory =
        new PaperInstalledPluginInventory(
            java.util.Arrays.asList(getServer().getPluginManager().getPlugins()));
    getServer().getPluginManager().registerEvents(pluginInventory, this);
    var connector = new JavaHttpRuntimeConnector(worker);
    var operationalGate = new OperationalGate();
    var proposalAuthorizer =
        new ProposalAuthorizer(
            () -> {
              var manager = reloadManagerReference.get();
              return manager == null
                  ? proposalPolicyReference.get()
                  : manager.snapshot().proposalPolicy();
            },
            new ProposalAuthorizer.LivePlayerPolicy() {
              @Override
              public boolean isOnline(UUID playerUuid) {
                var player = getServer().getPlayer(playerUuid);
                return player != null && player.isOnline();
              }

              @Override
              public boolean isOperator(UUID playerUuid) {
                var player = getServer().getPlayer(playerUuid);
                return player != null && player.isOnline() && player.isOp();
              }

              @Override
              public boolean hasPermission(UUID playerUuid, String permission) {
                var player = getServer().getPlayer(playerUuid);
                return player != null && player.isOnline() && player.hasPermission(permission);
              }
            });
    var toolRegistry = new ReadToolRegistry();
    var buildArtifacts = new BuildPreviewArtifactRepository();
    var buildPreviews =
        new PaperBuildPreviewService(
            getServer(),
            task -> getServer().getScheduler().runTask(this, task),
            worker,
            serverIdReference::get,
            buildArtifacts);
    var toolExecutor =
        new BukkitReadToolExecutor(
            getServer(),
            toolRegistry,
            task -> getServer().getScheduler().runTask(this, task),
            landmarkCatalogReference::get,
            buildPreviews);
    var clientConnections = new ClientConnectionRegistry();
    var clientTransfers = ClientTransferManager.withProductionLimits();
    var clientState = new ClientStateCoordinator(clientConnections, clientTransfers);
    var clientChannel =
        new PaperClientChannel(this, new ClientPayloadCodec(), clientState, clientTransfers);
    clientChannel.start();
    clientChannelReference.set(clientChannel);
    var clientViews =
        new ClientViewPublisher(
            new ClientViewSelector(
                clientConnections,
                ClientViewSchemaRegistry.versionOne(buildPreviewPublishingEnabled)),
            clientTransfers);
    var clientUi = new ClientUiCommandGateway(clientConnections, clientChannel.uiControlSink());
    var requests =
        new AgentRequestService(
            operationalGate,
            Duration.ofSeconds(90),
            worker,
            task -> getServer().getScheduler().runTask(this, task),
            (playerId, message) -> {
              var player = getServer().getPlayer(playerId);
              if (player != null && player.isOnline()) {
                player.sendMessage(Component.text(message));
              }
            },
            code -> getLogger().warning("event=request_warning code=" + code),
            toolRegistry,
            toolExecutor,
            worker,
            clientState::capabilitySnapshot,
            (playerId, fallbackText, views) -> {
              var publication = clientViews.prepare(playerId, fallbackText, views, Instant.now());
              return new AgentRequestService.PreparedStructuredReply() {
                @Override
                public boolean send() {
                  var player = getServer().getPlayer(playerId);
                  return player != null
                      && player.isOnline()
                      && clientChannel.sendPublication(player, publication);
                }

                @Override
                public void discard() {
                  clientViews.discard(publication);
                }
              };
            });
    requests.setAuthoritativeViewSource(buildArtifacts::consume);
    requestServiceReference.set(requests);
    getServer()
        .getPluginManager()
        .registerEvents(new AgentPlayerListener(requests, this::invalidatePlayerProposals), this);

    var registration =
        new PaperCommandRegistration(
            getServer().getCommandMap(),
            getServer()::getOnlinePlayers,
            Bukkit::isPrimaryThread,
            code -> logWarning("command-registration", code));
    var setupRegistration =
        new IndependentCommandRegistration(
            getServer().getCommandMap(),
            "agma",
            "agma",
            getServer()::getOnlinePlayers,
            Bukkit::isPrimaryThread,
            code -> logWarning("setup-command-registration", code));
    var setupCommand =
        new AgmaSetupCommand(
            this,
            new SetupGateway() {
              @Override
              public RedactedSetupSnapshot snapshot() {
                return setupSnapshot(coordinatorReference.get(), runtimeModeReference.get());
              }

              @Override
              public java.util.concurrent.CompletionStage<RedactedSetupSnapshot> retry() {
                var current = coordinatorReference.get();
                if (current == null) {
                  return CompletableFuture.completedFuture(
                      RedactedSetupSnapshot.failed("SETUP_RETRY_UNAVAILABLE"));
                }
                return current
                    .retryInitial()
                    .handle(
                        (ignored, error) ->
                            error == null
                                ? setupSnapshot(current, runtimeModeReference.get())
                                : RedactedSetupSnapshot.failed("SETUP_RETRY_FAILED"));
              }
            },
            task -> getServer().getScheduler().runTask(this, task));
    try {
      setupRegistration.register(setupCommand);
      setupRegistrationReference.set(setupRegistration);
    } catch (dev.minecraftagent.paper.command.CommandRegistrationFailure failure) {
      getLogger().severe("event=setup_command_failed code=" + failure.code());
    }
    AgentControl control =
        new AgentControl() {
          @Override
          public void turnOff() {
            var current = coordinatorReference.get();
            if (current != null) {
              current.turnOff();
            }
          }

          @Override
          public RecoveryRequest turnOn() {
            var current = coordinatorReference.get();
            if (current == null) {
              return new RecoveryRequest(
                  RecoveryDisposition.UNAVAILABLE, CompletableFuture.completedFuture(false));
            }
            return current.turnOn();
          }
        };
    java.util.function.Supplier<AdminPolicy> currentAdminPolicy =
        () -> {
          var manager = reloadManagerReference.get();
          if (manager != null) {
            return manager.snapshot().adminPolicy();
          }
          var current = coordinatorReference.get();
          return current == null ? AdminPolicy.locked() : current.adminPolicy();
        };
    AgentManagementGateway management =
        new AgentManagementGateway() {
          @Override
          public dev.minecraftagent.paper.management.ManagementSnapshot snapshot() {
            return ManagementSnapshotFactory.create(
                componentVersion,
                "1.0",
                requests.runtimeConnected(),
                requests.activeRequestCount(),
                capabilityRegistry.snapshot(),
                clientState.diagnosticsSnapshot());
          }

          @Override
          public java.util.concurrent.CompletionStage<CostsResult> costs() {
            return requests.queryCosts().thenApply(MinecraftAgentPlugin::mapCostsResult);
          }

          @Override
          public java.util.concurrent.CompletionStage<ReloadResult> reload() {
            var manager = reloadManagerReference.get();
            if (manager == null) {
              return CompletableFuture.completedFuture(new ReloadResult(ReloadStatus.UNAVAILABLE));
            }
            var request = manager.reload();
            return request
                .completion()
                .thenApply(
                    result -> {
                      getLogger()
                          .info(
                              "event=configuration_reload status="
                                  + result.status().name()
                                  + " code="
                                  + result.code().name()
                                  + " generation="
                                  + result.generation());
                      return mapReloadResult(result);
                    });
          }
        };
    var command =
        new AgentCommand(
            this,
            () -> {
              var current = coordinatorReference.get();
              return current == null ? AgentStatus.unregistered(null) : current.diagnostics();
            },
            control,
            requests,
            proposalResponses(),
            (playerId, action, viewId) -> {
              var clientAction =
                  switch (action) {
                    case PIN -> ClientUiCommandGateway.Action.PIN;
                    case UNPIN -> ClientUiCommandGateway.Action.UNPIN;
                    case CLEAR -> ClientUiCommandGateway.Action.CLEAR;
                    case PREVIEW -> ClientUiCommandGateway.Action.LITEMATICA_PREVIEW_LOAD;
                    case REMOVE -> ClientUiCommandGateway.Action.LITEMATICA_PREVIEW_REMOVE;
                    case MATERIALS -> ClientUiCommandGateway.Action.LITEMATICA_MATERIAL_LIST_OPEN;
                  };
              return clientUi.invoke(playerId, clientAction, viewId)
                      == ClientUiCommandGateway.Result.SENT
                  ? AgentUiControl.Result.SENT
                  : AgentUiControl.Result.CLIENT_UNAVAILABLE;
            },
            management,
            new OwnerReloadAuthorizer(currentAdminPolicy),
            new AdminToggleAuthorizer(currentAdminPolicy),
            task -> getServer().getScheduler().runTask(this, task));

    var coordinator =
        new PaperStartupCoordinator(
            worker,
            () -> {
              installPrivateResource(
                  dataDirectory, configPath, defaultConfigResource, MAX_DEFAULT_CONFIG_BYTES);
              var result =
                  localChecks.run(
                      new LocalStartupChecks.Request(
                          configPath,
                          dataDirectory,
                          checkedStartupEnvironment,
                          Runtime.version().feature(),
                          minecraftVersion));
              runtimeModeReference.set(result.config().runtime().mode());
              if (result.config().runtime().mode() == RuntimeDeploymentMode.MANAGED) {
                installPrivateResource(
                    managedDirectory,
                    managedConfigPath,
                    "managed-runtime/config.template.yml",
                    MAX_DEFAULT_CONFIG_BYTES);
                new ManagedRuntimeSetupProbe().verify(managedConfigPath);
              }
              var landmarkCatalog = new LandmarkCatalogLoader().loadOrCreate(dataDirectory);
              var trustedReloadManager = reloadManagerReference.get();
              if (trustedReloadManager != null
                  && trustedReloadManager.restartRequired(result.config()).isPresent()) {
                throw new StartupFailure(
                    StartupFailure.Code.PAPER_RESTART_REQUIRED, StartupFailure.Stage.CONFIG);
              }
              var runtime = result.config().runtime();
              var settings =
                  new RuntimeConnectionSettings(
                      runtime.mode(),
                      runtime.endpoint(),
                      result.config().serverId(),
                      runtime.serverToken(),
                      componentVersion,
                      runtime.connectTimeout(),
                      runtime.handshakeTimeout());
              var warnings =
                  new TreeSet<>(
                      result.warnings().stream().map(warning -> warning.code().name()).toList());
              var checkedStateDirectory = result.config().stateDirectory();
              var trustedStateDirectory = stateDirectoryReference.get();
              if (trustedStateDirectory == null) {
                stateDirectoryReference.set(checkedStateDirectory);
                desiredModeStoreReference.set(new FileDesiredModeStore(checkedStateDirectory));
              } else if (!trustedStateDirectory.equals(checkedStateDirectory)) {
                throw new StartupFailure(
                    StartupFailure.Code.STATE_DIRECTORY_UNSAFE, StartupFailure.Stage.STATE);
              }
              var desiredModeStore = desiredModeStoreReference.get();
              var desiredMode = desiredModeStore.load();
              var candidateReloadManager =
                  trustedReloadManager == null
                      ? new ReloadManager(
                          worker,
                          new PaperReloadCandidateSource(
                              configPath, dataDirectory, checkedStartupEnvironment),
                          result.config(),
                          operationalGate)
                      : trustedReloadManager;
              var effectivePolicy = candidateReloadManager.snapshot();
              var proposalPolicy = effectivePolicy.proposalPolicy();
              var proposalAudit = FileProposalAuditLog.open(checkedStateDirectory);
              var proposalService =
                  new ProposalService(
                      result.config().serverId(),
                      new InMemoryProposalRepository(),
                      ignored -> java.util.Optional.empty(),
                      proposalAuthorizer,
                      ignored -> false,
                      proposalAudit,
                      operationalGate,
                      Clock.systemUTC(),
                      Duration.ofSeconds(60));
              var capabilityPublication =
                  prepareCapabilityCatalog(result, pluginInventory, warnings);
              var readiness =
                  new CoreReadiness(
                      settings,
                      List.copyOf(warnings),
                      desiredMode,
                      desiredModeStore,
                      effectivePolicy.adminPolicy(),
                      new CoreReadiness.Publication() {
                        private final java.util.concurrent.atomic.AtomicBoolean finalized =
                            new java.util.concurrent.atomic.AtomicBoolean();

                        @Override
                        public void publish() {
                          if (!finalized.compareAndSet(false, true)) {
                            throw new IllegalStateException(
                                "Startup candidate was already finalized");
                          }
                          try {
                            if (trustedReloadManager == null) {
                              if (!reloadManagerReference.compareAndSet(
                                  null, candidateReloadManager)) {
                                throw new IllegalStateException(
                                    "Trusted reload manager changed during startup");
                              }
                            } else if (reloadManagerReference.get() != trustedReloadManager) {
                              throw new IllegalStateException(
                                  "Trusted reload manager changed during recovery");
                            }
                            landmarkCatalogReference.set(landmarkCatalog);
                            serverIdReference.set(result.config().serverId());
                            capabilityPublication.run();
                            proposalPolicyReference.set(proposalPolicy);
                            proposalServiceReference.set(proposalService);
                          } catch (RuntimeException error) {
                            if (trustedReloadManager == null) {
                              reloadManagerReference.compareAndSet(candidateReloadManager, null);
                              candidateReloadManager.close();
                            }
                            throw error;
                          }
                        }

                        @Override
                        public void discard() {
                          if (finalized.compareAndSet(false, true)
                              && trustedReloadManager == null) {
                            candidateReloadManager.close();
                          }
                        }
                      });
              return readiness;
            },
            connector,
            task -> getServer().getScheduler().runTask(this, task),
            new PaperStartupCoordinator.CommandGate() {
              @Override
              public void register() {
                registration.register(command);
              }

              @Override
              public void unregister() {
                registration.unregister();
              }
            },
            this::isEnabled,
            new PaperStartupCoordinator.EventSink() {
              @Override
              public void failure(String stage, String code) {
                getLogger().severe("event=startup_failed stage=" + stage + " code=" + code);
              }

              @Override
              public void warning(String stage, String code) {
                logWarning(stage, code);
              }

              @Override
              public void available(AgentHealth health) {
                getLogger().info("event=startup_ready health=" + health.name());
              }

              @Override
              public void offline(OfflineReason reason) {
                getLogger().info("event=agent_offline reason=" + reason.name());
              }
            },
            new OfflineCleanup(
                requests,
                (epoch, reason) -> {
                  var proposals = proposalServiceReference.get();
                  if (proposals != null) {
                    proposals.quiesce(epoch, reason);
                  }
                },
                (epoch, reason) -> {},
                (epoch, reason) -> clientChannel.clearTransientViews()),
            operationalGate,
            requests,
            runtimeSupervisor);
    coordinatorReference.set(coordinator);
    coordinator.start();
    getLogger().info("event=startup_started");
  }

  @Override
  public void onDisable() {
    var current = coordinatorReference.getAndSet(null);
    if (current != null) {
      current.close();
    }
    proposalServiceReference.set(null);
    proposalPolicyReference.set(lockedProposalPolicy());
    var reloadManager = reloadManagerReference.getAndSet(null);
    if (reloadManager != null) {
      reloadManager.close();
    }
    var requests = requestServiceReference.getAndSet(null);
    if (requests != null) {
      requests.close();
    }
    var clientChannel = clientChannelReference.getAndSet(null);
    if (clientChannel != null) {
      clientChannel.close();
    }
    var setupRegistration = setupRegistrationReference.getAndSet(null);
    if (setupRegistration != null) {
      setupRegistration.close();
    }
  }

  private void logWarning(String stage, String code) {
    getLogger().warning("event=startup_warning stage=" + stage + " code=" + code);
  }

  private Runnable prepareCapabilityCatalog(
      dev.minecraftagent.paper.startup.LocalStartupResult startup,
      PaperInstalledPluginInventory pluginInventory,
      Set<String> warnings) {
    if (startup.warnings().stream()
        .anyMatch(
            warning -> warning.code() == StartupWarning.Code.OPTIONAL_CAPABILITY_UNAVAILABLE)) {
      return () ->
          getLogger()
              .warning(
                  "event=capability_catalog status=RETAINED generation="
                      + capabilityRegistry.snapshot().generation()
                      + " code=OPTIONAL_CAPABILITY_UNAVAILABLE");
    }

    var approvals = startup.config().capabilityApprovals();
    var loaded =
        new CapabilityPackLoader(pluginInventory, approvals::contains)
            .load(startup.config().optionalCapabilityDirectory());
    var preview = capabilityRegistry.preview(loaded);
    if (!preview.publishable()) {
      warnings.add(StartupWarning.Code.CAPABILITY_CATALOG_UNAVAILABLE.name());
    } else if (loaded.drafts().stream().anyMatch(draft -> !draft.enabled())) {
      warnings.add(StartupWarning.Code.CAPABILITY_PACK_DISABLED.name());
    }
    return () -> {
      try {
        publishCapabilityCatalog(loaded, preview);
      } catch (RuntimeException error) {
        getLogger()
            .warning(
                "event=capability_catalog status=RETAINED code=CAPABILITY_CATALOG_UNAVAILABLE");
      }
    };
  }

  private void publishCapabilityCatalog(
      dev.minecraftagent.paper.capability.load.CapabilityLoadResult loaded,
      dev.minecraftagent.paper.capability.registry.CapabilityRegistryPreview preview) {
    var publication = capabilityRegistry.publish(preview);
    var diffPrefix =
        publication.status() == CapabilityRegistry.PublishStatus.PUBLISHED ? "" : "proposed_";
    getLogger()
        .info(
            "event=capability_catalog status="
                + publication.status().name()
                + " generation="
                + publication.snapshot().generation()
                + " "
                + diffPrefix
                + "added="
                + formatCapabilityIds(preview.diff().added())
                + " "
                + diffPrefix
                + "removed="
                + formatCapabilityIds(preview.diff().removed())
                + " "
                + diffPrefix
                + "changed="
                + formatCapabilityIds(preview.diff().changed())
                + " "
                + diffPrefix
                + "unchanged="
                + formatCapabilityIds(preview.diff().unchanged()));

    var diagnosticCounts = new TreeMap<CapabilityDiagnostic.Code, Integer>();
    for (var diagnostic : loaded.globalDiagnostics()) {
      getLogger().warning("event=capability_catalog_diagnostic code=" + diagnostic.code().name());
    }
    for (var draft : loaded.drafts()) {
      for (var diagnostic : draft.diagnostics()) {
        diagnosticCounts.merge(diagnostic.code(), 1, Integer::sum);
      }
      if (draft.diagnostics().stream()
          .anyMatch(
              diagnostic -> diagnostic.code() == CapabilityDiagnostic.Code.APPROVAL_REQUIRED)) {
        draft
            .identity()
            .ifPresent(
                identity ->
                    getLogger()
                        .info(
                            "event=capability_approval_required id="
                                + identity.id()
                                + " version="
                                + identity.version()
                                + " sha256="
                                + identity.contentSha256()));
      }
    }
    for (var entry : diagnosticCounts.entrySet()) {
      getLogger()
          .warning(
              "event=capability_manifest_disabled code="
                  + entry.getKey().name()
                  + " count="
                  + entry.getValue());
    }
  }

  private static String formatCapabilityIds(Set<String> ids) {
    return ids.isEmpty() ? "-" : String.join(",", ids);
  }

  private ProposalResponseGateway proposalResponses() {
    return new ProposalResponseGateway() {
      @Override
      public java.util.concurrent.CompletionStage<Result> confirm(UUID playerId, UUID proposalId) {
        var proposals = proposalServiceReference.get();
        if (proposals == null) {
          return CompletableFuture.completedFuture(Result.UNAVAILABLE);
        }
        var result = proposals.confirm(proposalId, playerId);
        if (result.status() == ProposalConfirmationResult.Status.EXECUTED
            && "EXECUTED_AUDIT_INCOMPLETE".equals(result.code())) {
          getLogger().warning("event=proposal_audit_incomplete code=POST_EXECUTION_AUDIT_FAILED");
        }
        return CompletableFuture.completedFuture(mapProposalResult(result, true));
      }

      @Override
      public java.util.concurrent.CompletionStage<Result> reject(UUID playerId, UUID proposalId) {
        var proposals = proposalServiceReference.get();
        if (proposals == null) {
          return CompletableFuture.completedFuture(Result.UNAVAILABLE);
        }
        return CompletableFuture.completedFuture(
            mapProposalResult(proposals.reject(proposalId, playerId), false));
      }
    };
  }

  private void invalidatePlayerProposals(UUID playerId) {
    var proposals = proposalServiceReference.get();
    if (proposals == null) {
      return;
    }
    try {
      proposals.invalidatePlayer(playerId);
    } catch (RuntimeException error) {
      getLogger().warning("event=proposal_warning code=PLAYER_INVALIDATION_AUDIT_FAILED");
    }
  }

  private static ProposalResponseGateway.Result mapProposalResult(
      ProposalConfirmationResult result, boolean confirmation) {
    return switch (result.status()) {
      case EXECUTED -> ProposalResponseGateway.Result.CONFIRMED;
      case FAILED -> ProposalResponseGateway.Result.FAILED;
      case REJECTED ->
          !confirmation && "PLAYER_REJECTED".equals(result.code())
              ? ProposalResponseGateway.Result.REJECTED
              : ProposalResponseGateway.Result.UNAVAILABLE;
    };
  }

  private static ProposalAuthorizer.Policy lockedProposalPolicy() {
    return new ProposalAuthorizer.Policy(
        Set.of(), ProposalAuthorizer.WriteAccess.OWNER, ProposalAuthorizer.WriteAccess.OWNER);
  }

  private static AgentManagementGateway.ReloadResult mapReloadResult(
      dev.minecraftagent.paper.management.reload.ReloadResult result) {
    var status =
        switch (result.code()) {
          case RELOAD_APPLIED -> AgentManagementGateway.ReloadStatus.RELOADED;
          case RELOAD_UNCHANGED -> AgentManagementGateway.ReloadStatus.UNCHANGED;
          case RELOAD_IN_PROGRESS -> AgentManagementGateway.ReloadStatus.BUSY;
          case RELOAD_MANAGER_CLOSED, RELOAD_OPERATION_NOT_ONLINE ->
              AgentManagementGateway.ReloadStatus.UNAVAILABLE;
          case RELOAD_CONFIG_REJECTED -> AgentManagementGateway.ReloadStatus.INVALID_CONFIG;
          case RELOAD_RESTART_REQUIRED_SERVER_ID,
              RELOAD_RESTART_REQUIRED_RUNTIME_MODE,
              RELOAD_RESTART_REQUIRED_RUNTIME_ENDPOINT,
              RELOAD_RESTART_REQUIRED_RUNTIME_TOKEN,
              RELOAD_RESTART_REQUIRED_RUNTIME_CONNECT_TIMEOUT,
              RELOAD_RESTART_REQUIRED_RUNTIME_HANDSHAKE_TIMEOUT,
              RELOAD_RESTART_REQUIRED_STATE_DIRECTORY,
              RELOAD_RESTART_REQUIRED_CAPABILITY_DIRECTORY,
              RELOAD_RESTART_REQUIRED_CAPABILITY_APPROVALS ->
              AgentManagementGateway.ReloadStatus.RESTART_REQUIRED;
          case RELOAD_WORKER_REJECTED,
              RELOAD_CANDIDATE_LOAD_FAILED,
              RELOAD_GENERATION_EXHAUSTED,
              RELOAD_STALE_COMPLETION ->
              AgentManagementGateway.ReloadStatus.FAILED;
        };
    return new AgentManagementGateway.ReloadResult(status);
  }

  private static AgentManagementGateway.CostsResult mapCostsResult(
      AgentRequestService.CostsQueryResult result) {
    return switch (result.status()) {
      case UNAVAILABLE -> AgentManagementGateway.CostsResult.unavailable();
      case TIMED_OUT, FAILED -> AgentManagementGateway.CostsResult.failed();
      case AVAILABLE -> {
        var costs = Objects.requireNonNull(result.costs());
        var day = costs.currentDay();
        var month = costs.currentMonth();
        var budget = costs.budget();
        yield AgentManagementGateway.CostsResult.available(
            new AgentManagementGateway.CostsSnapshot(
                new AgentManagementGateway.UsageWindow(
                    day.period(),
                    day.admittedRequests(),
                    day.providerCalls(),
                    day.reportedProviderCalls(),
                    day.estimatedProviderCalls(),
                    day.inputTokens(),
                    day.outputTokens(),
                    day.costMicroUsd()),
                new AgentManagementGateway.UsageWindow(
                    month.period(),
                    month.admittedRequests(),
                    month.providerCalls(),
                    month.reportedProviderCalls(),
                    month.estimatedProviderCalls(),
                    month.inputTokens(),
                    month.outputTokens(),
                    month.costMicroUsd()),
                budget.month(),
                budget.limitMicroUsd(),
                budget.settledMicroUsd(),
                budget.activeReservationsMicroUsd(),
                budget.remainingMicroUsd(),
                budget.exhausted()));
      }
    };
  }

  private static void installPrivateResource(
      Path directory, Path target, String resourceName, int maximumBytes) throws StartupFailure {
    var normalizedDirectory = directory.toAbsolutePath().normalize();
    var normalizedTarget = target.toAbsolutePath().normalize();
    if (!normalizedDirectory.equals(normalizedTarget.getParent())) {
      throw configFailure();
    }
    if (Files.exists(normalizedTarget, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
      return;
    }

    Path temporary = null;
    try {
      Files.createDirectories(normalizedDirectory);
      Files.setPosixFilePermissions(normalizedDirectory, PRIVATE_DIRECTORY_PERMISSIONS);
      temporary = normalizedDirectory.resolve(".resource-install-" + UUID.randomUUID());
      try (var input =
              MinecraftAgentPlugin.class.getClassLoader().getResourceAsStream(resourceName);
          var output =
              FileChannel.open(
                  temporary,
                  java.util.Set.of(
                      java.nio.file.StandardOpenOption.CREATE_NEW,
                      java.nio.file.StandardOpenOption.WRITE),
                  PosixFilePermissions.asFileAttribute(PRIVATE_FILE_PERMISSIONS))) {
        if (input == null) {
          throw configFailure();
        }
        var content = input.readNBytes(maximumBytes + 1);
        if (content.length > maximumBytes) {
          throw configFailure();
        }
        var buffer = ByteBuffer.wrap(content);
        while (buffer.hasRemaining()) {
          output.write(buffer);
        }
        output.force(true);
      }
      movePrivateFile(temporary, normalizedTarget);
      temporary = null;
      Files.setPosixFilePermissions(normalizedTarget, PRIVATE_FILE_PERMISSIONS);
    } catch (StartupFailure failure) {
      throw failure;
    } catch (FileAlreadyExistsException exception) {
      // Another startup task installed the same immutable default; the loader validates it next.
    } catch (IOException | SecurityException | UnsupportedOperationException exception) {
      throw configFailure();
    } finally {
      if (temporary != null) {
        try {
          Files.deleteIfExists(temporary);
        } catch (IOException ignored) {
          // The stable startup failure remains authoritative.
        }
      }
    }
  }

  private static void movePrivateFile(Path source, Path target) throws IOException {
    try {
      Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
    } catch (AtomicMoveNotSupportedException exception) {
      Files.move(source, target);
    }
  }

  private static StartupFailure configFailure() {
    return new StartupFailure(
        StartupFailure.Code.PAPER_CONFIG_INVALID, StartupFailure.Stage.CONFIG);
  }

  private static String generateManagedServerToken() {
    var bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    return HexFormat.of().formatHex(bytes);
  }

  private static boolean hasEmbeddedManagedRuntime() {
    try (var input =
        MinecraftAgentPlugin.class
            .getClassLoader()
            .getResourceAsStream("managed-runtime/sidecar.zip")) {
      return input != null;
    } catch (IOException error) {
      return false;
    }
  }

  private static RedactedSetupSnapshot setupSnapshot(
      PaperStartupCoordinator coordinator, RuntimeDeploymentMode mode) {
    if (mode == RuntimeDeploymentMode.EXTERNAL) {
      return RedactedSetupSnapshot.of(SetupState.EXTERNAL);
    }
    if (coordinator == null) {
      return RedactedSetupSnapshot.of(SetupState.SETUP_REQUIRED);
    }
    var status = coordinator.diagnostics();
    return switch (status.state()) {
      case STARTING -> RedactedSetupSnapshot.of(SetupState.STARTING);
      case ONLINE -> RedactedSetupSnapshot.of(SetupState.READY);
      case STOPPING -> RedactedSetupSnapshot.of(SetupState.READY);
      case OFFLINE ->
          status.failureCode() == null
              ? RedactedSetupSnapshot.of(SetupState.READY)
              : RedactedSetupSnapshot.failed(safeSetupDiagnostic(status.failureCode()));
      case UNREGISTERED -> {
        var failureCode = status.failureCode();
        if (failureCode == null
            || failureCode.equals(StartupFailure.Code.MANAGED_RUNTIME_SETUP_REQUIRED.name())) {
          yield RedactedSetupSnapshot.of(SetupState.SETUP_REQUIRED);
        }
        yield RedactedSetupSnapshot.failed(safeSetupDiagnostic(failureCode));
      }
    };
  }

  private static String safeSetupDiagnostic(String code) {
    return code.length() <= 64 ? code : "SETUP_FAILED";
  }
}
