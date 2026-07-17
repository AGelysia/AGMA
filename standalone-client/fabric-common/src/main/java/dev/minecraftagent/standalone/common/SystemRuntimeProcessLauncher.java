package dev.minecraftagent.standalone.common;

import dev.minecraftagent.standalone.supervisor.OwnedRuntimeProcess;
import dev.minecraftagent.standalone.supervisor.RuntimeLaunchSpec;
import dev.minecraftagent.standalone.supervisor.RuntimeProcessLauncher;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionStage;

/** Starts exactly one verified managed Runtime and retains its concrete Process handle. */
public final class SystemRuntimeProcessLauncher implements RuntimeProcessLauncher {
  private static final Set<String> INHERITED_ENVIRONMENT =
      Set.of("SYSTEMROOT", "WINDIR", "TEMP", "TMP", "LANG", "LC_ALL", "TZ");

  @Override
  public OwnedRuntimeProcess start(RuntimeLaunchSpec spec) throws IOException {
    var command = new ArrayList<String>(spec.arguments().size() + 1);
    command.add(spec.executable().toString());
    command.addAll(spec.arguments());
    var logs = PrivateFilePermissions.prepareChildDirectory(spec.stateRoot(), "logs");
    var processLog = logs.resolve("runtime-process.log");
    PrivateFilePermissions.atomicWrite(spec.stateRoot(), processLog, new byte[0]);

    var builder = new ProcessBuilder(command);
    builder.directory(spec.stateRoot().toFile());
    builder.redirectErrorStream(true);
    builder.redirectOutput(processLog.toFile());
    var inherited = Map.copyOf(builder.environment());
    builder.environment().clear();
    for (var name : INHERITED_ENVIRONMENT) {
      var value = inherited.get(name);
      if (value != null && !value.isEmpty() && value.indexOf('\0') < 0) {
        builder.environment().put(name, value);
      }
    }
    builder.environment().putAll(spec.environment());
    var process = builder.start();
    return new ProcessOwnedRuntime(process);
  }

  private static final class ProcessOwnedRuntime implements OwnedRuntimeProcess {
    private final Process process;
    private final OutputStream input;

    private ProcessOwnedRuntime(Process process) {
      this.process = process;
      this.input = process.getOutputStream();
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
      return process.onExit().thenApply(Process::exitValue);
    }

    @Override
    public void requestStop() {
      try {
        input.close();
      } catch (IOException ignored) {
        // The supervisor verifies exit and force-stops this exact process on timeout.
      }
    }

    @Override
    public void forceStop() {
      try {
        input.close();
      } catch (IOException ignored) {
        // The concrete Process handle remains authoritative.
      }
      process.destroyForcibly();
    }
  }
}
