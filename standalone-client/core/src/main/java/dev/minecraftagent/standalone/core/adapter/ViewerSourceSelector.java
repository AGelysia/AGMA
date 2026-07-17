package dev.minecraftagent.standalone.core.adapter;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

public final class ViewerSourceSelector {
  public Selection select(List<CatalogAdapter> adapters) {
    Objects.requireNonNull(adapters, "adapters");
    if (adapters.size() > 8 || adapters.stream().anyMatch(Objects::isNull)) {
      throw new IllegalArgumentException("adapter inventory is invalid");
    }
    var ids = new HashSet<String>();
    for (var adapter : adapters) {
      if (!ids.add(adapter.descriptor().id())) {
        throw new IllegalArgumentException("adapter ids must be unique");
      }
    }

    var states =
        adapters.stream()
            .map(adapter -> new AdapterState(adapter, safeProbe(adapter)))
            .sorted(
                Comparator.comparingInt(
                        (AdapterState state) -> state.adapter().descriptor().priority())
                    .reversed()
                    .thenComparing(state -> state.adapter().descriptor().id()))
            .toList();
    var selected =
        states.stream()
            .filter(state -> state.probe().status() == CatalogAdapter.Status.READY)
            .map(AdapterState::adapter)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("a ready vanilla fallback is required"));
    return new Selection(selected, states);
  }

  private static CatalogAdapter.Probe safeProbe(CatalogAdapter adapter) {
    try {
      var probe = adapter.probe();
      return probe == null ? failed("ADAPTER_PROBE_NULL") : probe;
    } catch (RuntimeException | LinkageError error) {
      return failed("ADAPTER_PROBE_FAILED");
    }
  }

  private static CatalogAdapter.Probe failed(String code) {
    return new CatalogAdapter.Probe(CatalogAdapter.Status.FAILED, null, false, code);
  }

  public record AdapterState(CatalogAdapter adapter, CatalogAdapter.Probe probe) {
    public AdapterState {
      Objects.requireNonNull(adapter, "adapter");
      Objects.requireNonNull(probe, "probe");
    }
  }

  public record Selection(CatalogAdapter selected, List<AdapterState> states) {
    public Selection {
      Objects.requireNonNull(selected, "selected");
      states = List.copyOf(states);
      if (states.stream()
          .noneMatch(
              state ->
                  state.adapter() == selected
                      && state.probe().status() == CatalogAdapter.Status.READY)) {
        throw new IllegalArgumentException("selected adapter must be a ready inventory member");
      }
    }
  }
}
