package dev.minecraftagent.standalone.common;

import dev.minecraftagent.standalone.core.contract.RuntimeClientProfile;
import dev.minecraftagent.standalone.supervisor.ExecutorSupervisorScheduler;
import dev.minecraftagent.standalone.supervisor.RuntimeSupervisor;
import dev.minecraftagent.standalone.supervisor.SupervisorException;
import dev.minecraftagent.standalone.supervisor.SupervisorPolicy;
import dev.minecraftagent.standalone.supervisor.install.InstalledRuntime;
import dev.minecraftagent.standalone.supervisor.install.ManagedRuntimeInstallException;
import dev.minecraftagent.standalone.supervisor.install.ManagedRuntimeInstaller;
import dev.minecraftagent.standalone.supervisor.install.ManagedRuntimeMaintenanceResult;
import dev.minecraftagent.standalone.supervisor.install.RuntimePlatform;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Owns configuration, sidecar installation, one Runtime child, and one authenticated connector. */
public final class ClientRuntimeController implements AutoCloseable {
  private final Path root;
  private final ClientProfileStore profileStore;
  private final ClientProfileSession profileSession = new ClientProfileSession();
  private final ClientSecretResolver secretResolver = new ClientSecretResolver();
  private final ManagedRuntimeInstaller installer = new ManagedRuntimeInstaller();
  private final Supplier<EmbeddedRuntimeDistribution> distribution;
  private final String componentVersion;
  private final ExecutorService lifecycleExecutor;

  private RuntimeSupervisor supervisor;
  private JavaHttpLocalConnector connector;
  private LocalConnector.Session connectorSession;
  private CompletableFuture<ClientProfileSnapshot> pendingStart;
  private String startupFailureCode;
  private long generation;
  private boolean closed;

  public ClientRuntimeController(Path root, String componentVersion) {
    this(root, componentVersion, ClientRuntimeController.class.getClassLoader());
  }

  public ClientRuntimeController(
      Path root, String componentVersion, ClassLoader runtimeResourceLoader) {
    this(root, componentVersion, distribution(runtimeResourceLoader));
  }

  ClientRuntimeController(
      Path root, String componentVersion, Supplier<EmbeddedRuntimeDistribution> distribution) {
    this.root = root.toAbsolutePath().normalize();
    if (!this.root.equals(root)) {
      throw new IllegalArgumentException("Client Runtime root must be absolute and normalized");
    }
    this.componentVersion = Objects.requireNonNull(componentVersion, "componentVersion");
    this.distribution = Objects.requireNonNull(distribution, "distribution");
    this.profileStore = new ClientProfileStore(root);
    this.lifecycleExecutor =
        Executors.newSingleThreadExecutor(
            action -> {
              var thread = new Thread(action, "agma-standalone-runtime-lifecycle");
              thread.setDaemon(true);
              return thread;
            });
    loadExistingProfile();
  }

  public ClientRuntimeView view() {
    final ClientProfileSnapshot profile;
    final String failureCode;
    final RuntimeSupervisor activeSupervisor;
    final LocalConnector.Session activeSession;
    synchronized (this) {
      profile = profileSession.snapshot();
      failureCode = startupFailureCode;
      activeSupervisor = supervisor;
      activeSession = connectorSession;
    }
    var tools = activeSession == null ? Set.<String>of() : activeSession.activeTools();
    var activeConnectorState = activeSession == null ? null : activeSession.state();
    var supervisorSnapshot = activeSupervisor == null ? null : activeSupervisor.snapshot();
    return new ClientRuntimeView(
        profile, tools, failureCode, supervisorSnapshot, activeConnectorState);
  }

  public synchronized RuntimeClientProfile configure(ClientSetup setup) {
    requireOpen();
    var state = profileSession.snapshot().state();
    if (state == ClientLifecycleState.STARTING
        || state == ClientLifecycleState.READY
        || state == ClientLifecycleState.STOPPING) {
      throw new IllegalStateException("Runtime configuration cannot change while active");
    }
    var profile = profileStore.configure(setup);
    if (state != ClientLifecycleState.UNCONFIGURED) {
      profileSession.clear();
    }
    profileSession.configure(profile);
    startupFailureCode = null;
    return profile;
  }

  public synchronized void deleteConfiguration() {
    requireOpen();
    var state = profileSession.snapshot().state();
    if (state == ClientLifecycleState.STARTING
        || state == ClientLifecycleState.READY
        || state == ClientLifecycleState.STOPPING) {
      throw new IllegalStateException("Runtime must be stopped before deleting private data");
    }
    profileStore.deleteConfiguration();
    if (state != ClientLifecycleState.UNCONFIGURED) {
      profileSession.clear();
    }
    startupFailureCode = null;
  }

  public synchronized ManagedRuntimeMaintenanceResult uninstallManagedRuntime() {
    requireOpen();
    var state = profileSession.snapshot().state();
    if (state == ClientLifecycleState.STARTING
        || state == ClientLifecycleState.READY
        || state == ClientLifecycleState.STOPPING) {
      throw new IllegalStateException("Runtime must be stopped before uninstall");
    }
    try {
      return installer.uninstall(root.resolve("managed-runtime"));
    } catch (ManagedRuntimeInstallException failure) {
      throw new ClientRuntimeException(failure.code(), "Managed Runtime uninstall failed", failure);
    }
  }

  public CompletionStage<ClientProfileSnapshot> start(ClientToolHandler toolHandler) {
    Objects.requireNonNull(toolHandler, "toolHandler");
    final RuntimeClientProfile profile;
    final long requestedGeneration;
    final CompletableFuture<ClientProfileSnapshot> result;
    synchronized (this) {
      requireOpen();
      var state = profileSession.snapshot().state();
      if (state == ClientLifecycleState.READY) {
        return CompletableFuture.completedFuture(profileSession.snapshot());
      }
      if (state == ClientLifecycleState.STARTING && pendingStart != null) {
        return pendingStart;
      }
      if (state != ClientLifecycleState.STOPPED && state != ClientLifecycleState.ERROR) {
        return failed(
            new ClientRuntimeException("RUNTIME_NOT_CONFIGURED", "Runtime is not configured"));
      }
      try {
        profile = profileStore.prepareStart();
      } catch (RuntimeException failure) {
        failProfile(code(failure));
        return failed(failure);
      }
      profileSession.clear();
      profileSession.configure(profile);
      profileSession.transition(ClientLifecycleState.STARTING);
      startupFailureCode = null;
      requestedGeneration = ++generation;
      result = new CompletableFuture<>();
      pendingStart = result;
    }
    lifecycleExecutor.execute(() -> launch(requestedGeneration, profile, toolHandler, result));
    return result;
  }

  public CompletionStage<ClientProfileSnapshot> stop() {
    final RuntimeSupervisor ownedSupervisor;
    final JavaHttpLocalConnector ownedConnector;
    final LocalConnector.Session ownedSession;
    final CompletableFuture<ClientProfileSnapshot> starting;
    synchronized (this) {
      var state = profileSession.snapshot().state();
      if (state == ClientLifecycleState.UNCONFIGURED || state == ClientLifecycleState.STOPPED) {
        return CompletableFuture.completedFuture(profileSession.snapshot());
      }
      generation++;
      starting = pendingStart;
      pendingStart = null;
      ownedSupervisor = supervisor;
      supervisor = null;
      ownedConnector = connector;
      connector = null;
      ownedSession = connectorSession;
      connectorSession = null;
      if (state == ClientLifecycleState.ERROR) {
        profileSession.transition(ClientLifecycleState.STOPPED);
      } else if (state != ClientLifecycleState.STOPPING) {
        profileSession.transition(ClientLifecycleState.STOPPING);
      }
    }
    if (starting != null) {
      starting.completeExceptionally(new CancellationException("Runtime start cancelled"));
    }
    var closeSession =
        ownedSession == null
            ? CompletableFuture.<Void>completedFuture(null)
            : ownedSession.close().toCompletableFuture().exceptionally(failure -> null);
    return closeSession
        .thenCompose(
            ignored ->
                ownedSupervisor == null
                    ? CompletableFuture.completedFuture(null)
                    : ownedSupervisor.stop().toCompletableFuture().handle((value, failure) -> null))
        .thenApply(
            ignored -> {
              if (ownedConnector != null) {
                ownedConnector.close();
              }
              if (ownedSupervisor != null) {
                ownedSupervisor.close();
              }
              synchronized (this) {
                var state = profileSession.snapshot().state();
                if (state == ClientLifecycleState.STOPPING) {
                  profileSession.transition(ClientLifecycleState.STOPPED);
                }
                startupFailureCode = null;
                return profileSession.snapshot();
              }
            });
  }

  public CompletionStage<TextCompletion> request(String text, UUID sessionId) {
    return request(UUID.randomUUID(), text, sessionId);
  }

  public CompletionStage<TextCompletion> request(UUID requestId, String text, UUID sessionId) {
    Objects.requireNonNull(requestId, "requestId");
    final LocalConnector.Session active;
    final int timeoutSeconds;
    synchronized (this) {
      if (profileSession.snapshot().state() != ClientLifecycleState.READY
          || connectorSession == null) {
        return failed(new ClientRuntimeException("RUNTIME_NOT_READY", "Runtime is not ready"));
      }
      active = connectorSession;
      timeoutSeconds = profileSession.configuredProfile().model().timeoutSeconds();
    }
    return active.request(
        new TextRequest(requestId, sessionId, text, Duration.ofSeconds(timeoutSeconds)));
  }

  public CompletionStage<TextCompletion> request(TextRequest request) {
    Objects.requireNonNull(request, "request");
    synchronized (this) {
      if (profileSession.snapshot().state() != ClientLifecycleState.READY
          || connectorSession == null) {
        return failed(new ClientRuntimeException("RUNTIME_NOT_READY", "Runtime is not ready"));
      }
      return connectorSession.request(request);
    }
  }

  public boolean cancel(UUID requestId) {
    return cancel(requestId, CancelReason.USER_REQUEST);
  }

  public boolean cancel(UUID requestId, CancelReason reason) {
    Objects.requireNonNull(reason, "reason");
    synchronized (this) {
      return connectorSession != null && connectorSession.cancel(requestId, reason);
    }
  }

  public CompletionStage<RuntimeStatus> queryStatus() {
    synchronized (this) {
      if (connectorSession == null) {
        return failed(new ClientRuntimeException("RUNTIME_NOT_READY", "Runtime is not ready"));
      }
      return connectorSession.queryStatus(Duration.ofSeconds(3));
    }
  }

  public ClientProfileStore profileStore() {
    return profileStore;
  }

  @Override
  public void close() {
    synchronized (this) {
      if (closed) {
        return;
      }
      closed = true;
    }
    try {
      stop().toCompletableFuture().get(12, TimeUnit.SECONDS);
    } catch (Exception ignored) {
      RuntimeSupervisor remaining;
      JavaHttpLocalConnector remainingConnector;
      synchronized (this) {
        remaining = supervisor;
        supervisor = null;
        remainingConnector = connector;
        connector = null;
        connectorSession = null;
      }
      if (remainingConnector != null) {
        remainingConnector.close();
      }
      if (remaining != null) {
        remaining.close();
      }
    } finally {
      lifecycleExecutor.shutdownNow();
    }
  }

  private void loadExistingProfile() {
    if (!profileStore.isConfigured()) {
      return;
    }
    try {
      profileSession.configure(profileStore.load());
    } catch (RuntimeException failure) {
      startupFailureCode = code(failure);
    }
  }

  private void launch(
      long requestedGeneration,
      RuntimeClientProfile profile,
      ClientToolHandler toolHandler,
      CompletableFuture<ClientProfileSnapshot> result) {
    RuntimeSupervisor createdSupervisor = null;
    JavaHttpLocalConnector createdConnector = null;
    try {
      PrivateFilePermissions.prepareDirectory(root);
      var embedded = distribution.get();
      var platform = RuntimePlatform.current();
      installer.cleanupStaleStaging(root.resolve("managed-runtime"));
      InstalledRuntime installed =
          installer.install(
              root.resolve("managed-runtime"), platform, embedded.artifact(), embedded.source());
      var spec =
          installed.launchSpec(
              profile.identity().installationId(), root, profileStore.profilePath(), Map.of());
      createdConnector = new JavaHttpLocalConnector(componentVersion);
      createdSupervisor =
          new RuntimeSupervisor(
              new VerifiedRuntimePathPolicy(installed.installationRoot(), root),
              new SystemRuntimeProcessLauncher(),
              new LoopbackRuntimeHealthProbe(profile.transport().port()),
              new ExecutorSupervisorScheduler("agma-standalone-runtime-supervisor"),
              SupervisorPolicy.defaults());
      createdSupervisor.configure(spec);
      synchronized (this) {
        if (!active(requestedGeneration)) {
          throw new CancellationException("Runtime start superseded");
        }
        supervisor = createdSupervisor;
        connector = createdConnector;
      }
      var ownedSupervisor = createdSupervisor;
      var ownedConnector = createdConnector;
      createdSupervisor
          .start()
          .whenComplete(
              (snapshot, failure) -> {
                if (failure != null) {
                  finishStartFailure(requestedGeneration, result, failure);
                  return;
                }
                connect(
                    requestedGeneration,
                    profile,
                    toolHandler,
                    installed,
                    ownedSupervisor,
                    ownedConnector,
                    result);
              });
    } catch (Throwable failure) {
      if (createdConnector != null) {
        createdConnector.close();
      }
      if (createdSupervisor != null) {
        createdSupervisor.close();
      }
      finishStartFailure(requestedGeneration, result, failure);
    }
  }

  private void connect(
      long requestedGeneration,
      RuntimeClientProfile profile,
      ClientToolHandler toolHandler,
      InstalledRuntime installed,
      RuntimeSupervisor ownedSupervisor,
      JavaHttpLocalConnector ownedConnector,
      CompletableFuture<ClientProfileSnapshot> result) {
    final CompletionStage<LocalConnector.Session> connection;
    try (var secrets = secretResolver.resolve(profile, root, System.getenv())) {
      connection = ownedConnector.connect(profile, secrets.connectorToken());
    } catch (RuntimeException failure) {
      finishStartFailure(requestedGeneration, result, failure);
      return;
    }
    connection.whenComplete(
        (session, failure) -> {
          if (failure != null) {
            finishStartFailure(requestedGeneration, result, failure);
            return;
          }
          try {
            session.setToolHandler(toolHandler);
            synchronized (this) {
              if (!active(requestedGeneration)
                  || supervisor != ownedSupervisor
                  || connector != ownedConnector) {
                session.close();
                return;
              }
              connectorSession = session;
              pendingStart = null;
              startupFailureCode = null;
              result.complete(profileSession.transition(ClientLifecycleState.READY));
            }
            lifecycleExecutor.execute(
                () -> {
                  try {
                    installer.pruneVersions(root.resolve("managed-runtime"), installed);
                  } catch (ManagedRuntimeInstallException ignored) {
                    // A verified current Runtime remains usable when optional pruning fails.
                  }
                });
          } catch (RuntimeException exception) {
            session.close();
            finishStartFailure(requestedGeneration, result, exception);
          }
        });
  }

  private void finishStartFailure(
      long requestedGeneration,
      CompletableFuture<ClientProfileSnapshot> result,
      Throwable rawFailure) {
    var failure = unwrap(rawFailure);
    RuntimeSupervisor failedSupervisor;
    JavaHttpLocalConnector failedConnector;
    synchronized (this) {
      if (!active(requestedGeneration)) {
        result.completeExceptionally(failure);
        return;
      }
      failedSupervisor = supervisor;
      supervisor = null;
      failedConnector = connector;
      connector = null;
      connectorSession = null;
      pendingStart = null;
      failProfile(code(failure));
    }
    if (failedConnector != null) {
      failedConnector.close();
    }
    if (failedSupervisor != null) {
      failedSupervisor.close();
    }
    result.completeExceptionally(failure);
  }

  private synchronized boolean active(long requestedGeneration) {
    return !closed
        && generation == requestedGeneration
        && profileSession.snapshot().state() == ClientLifecycleState.STARTING;
  }

  private void failProfile(String code) {
    startupFailureCode = code;
    if (profileSession.snapshot().state() != ClientLifecycleState.UNCONFIGURED) {
      profileSession.fail(code);
    }
  }

  private void requireOpen() {
    if (closed) {
      throw new IllegalStateException("Client Runtime controller is closed");
    }
  }

  private static String code(Throwable raw) {
    var failure = unwrap(raw);
    if (failure instanceof ClientRuntimeException value) {
      return value.code();
    }
    if (failure instanceof ClientConfigurationException value) {
      return value.code();
    }
    if (failure instanceof ManagedRuntimeInstallException value) {
      return value.code();
    }
    if (failure instanceof SupervisorException value) {
      return value.code();
    }
    if (failure instanceof ConnectorException value) {
      return value.code();
    }
    if (failure instanceof CancellationException) {
      return "RUNTIME_START_CANCELLED";
    }
    return "RUNTIME_START_FAILED";
  }

  private static Throwable unwrap(Throwable raw) {
    var failure = raw;
    while ((failure instanceof CompletionException
            || failure instanceof java.util.concurrent.ExecutionException)
        && failure.getCause() != null) {
      failure = failure.getCause();
    }
    return failure;
  }

  private static <T> CompletionStage<T> failed(Throwable failure) {
    return CompletableFuture.failedFuture(failure);
  }

  private static Supplier<EmbeddedRuntimeDistribution> distribution(ClassLoader loader) {
    Objects.requireNonNull(loader, "runtimeResourceLoader");
    return () -> EmbeddedRuntimeDistribution.load(loader);
  }
}
