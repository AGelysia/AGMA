package dev.minecraftagent.paper.startup;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

public final class LocalStartupChecks {
  public record Request(
      Path configPath,
      Path dataDirectory,
      Map<String, String> environment,
      int javaFeature,
      String minecraftVersion) {
    public Request {
      Objects.requireNonNull(configPath);
      Objects.requireNonNull(dataDirectory);
      environment = Map.copyOf(environment);
      Objects.requireNonNull(minecraftVersion);
    }
  }

  private final PaperConfigLoader configLoader;
  private final PlatformCompatibility compatibility;
  private final StateDirectoryProbe stateDirectoryProbe;
  private final OptionalCapabilityCheck optionalCapabilityCheck;

  public LocalStartupChecks() {
    this(
        new PaperConfigLoader(),
        new PlatformCompatibility(),
        new StateDirectoryProbe(),
        new OptionalCapabilityCheck());
  }

  LocalStartupChecks(
      PaperConfigLoader configLoader,
      PlatformCompatibility compatibility,
      StateDirectoryProbe stateDirectoryProbe,
      OptionalCapabilityCheck optionalCapabilityCheck) {
    this.configLoader = Objects.requireNonNull(configLoader);
    this.compatibility = Objects.requireNonNull(compatibility);
    this.stateDirectoryProbe = Objects.requireNonNull(stateDirectoryProbe);
    this.optionalCapabilityCheck = Objects.requireNonNull(optionalCapabilityCheck);
  }

  public LocalStartupResult run(Request request) throws StartupFailure {
    Objects.requireNonNull(request);
    compatibility.check(request.javaFeature(), request.minecraftVersion());
    var config =
        configLoader.load(request.configPath(), request.dataDirectory(), request.environment());
    config.securityPolicy().validate();
    stateDirectoryProbe.verify(request.dataDirectory(), config.stateDirectory());
    var coreTools = CoreToolRuntime.initializeDefaults();
    var warnings =
        optionalCapabilityCheck.inspect(
            request.dataDirectory(), config.optionalCapabilityDirectory());
    var health = warnings.isEmpty() ? StartupHealth.READY : StartupHealth.DEGRADED;
    return new LocalStartupResult(health, config, coreTools, warnings);
  }
}
