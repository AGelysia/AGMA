package dev.minecraftagent.paper.runtime;

import dev.minecraftagent.paper.transport.RuntimeConnectionSettings;
import dev.minecraftagent.standalone.supervisor.OwnedRuntimeProcess;
import dev.minecraftagent.standalone.supervisor.RuntimeLaunchSpec;
import dev.minecraftagent.standalone.supervisor.RuntimePathPolicy;
import dev.minecraftagent.standalone.supervisor.RuntimeProcessLauncher;
import dev.minecraftagent.standalone.supervisor.SupervisorException;
import dev.minecraftagent.standalone.supervisor.SupervisorPolicy;
import dev.minecraftagent.standalone.supervisor.SupervisorScheduler;
import dev.minecraftagent.standalone.supervisor.SupervisorSnapshot;
import dev.minecraftagent.standalone.supervisor.SupervisorState;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Paper binding over the shared runtime supervisor core: it contributes the embedded sidecar
 * provisioning, private launch environment, and log policy, while the core owns process-exit
 * monitoring and bounded restarts.
 */
public final class ManagedRuntimeSupervisor
    implements dev.minecraftagent.paper.runtime.RuntimeSupervisor {
  private static final java.util.Set<java.nio.file.attribute.PosixFilePermission>
      PRIVATE_DIRECTORY_PERMISSIONS = PosixFilePermissions.fromString("rwx------");
  private static final java.util.Set<java.nio.file.attribute.PosixFilePermission>
      PRIVATE_FILE_PERMISSIONS = PosixFilePermissions.fromString("rw-------");

  private static final String START_FAILED = "MANAGED_RUNTIME_START_FAILED";
  private static final String EXITED = "MANAGED_RUNTIME_EXITED";
  private static final String START_TIMEOUT = "MANAGED_RUNTIME_START_TIMEOUT";
  private static final String CANCELLED = "MANAGED_RUNTIME_CANCELLED";

  public static final class Settings {
    private final Path executable;
    private final Path entrypoint;
    private final Path configFile;
    private final Path workingDirectory;
    private final Path standardOutputLog;
    private final Path standardErrorLog;
    private final Map<String, String> environmentAllowlist;

    public Settings(
        Path executable,
        Path entrypoint,
        Path configFile,
        Path workingDirectory,
        Path standardOutputLog,
        Path standardErrorLog,
        Map<String, String> environmentAllowlist) {
      this.executable = absolute(executable, "executable");
      this.entrypoint = absolute(entrypoint, "entrypoint");
      this.configFile = absolute(configFile, "configFile");
      this.workingDirectory = absolute(workingDirectory, "workingDirectory");
      this.standardOutputLog = absolute(standardOutputLog, "standardOutputLog");
      this.standardErrorLog = absolute(standardErrorLog, "standardErrorLog");
      Objects.requireNonNull(environmentAllowlist, "environmentAllowlist");
      var environment = new LinkedHashMap<String, String>();
      environmentAllowlist.forEach(
          (key, value) ->
              environment.put(Objects.requireNonNull(key), Objects.requireNonNull(value)));
      this.environmentAllowlist = Map.copyOf(environment);
    }

    public Path executable() {
      return executable;
    }

    public Path entrypoint() {
      return entrypoint;
    }

    public Path configFile() {
      return configFile;
    }

    public Path workingDirectory() {
      return workingDirectory;
    }

    public Path standardOutputLog() {
      return standardOutputLog;
    }

    public Path standardErrorLog() {
      return standardErrorLog;
    }

    public Map<String, String> environmentAllowlist() {
      return environmentAllowlist;
    }

    @Override
    public String toString() {
      return "ManagedRuntimeSupervisor.Settings[paths=<redacted>, environment=<redacted>]";
    }

    private static Path absolute(Path path, String name) {
      return Objects.requireNonNull(path, name).toAbsolutePath().normalize();
    }
  }

  /** Reports one managed Runtime process exit that was not requested by the plugin itself. */
  @FunctionalInterface
  public interface ExitListener {
    void runtimeExited(String failureCode);
  }

  /** Installs the embedded managed Runtime once; restarts reuse the verified installation. */
  @FunctionalInterface
  public interface Provisioner {
    void provision();
  }

  private final Object lock = new Object();
  private final dev.minecraftagent.standalone.supervisor.RuntimeSupervisor supervisor;
  private final Provisioner provisioner;
  private final ProcessFactory processFactory;
  private final HealthProbe healthProbe;
  private final Settings settings;
  private final SupervisorPolicy policy;
  private final SupervisorScheduler scheduler;
  private final ExitListener exitListener;

  private ManagedAttempt current;
  private ManagedRuntimeProcess liveProcess;
  private volatile RuntimeConnectionSettings currentSettings;
  private boolean deliberateStop;
  private boolean closed;

  public ManagedRuntimeSupervisor(
      Provisioner provisioner,
      ProcessFactory processFactory,
      HealthProbe healthProbe,
      SupervisorScheduler scheduler,
      Settings settings,
      SupervisorPolicy policy,
      ExitListener exitListener) {
    this.provisioner = Objects.requireNonNull(provisioner, "provisioner");
    this.processFactory = Objects.requireNonNull(processFactory, "processFactory");
    this.healthProbe = Objects.requireNonNull(healthProbe, "healthProbe");
    this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    this.settings = Objects.requireNonNull(settings, "settings");
    this.policy = Objects.requireNonNull(policy, "policy");
    this.exitListener = Objects.requireNonNull(exitListener, "exitListener");
    this.supervisor =
        new dev.minecraftagent.standalone.supervisor.RuntimeSupervisor(
            new PrivateLogPathPolicy(settings),
            new ManagedRuntimeLauncher(),
            (process, spec) -> probe(process),
            scheduler,
            policy);
  }

  @Override
  public RuntimeStartAttempt prepare(RuntimeConnectionSettings connectionSettings) {
    Objects.requireNonNull(connectionSettings);
    synchronized (lock) {
      if (closed) {
        throw new IllegalStateException("Managed runtime supervisor is closed");
      }
      if (current != null && current.matches(connectionSettings)) {
        return current;
      }
      deliberateStop = true;
    }
    stopForReconfiguration();
    provisioner.provision();
    try {
      supervisor.configure(launchSpec(connectionSettings));
    } catch (SupervisorException failure) {
      throw translate(failure);
    }
    synchronized (lock) {
      deliberateStop = false;
      currentSettings = connectionSettings;
    }
    var attempt = start(connectionSettings);
    synchronized (lock) {
      current = attempt;
    }
    return attempt;
  }

  @Override
  public void stop() {
    synchronized (lock) {
      deliberateStop = true;
      if (current != null) {
        current.stopping = true;
      }
    }
    requestSupervisorStop();
  }

  @Override
  public void close() {
    synchronized (lock) {
      if (closed) {
        return;
      }
      closed = true;
      deliberateStop = true;
      if (current != null) {
        current.stopping = true;
      }
    }
    stopForReconfiguration();
    try {
      supervisor.close();
    } catch (RuntimeException ignored) {
      // The supervisor and its scheduler are already shutting down.
    }
  }

  private void stopForReconfiguration() {
    var stopped = requestSupervisorStop();
    if (stopped == null) {
      return;
    }
    try {
      stopped.toCompletableFuture().get(policy.stopTimeout().toMillis() * 3, TimeUnit.MILLISECONDS);
    } catch (ExecutionException | TimeoutException ignored) {
      // configure() reports a stable failure when the previous Runtime has not stopped yet.
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new RuntimeSupervisorException(START_FAILED, "Managed runtime start was interrupted");
    }
  }

  private CompletionStage<SupervisorSnapshot> requestSupervisorStop() {
    try {
      return supervisor.stop().exceptionally(failure -> null);
    } catch (RuntimeException ignored) {
      return null;
    }
  }

  private ManagedAttempt start(RuntimeConnectionSettings connectionSettings) {
    final CompletionStage<SupervisorSnapshot> started;
    try {
      started = supervisor.start();
    } catch (SupervisorException failure) {
      throw translate(failure);
    }
    var attempt = new ManagedAttempt(connectionSettings);
    var readiness = new CompletableFuture<Void>();
    started.whenComplete(
        (snapshot, failure) -> {
          if (failure != null) {
            readiness.completeExceptionally(translate(failure));
          } else {
            attempt.ready = true;
            readiness.complete(null);
          }
        });
    attempt.readiness = readiness;
    return attempt;
  }

  private RuntimeLaunchSpec launchSpec(RuntimeConnectionSettings connectionSettings) {
    var environment = new LinkedHashMap<String, String>(settings.environmentAllowlist());
    environment.put("AGMA_MANAGED_SERVER_ID", connectionSettings.serverId());
    environment.put(
        "AGMA_MANAGED_RUNTIME_PORT", Integer.toString(connectionSettings.endpoint().getPort()));
    return new RuntimeLaunchSpec(
        UUID.randomUUID(),
        settings.workingDirectory(),
        settings.executable(),
        List.of(
            settings.entrypoint().toString(),
            "--config",
            settings.configFile().toString(),
            "--managed"),
        environment);
  }

  private CompletionStage<Boolean> probe(OwnedRuntimeProcess process) {
    if (!process.isAlive()) {
      return CompletableFuture.completedFuture(false);
    }
    var connectionSettings = currentSettings;
    var ready = false;
    try {
      ready = healthProbe.isReady(connectionSettings);
    } catch (Exception ignored) {
      // A transient probe failure remains indistinguishable from not-ready until timeout.
    }
    return CompletableFuture.completedFuture(ready);
  }

  private void cancel(ManagedAttempt attempt) {
    synchronized (lock) {
      if (current != attempt || attempt.stopping) {
        return;
      }
      attempt.stopping = true;
      deliberateStop = true;
    }
    requestSupervisorStop();
  }

  private void launched(ManagedRuntimeProcess process) {
    synchronized (lock) {
      liveProcess = process;
    }
  }

  private void runtimeExited(ManagedRuntimeProcess process) {
    synchronized (lock) {
      if (closed || deliberateStop || process != liveProcess || current == null || !current.ready) {
        return;
      }
    }
    try {
      scheduler.schedule(() -> exitListener.runtimeExited(EXITED), Duration.ZERO);
    } catch (RuntimeException ignored) {
      // The scheduler closes together with the supervisor during shutdown.
    }
  }

  private static RuntimeException translate(Throwable error) {
    var failure =
        error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
    if (failure instanceof CancellationException) {
      return new RuntimeSupervisorException(CANCELLED, "Managed runtime startup was cancelled");
    }
    if (failure instanceof SupervisorException supervisorFailure) {
      return switch (supervisorFailure.code()) {
        case "RUNTIME_EXITED" ->
            new RuntimeSupervisorException(EXITED, supervisorFailure.getMessage());
        case "START_TIMEOUT" ->
            new RuntimeSupervisorException(START_TIMEOUT, supervisorFailure.getMessage());
        case "SUPERVISOR_CLOSED" ->
            new IllegalStateException("Managed runtime supervisor is closed");
        default -> new RuntimeSupervisorException(START_FAILED, supervisorFailure.getMessage());
      };
    }
    return new RuntimeSupervisorException(START_FAILED, "Managed runtime could not be started");
  }

  private static void preparePrivateLog(Path file) throws IOException {
    var parent = file.getParent();
    if (parent == null) {
      throw new IOException("Managed runtime log parent is unavailable");
    }
    if (!Files.exists(parent, LinkOption.NOFOLLOW_LINKS)) {
      Files.createDirectory(
          parent, PosixFilePermissions.asFileAttribute(PRIVATE_DIRECTORY_PERMISSIONS));
    }
    var parentAttributes =
        Files.readAttributes(parent, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    if (!parentAttributes.isDirectory()
        || parentAttributes.isSymbolicLink()
        || !parentAttributes.permissions().equals(PRIVATE_DIRECTORY_PERMISSIONS)) {
      throw new IOException("Managed runtime log directory is unsafe");
    }
    if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
      Files.createFile(file, PosixFilePermissions.asFileAttribute(PRIVATE_FILE_PERMISSIONS));
    }
    var attributes =
        Files.readAttributes(file, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    var links = Files.getAttribute(file, "unix:nlink", LinkOption.NOFOLLOW_LINKS);
    if (!attributes.isRegularFile()
        || attributes.isSymbolicLink()
        || !attributes.permissions().equals(PRIVATE_FILE_PERMISSIONS)
        || !attributes.owner().equals(parentAttributes.owner())
        || !(links instanceof Number count)
        || count.longValue() != 1L) {
      throw new IOException("Managed runtime log file is unsafe");
    }
  }

  private final class ManagedRuntimeLauncher implements RuntimeProcessLauncher {
    @Override
    public OwnedRuntimeProcess start(RuntimeLaunchSpec spec) throws IOException {
      var builder = new ProcessBuilder(command(spec));
      builder.directory(settings.workingDirectory().toFile());
      builder.environment().clear();
      builder.environment().putAll(spec.environment());
      builder.redirectOutput(
          ProcessBuilder.Redirect.appendTo(settings.standardOutputLog().toFile()));
      builder.redirectError(ProcessBuilder.Redirect.appendTo(settings.standardErrorLog().toFile()));
      var process = new ManagedRuntimeProcess(processFactory.start(builder));
      launched(process);
      return process;
    }

    private List<String> command(RuntimeLaunchSpec spec) {
      var command = new ArrayList<String>();
      command.add(spec.executable().toString());
      command.addAll(spec.arguments());
      return List.copyOf(command);
    }
  }

  private final class ManagedRuntimeProcess implements OwnedRuntimeProcess {
    private final Process process;

    private ManagedRuntimeProcess(Process process) {
      this.process = Objects.requireNonNull(process, "process");
    }

    @Override
    public long pid() {
      return process.pid();
    }

    @Override
    public boolean isAlive() {
      return process.isAlive();
    }

    @Override
    public CompletionStage<Integer> onExit() {
      var exit = process.onExit();
      exit.whenComplete((ignored, error) -> ManagedRuntimeSupervisor.this.runtimeExited(this));
      return exit.thenApply(ignored -> process.exitValue());
    }

    @Override
    public void requestStop() {
      process.destroy();
    }

    @Override
    public void forceStop() {
      process.destroyForcibly();
    }
  }

  private static final class PrivateLogPathPolicy implements RuntimePathPolicy {
    private final Settings settings;

    private PrivateLogPathPolicy(Settings settings) {
      this.settings = settings;
    }

    @Override
    public RuntimeLaunchSpec prepare(RuntimeLaunchSpec requested) throws SupervisorException {
      try {
        preparePrivateLog(settings.standardOutputLog());
        preparePrivateLog(settings.standardErrorLog());
      } catch (IOException error) {
        throw new SupervisorException(
            "PATH_POLICY_FAILED", "Managed runtime log paths could not be prepared");
      }
      return requested;
    }
  }

  private final class ManagedAttempt implements RuntimeStartAttempt {
    private final RuntimeConnectionSettings settings;
    private CompletableFuture<Void> readiness;
    private volatile boolean ready;
    private boolean stopping;

    private ManagedAttempt(RuntimeConnectionSettings settings) {
      this.settings = settings;
    }

    private boolean matches(RuntimeConnectionSettings requested) {
      return !stopping
          && settings.serverId().equals(requested.serverId())
          && settings.endpoint().getPort() == requested.endpoint().getPort()
          && supervisorActive();
    }

    private boolean supervisorActive() {
      var state = supervisor.snapshot().state();
      return state == SupervisorState.STARTING || state == SupervisorState.READY;
    }

    @Override
    public CompletableFuture<Void> ready() {
      return readiness;
    }

    @Override
    public void cancel() {
      ManagedRuntimeSupervisor.this.cancel(this);
    }
  }
}
