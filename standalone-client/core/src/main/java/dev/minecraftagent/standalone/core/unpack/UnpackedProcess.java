package dev.minecraftagent.standalone.core.unpack;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * A recipe mapped into a catalog-neutral candidate form. This is deliberately not a contract
 * process record yet: ids are unresolved raw strings and amounts are plain decimals so the mapper
 * stays free of catalog concerns. The catalog mapper turns the candidates into contract records.
 */
public record UnpackedProcess(
    String processId,
    String categoryId,
    List<Group> inputs,
    List<Output> outputs,
    String catalystId,
    Long durationTicks,
    List<String> conditions,
    List<String> warnings,
    String workstationId,
    boolean plannable) {
  public UnpackedProcess {
    Objects.requireNonNull(processId, "processId");
    Objects.requireNonNull(categoryId, "categoryId");
    inputs = List.copyOf(Objects.requireNonNull(inputs, "inputs"));
    outputs = List.copyOf(Objects.requireNonNull(outputs, "outputs"));
    conditions = List.copyOf(Objects.requireNonNull(conditions, "conditions"));
    warnings = List.copyOf(Objects.requireNonNull(warnings, "warnings"));
  }

  /** One input group. {@code slot} is an optional source slot name used for stable group ids. */
  public record Group(String slot, List<Amount> alternatives) {
    public Group {
      alternatives = List.copyOf(Objects.requireNonNull(alternatives, "alternatives"));
    }
  }

  /** One item or fluid alternative with its required amount. */
  public record Amount(UnpackedTag.Kind kind, String id, BigDecimal amount) {
    public Amount {
      Objects.requireNonNull(kind, "kind");
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(amount, "amount");
    }
  }

  /** One process output. The first output of a process is its primary output. */
  public record Output(
      UnpackedTag.Kind kind, String id, BigDecimal amount, double chance, boolean primary) {
    public Output {
      Objects.requireNonNull(kind, "kind");
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(amount, "amount");
    }
  }
}
