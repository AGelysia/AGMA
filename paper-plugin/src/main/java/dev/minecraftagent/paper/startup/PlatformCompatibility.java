package dev.minecraftagent.paper.startup;

public final class PlatformCompatibility {
  public static final String SUPPORTED_MINECRAFT_VERSION = "1.21.11";

  public void check(int javaFeature, String minecraftVersion) throws StartupFailure {
    if (javaFeature < 21) {
      throw new StartupFailure(
          StartupFailure.Code.JAVA_VERSION_UNSUPPORTED, StartupFailure.Stage.ENVIRONMENT);
    }
    if (!SUPPORTED_MINECRAFT_VERSION.equals(minecraftVersion)) {
      throw new StartupFailure(
          StartupFailure.Code.PAPER_VERSION_UNSUPPORTED, StartupFailure.Stage.ENVIRONMENT);
    }
  }
}
