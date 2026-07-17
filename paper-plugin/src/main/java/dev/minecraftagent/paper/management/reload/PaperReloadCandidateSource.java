package dev.minecraftagent.paper.management.reload;

import dev.minecraftagent.paper.startup.PaperConfigLoader;
import dev.minecraftagent.paper.startup.StartupFailure;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/** Side-effect-free adapter from the strict Paper config loader to a reload candidate. */
public final class PaperReloadCandidateSource implements ReloadManager.CandidateSource {
  private final PaperConfigLoader loader;
  private final Path configFile;
  private final Path dataDirectory;
  private final Map<String, String> environment;

  public PaperReloadCandidateSource(
      Path configFile, Path dataDirectory, Map<String, String> environment) {
    loader = new PaperConfigLoader();
    this.configFile = Objects.requireNonNull(configFile).toAbsolutePath().normalize();
    this.dataDirectory = Objects.requireNonNull(dataDirectory).toAbsolutePath().normalize();
    this.environment = Map.copyOf(environment);
  }

  @Override
  public ReloadCandidate load() throws StartupFailure {
    return ReloadCandidate.from(loader.load(configFile, dataDirectory, environment));
  }
}
