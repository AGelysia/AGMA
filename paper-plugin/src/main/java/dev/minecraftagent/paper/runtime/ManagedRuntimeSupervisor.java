package dev.minecraftagent.paper.runtime;

import dev.minecraftagent.paper.transport.RuntimeConnectionSettings;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public final class ManagedRuntimeSupervisor implements RuntimeSupervisor {
  private static final java.util.Set<java.nio.file.attribute.PosixFilePermission>
      PRIVATE_DIRECTORY_PERMISSIONS = PosixFilePermissions.fromString("rwx------");
  private static final java.util.Set<java.nio.file.attribute.PosixFilePermission>
      PRIVATE_FILE_PERMISSIONS = PosixFilePermissions.fromString("rw-------");

  public static final class Settings {
    private final Path executable;
    private final Path entrypoint;
    private final Path configFile;
    private final Path workingDirectory;
    private final Path standardOutputLog;
    private final Path standardErrorLog;
    private final Duration healthPollInterval;
    private final Duration startupTimeout;
    private final Duration stopTimeout;
    private final Map<String, String> environmentAllowlist;

    public Settings(
        Path executable,
        Path entrypoint,
        Path configFile,
        Path workingDirectory,
        Path standardOutputLog,
        Path standardErrorLog,
        Duration healthPollInterval,
        Duration startupTimeout,
        Duration stopTimeout,
        Map<String, String> environmentAllowlist) {
      this.executable = absolute(executable, "executable");
      this.entrypoint = absolute(entrypoint, "entrypoint");
      this.configFile = absolute(configFile, "configFile");
      this.workingDirectory = absolute(workingDirectory, "workingDirectory");
      this.standardOutputLog = absolute(standardOutputLog, "standardOutputLog");
      this.standardErrorLog = absolute(standardErrorLog, "standardErrorLog");
      this.healthPollInterval = positive(healthPollInterval, "healthPollInterval");
      this.startupTimeout = positive(startupTimeout, "startupTimeout");
      this.stopTimeout = nonNegative(stopTimeout, "stopTimeout");
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

    public Duration healthPollInterval() {
      return healthPollInterval;
    }

    public Duration startupTimeout() {
      return startupTimeout;
    }

    public Duration stopTimeout() {
      return stopTimeout;
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

    private static Duration positive(Duration duration, String name) {
      Objects.requireNonNull(duration, name);
      if (duration.isZero() || duration.isNegative()) {
        throw new IllegalArgumentException(name + " must be positive");
      }
      requireNanosecondRange(duration, name);
      return duration;
    }

    private static Duration nonNegative(Duration duration, String name) {
      Objects.requireNonNull(duration, name);
      if (duration.isNegative()) {
        throw new IllegalArgumentException(name + " must not be negative");
      }
      requireNanosecondRange(duration, name);
      return duration;
    }

    private static void requireNanosecondRange(Duration duration, String name) {
      try {
        duration.toNanos();
      } catch (ArithmeticException error) {
        throw new IllegalArgumentException(name + " is too large", error);
      }
    }
  }

  private static final String START_FAILED = "MANAGED_RUNTIME_START_FAILED";
  private static final String EXITED = "MANAGED_RUNTIME_EXITED";
  private static final String START_TIMEOUT = "MANAGED_RUNTIME_START_TIMEOUT";
  private static final String CANCELLED = "MANAGED_RUNTIME_CANCELLED";

  private final Object lock = new Object();
  private final ScheduledExecutorService scheduler;
  private final ProcessFactory processFactory;
  private final HealthProbe healthProbe;
  private final Settings settings;

  private ActiveAttempt current;
  private boolean closed;

  public ManagedRuntimeSupervisor(
      ScheduledExecutorService scheduler,
      ProcessFactory processFactory,
      HealthProbe healthProbe,
      Settings settings) {
    this.scheduler = Objects.requireNonNull(scheduler);
    this.processFactory = Objects.requireNonNull(processFactory);
    this.healthProbe = Objects.requireNonNull(healthProbe);
    this.settings = Objects.requireNonNull(settings);
  }

  @Override
  public RuntimeStartAttempt prepare(RuntimeConnectionSettings connectionSettings) {
    Objects.requireNonNull(connectionSettings);
    synchronized (lock) {
      if (closed) {
        throw new IllegalStateException("Managed runtime supervisor is closed");
      }
      if (current != null && current.reusable()) {
        return current;
      }
      current = null;
      var attempt =
          new ActiveAttempt(
              connectionSettings, System.nanoTime() + settings.startupTimeout().toNanos());
      current = attempt;
      try {
        attempt.startTask = scheduler.schedule(() -> start(attempt), 0L, TimeUnit.NANOSECONDS);
      } catch (RejectedExecutionException error) {
        current = null;
        attempt.stopping = true;
        attempt.ready.completeExceptionally(
            new RuntimeSupervisorException(
                START_FAILED, "Managed runtime could not be scheduled for startup"));
      }
      return attempt;
    }
  }

  @Override
  public void stop() {
    ActiveAttempt attempt;
    synchronized (lock) {
      attempt = current;
    }
    if (attempt != null) {
      cancel(attempt);
    }
  }

  @Override
  public void close() {
    ActiveAttempt attempt;
    synchronized (lock) {
      if (closed) {
        return;
      }
      closed = true;
      attempt = current;
    }
    if (attempt != null) {
      cancel(attempt);
    }
  }

  private void start(ActiveAttempt attempt) {
    synchronized (lock) {
      if (current != attempt || attempt.stopping || closed) {
        return;
      }
    }

    final Process process;
    try {
      process =
          Objects.requireNonNull(processFactory.start(processBuilder(attempt.connectionSettings)));
    } catch (RuntimeSupervisorException failure) {
      clearStartTask(attempt);
      failAndTerminate(attempt, failure.code(), failure.getMessage());
      return;
    } catch (IOException | RuntimeException error) {
      clearStartTask(attempt);
      failAndTerminate(attempt, START_FAILED, "Managed runtime could not be started");
      return;
    }

    var terminate = false;
    var schedulingFailed = false;
    synchronized (lock) {
      attempt.startTask = null;
      if (current != attempt || attempt.stopping || closed) {
        terminate = true;
      } else {
        attempt.process = process;
        try {
          schedulePoll(attempt);
        } catch (RejectedExecutionException error) {
          schedulingFailed = true;
        }
      }
    }
    if (terminate) {
      terminate(process);
    } else if (schedulingFailed) {
      failAndTerminate(attempt, START_FAILED, "Managed runtime readiness could not be checked");
    }
  }

  private void clearStartTask(ActiveAttempt attempt) {
    synchronized (lock) {
      if (current == attempt) {
        attempt.startTask = null;
      }
    }
  }

  private ProcessBuilder processBuilder(RuntimeConnectionSettings connectionSettings)
      throws IOException {
    preparePrivateLog(settings.standardOutputLog());
    preparePrivateLog(settings.standardErrorLog());

    var command =
        List.of(
            settings.executable().toString(),
            settings.entrypoint().toString(),
            "--config",
            settings.configFile().toString(),
            "--managed");
    var builder = new ProcessBuilder(command);
    builder.directory(settings.workingDirectory().toFile());
    builder.environment().clear();
    builder.environment().putAll(settings.environmentAllowlist());
    builder.environment().put("AGMA_MANAGED_SERVER_ID", connectionSettings.serverId());
    builder
        .environment()
        .put(
            "AGMA_MANAGED_RUNTIME_PORT", Integer.toString(connectionSettings.endpoint().getPort()));
    builder.redirectOutput(ProcessBuilder.Redirect.appendTo(settings.standardOutputLog().toFile()));
    builder.redirectError(ProcessBuilder.Redirect.appendTo(settings.standardErrorLog().toFile()));
    return builder;
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

  private void schedulePoll(ActiveAttempt attempt) {
    attempt.poll =
        scheduler.schedule(
            () -> poll(attempt), settings.healthPollInterval().toNanos(), TimeUnit.NANOSECONDS);
  }

  private void poll(ActiveAttempt attempt) {
    Process process;
    synchronized (lock) {
      if (current != attempt || attempt.stopping) {
        return;
      }
      process = attempt.process;
      if (process == null) {
        return;
      }
    }
    if (!process.isAlive()) {
      failAndTerminate(attempt, EXITED, "Managed runtime exited before it became ready");
      return;
    }

    boolean ready = false;
    try {
      ready = healthProbe.isReady(attempt.connectionSettings);
    } catch (Exception ignored) {
      // A transient probe failure remains indistinguishable from not-ready until timeout.
    }
    if (ready) {
      completeReady(attempt);
      return;
    }
    if (System.nanoTime() - attempt.deadlineNanos >= 0) {
      failAndTerminate(attempt, START_TIMEOUT, "Managed runtime did not become ready in time");
      return;
    }

    var schedulingFailed = false;
    synchronized (lock) {
      if (current == attempt && !attempt.stopping) {
        try {
          schedulePoll(attempt);
        } catch (RejectedExecutionException error) {
          schedulingFailed = true;
        }
      }
    }
    if (schedulingFailed) {
      failAndTerminate(attempt, START_FAILED, "Managed runtime readiness could not be checked");
    }
  }

  private void completeReady(ActiveAttempt attempt) {
    synchronized (lock) {
      if (current != attempt || attempt.stopping) {
        return;
      }
      cancelPoll(attempt);
      attempt.ready.complete(null);
    }
  }

  private void cancel(ActiveAttempt attempt) {
    failAndTerminate(attempt, CANCELLED, "Managed runtime startup was cancelled");
  }

  private void failAndTerminate(ActiveAttempt attempt, String code, String message) {
    var failure = new RuntimeSupervisorException(code, message);
    Process process;
    ScheduledFuture<?> startTask;
    synchronized (lock) {
      if (current != attempt || attempt.stopping) {
        return;
      }
      attempt.stopping = true;
      cancelPoll(attempt);
      startTask = attempt.startTask;
      attempt.startTask = null;
      process = attempt.process;
      current = null;
    }
    if (startTask != null) {
      startTask.cancel(true);
    }
    if (process != null) {
      terminate(process);
    }
    attempt.ready.completeExceptionally(failure);
  }

  private void terminate(Process process) {
    if (!process.isAlive()) {
      return;
    }
    process.destroy();
    var interrupted = false;
    try {
      if (!process.waitFor(settings.stopTimeout().toNanos(), TimeUnit.NANOSECONDS)) {
        process.destroyForcibly();
        process.waitFor(settings.stopTimeout().toNanos(), TimeUnit.NANOSECONDS);
      }
    } catch (InterruptedException error) {
      interrupted = true;
      process.destroyForcibly();
      try {
        process.waitFor(settings.stopTimeout().toNanos(), TimeUnit.NANOSECONDS);
      } catch (InterruptedException repeated) {
        interrupted = true;
      }
    } finally {
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }

  private static void cancelPoll(ActiveAttempt attempt) {
    var poll = attempt.poll;
    if (poll != null) {
      poll.cancel(false);
    }
  }

  private final class ActiveAttempt implements RuntimeStartAttempt {
    private final RuntimeConnectionSettings connectionSettings;
    private final long deadlineNanos;
    private final CompletableFuture<Void> ready = new CompletableFuture<>();
    private Process process;
    private ScheduledFuture<?> startTask;
    private ScheduledFuture<?> poll;
    private boolean stopping;

    private ActiveAttempt(RuntimeConnectionSettings connectionSettings, long deadlineNanos) {
      this.connectionSettings = connectionSettings;
      this.deadlineNanos = deadlineNanos;
    }

    private boolean reusable() {
      return !stopping && (process == null || process.isAlive());
    }

    @Override
    public CompletableFuture<Void> ready() {
      return ready;
    }

    @Override
    public void cancel() {
      ManagedRuntimeSupervisor.this.cancel(this);
    }
  }
}
