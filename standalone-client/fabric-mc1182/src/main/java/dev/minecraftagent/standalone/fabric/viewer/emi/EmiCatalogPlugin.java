package dev.minecraftagent.standalone.fabric.viewer.emi;

import dev.emi.emi.api.EmiApi;
import dev.emi.emi.api.EmiPlugin;
import dev.emi.emi.api.EmiRegistry;
import dev.minecraftagent.standalone.core.adapter.CatalogAdapter;
import dev.minecraftagent.standalone.core.catalog.CatalogPublisher;
import dev.minecraftagent.standalone.fabric.OptionalViewerRegistry;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.Registry;

/**
 * EMI 0.7.3 exposes registration but no public recipe-manager enumeration API. Keep the adapter
 * visible as an explicit fail-closed diagnostic and fall back to JEI or the vanilla client.
 */
public final class EmiCatalogPlugin implements EmiPlugin {
  @Override
  public void register(EmiRegistry registry) {
    OptionalViewerRegistry.register(new UnsupportedEmiAdapter());
  }

  private static final class UnsupportedEmiAdapter implements CatalogAdapter {
    @Override
    public Descriptor descriptor() {
      return new Descriptor("emi", Kind.EMI, 100);
    }

    @Override
    public Probe probe() {
      var installed =
          net.fabricmc.loader.api.FabricLoader.getInstance()
              .getModContainer("emi")
              .map(container -> container.getMetadata().getVersion().getFriendlyString())
              .orElse(null);
      return new Probe(Status.INCOMPATIBLE, installed, false, "EMI_RECIPE_ENUMERATION_UNAVAILABLE");
    }

    @Override
    public Optional<String> hoveredItemId() {
      var interaction = EmiApi.getHoveredStack(true);
      if (interaction == null || interaction.isEmpty()) {
        return Optional.empty();
      }
      return interaction.getStack().getEmiStacks().stream()
          .map(stack -> stack.getItemStack())
          .filter(stack -> stack != null && !stack.isEmpty())
          .map(stack -> Registry.ITEM.getKey(stack.getItem()))
          .filter(java.util.Objects::nonNull)
          .map(Object::toString)
          .findFirst();
    }

    @Override
    public Contribution capture(
        String generationId,
        CatalogPublisher.Cancellation cancellation,
        CatalogPublisher.ProgressListener progress) {
      return new Contribution(
          "emi", generationId, List.of(), List.of(), List.of("EMI_RECIPE_ENUMERATION_UNAVAILABLE"));
    }
  }
}
