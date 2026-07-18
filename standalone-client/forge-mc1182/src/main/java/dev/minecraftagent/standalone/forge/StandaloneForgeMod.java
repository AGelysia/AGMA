package dev.minecraftagent.standalone.forge;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;

/** Distribution-safe Forge bootstrap for the client-only standalone lifecycle. */
@Mod(StandaloneForgeMod.MOD_ID)
public final class StandaloneForgeMod {
  public static final String MOD_ID = "agma_standalone";

  public StandaloneForgeMod() {
    // Forge 40.2.x validates safe lambdas in dev mode before checking the side;
    // that validation resolves the client-only implementation on a dedicated server.
    DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> StandaloneForgeClient::bootstrap);
  }
}
