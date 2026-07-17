package dev.minecraftagent.standalone.core.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.minecraftagent.standalone.core.catalog.CatalogPublisher;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ViewerSourceSelectorTest {
  private final ViewerSourceSelector selector = new ViewerSourceSelector();

  @Test
  void reviewedJeiWinsWithoutMergingWithEmi() {
    var vanilla = adapter("vanilla", CatalogAdapter.Kind.VANILLA, 0, ready("1.21.11"));
    var jei = adapter("jei", CatalogAdapter.Kind.JEI, 200, ready("27.17.0.50"));
    var emi = adapter("emi", CatalogAdapter.Kind.EMI, 100, ready("fixture-1.21.11"));

    var selected = selector.select(List.of(vanilla, emi, jei));
    assertEquals("jei", selected.selected().descriptor().id());
    assertEquals(3, selected.states().size());
  }

  @Test
  void unavailableOrBrokenViewersFallBackToVanilla() {
    var vanilla = adapter("vanilla", CatalogAdapter.Kind.VANILLA, 0, ready("1.18.2"));
    var jei =
        adapter(
            "jei",
            CatalogAdapter.Kind.JEI,
            200,
            new CatalogAdapter.Probe(
                CatalogAdapter.Status.NOT_INSTALLED, null, false, "JEI_NOT_INSTALLED"));
    CatalogAdapter broken =
        new FakeAdapter("emi", CatalogAdapter.Kind.EMI, 100, null) {
          @Override
          public CatalogAdapter.Probe probe() {
            throw new NoClassDefFoundError("fixture optional API");
          }
        };

    var selected = selector.select(List.of(jei, broken, vanilla));
    assertEquals("vanilla", selected.selected().descriptor().id());
    assertEquals(
        CatalogAdapter.Status.FAILED,
        selected.states().stream()
            .filter(state -> state.adapter().descriptor().id().equals("emi"))
            .findFirst()
            .orElseThrow()
            .probe()
            .status());
  }

  @Test
  void duplicateAdaptersAndMissingFallbackFailClosed() {
    var first = adapter("jei", CatalogAdapter.Kind.JEI, 200, ready("27.17.0.50"));
    var duplicate = adapter("jei", CatalogAdapter.Kind.JEI, 100, ready("27.17.0.50"));
    assertThrows(IllegalArgumentException.class, () -> selector.select(List.of(first, duplicate)));

    var unavailable =
        adapter(
            "jei",
            CatalogAdapter.Kind.JEI,
            200,
            new CatalogAdapter.Probe(
                CatalogAdapter.Status.VERSION_UNAVAILABLE,
                "1.1.24+1.21.1",
                false,
                "JEI_VERSION_UNAVAILABLE"));
    assertThrows(IllegalStateException.class, () -> selector.select(List.of(unavailable)));
  }

  private static CatalogAdapter adapter(
      String id, CatalogAdapter.Kind kind, int priority, CatalogAdapter.Probe probe) {
    return new FakeAdapter(id, kind, priority, probe);
  }

  private static CatalogAdapter.Probe ready(String version) {
    return new CatalogAdapter.Probe(CatalogAdapter.Status.READY, version, true, "ADAPTER_READY");
  }

  private static class FakeAdapter implements CatalogAdapter {
    private final Descriptor descriptor;
    private final Probe probe;

    private FakeAdapter(String id, Kind kind, int priority, Probe probe) {
      this.descriptor = new Descriptor(id, kind, priority);
      this.probe = probe;
    }

    @Override
    public Descriptor descriptor() {
      return descriptor;
    }

    @Override
    public Probe probe() {
      return probe;
    }

    @Override
    public Contribution capture(
        String generationId,
        CatalogPublisher.Cancellation cancellation,
        CatalogPublisher.ProgressListener progress) {
      return new Contribution(descriptor.id(), generationId, List.of(), List.of(), List.of());
    }
  }
}
