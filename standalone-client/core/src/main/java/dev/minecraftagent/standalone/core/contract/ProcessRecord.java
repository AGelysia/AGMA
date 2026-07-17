package dev.minecraftagent.standalone.core.contract;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

public record ProcessRecord(
    String processId,
    String categoryId,
    String displayName,
    List<ResourceRef> workstations,
    List<InputGroup> inputs,
    List<Catalyst> catalysts,
    List<Output> outputs,
    Long durationTicks,
    ResourceRef energy,
    List<String> conditions,
    List<Stage> stages,
    ResourceRef.Source source,
    boolean plannable,
    List<String> warnings) {
  public ProcessRecord {
    processId = ContractChecks.namespacedId(processId, "processId");
    categoryId = ContractChecks.namespacedId(categoryId, "categoryId");
    displayName = ContractChecks.text(displayName, "displayName", 256);
    workstations = ContractChecks.list(workstations, "workstations", 32);
    inputs = ContractChecks.list(inputs, "inputs", 128);
    catalysts = ContractChecks.list(catalysts, "catalysts", 64);
    outputs = ContractChecks.nonEmptyList(outputs, "outputs", 64);
    if (outputs.stream().filter(Output::primary).count() != 1) {
      throw new IllegalArgumentException("outputs must contain exactly one primary output");
    }
    if (durationTicks != null && (durationTicks < 0 || durationTicks > 86_400_000L)) {
      throw new IllegalArgumentException("durationTicks is out of range");
    }
    if (energy != null && energy.kind() != ResourceRef.Kind.ENERGY) {
      throw new IllegalArgumentException("energy must reference an energy resource");
    }
    conditions = boundedText(conditions, "conditions", 64, 512);
    stages = ContractChecks.list(stages, "stages", 128);
    Objects.requireNonNull(source, "source");
    warnings = boundedText(warnings, "warnings", 64, 512);

    var generationId = source.generationId();
    workstations.forEach(resource -> requireGeneration(resource, generationId));
    inputs.forEach(
        group ->
            group.alternatives().forEach(resource -> requireGeneration(resource, generationId)));
    catalysts.forEach(
        catalyst -> {
          requireGeneration(catalyst.resource(), generationId);
          if (catalyst.returnedResource() != null) {
            requireGeneration(catalyst.returnedResource(), generationId);
          }
        });
    outputs.forEach(output -> requireGeneration(output.resource(), generationId));
    if (energy != null) {
      requireGeneration(energy, generationId);
    }

    var groupIds = new HashSet<String>();
    for (var input : inputs) {
      if (!groupIds.add(input.groupId())) {
        throw new IllegalArgumentException("input group ids must be unique");
      }
    }
    for (var index = 0; index < stages.size(); index++) {
      var stage = stages.get(index);
      if (stage.index() != index) {
        throw new IllegalArgumentException("stage indexes must be contiguous and ordered");
      }
      if (!groupIds.containsAll(stage.inputGroupIds())) {
        throw new IllegalArgumentException("stages may only reference declared input groups");
      }
    }
  }

  private static List<String> boundedText(
      List<String> values, String field, int maximumSize, int maximumLength) {
    return ContractChecks.list(values, field, maximumSize).stream()
        .map(value -> ContractChecks.text(value, field, maximumLength))
        .toList();
  }

  private static void requireGeneration(ResourceRef resource, String generationId) {
    if (!generationId.equals(resource.source().generationId())) {
      throw new IllegalArgumentException("process resources must use one snapshot generation");
    }
  }

  public record InputGroup(String groupId, List<ResourceRef> alternatives) {
    public InputGroup {
      groupId = ContractChecks.symbolicId(groupId, "groupId");
      alternatives = ContractChecks.nonEmptyList(alternatives, "alternatives", 64);
    }
  }

  public record Catalyst(ResourceRef resource, boolean consumed, ResourceRef returnedResource) {
    public Catalyst {
      Objects.requireNonNull(resource, "resource");
      if (!consumed && returnedResource != null) {
        throw new IllegalArgumentException("a reusable catalyst does not need a returned resource");
      }
    }
  }

  public record Output(ResourceRef resource, BigDecimal probability, boolean primary) {
    public Output {
      Objects.requireNonNull(resource, "resource");
      probability = ContractChecks.probability(probability, "probability");
      if (primary && probability.compareTo(BigDecimal.ONE) != 0) {
        throw new IllegalArgumentException("the primary output must be deterministic");
      }
    }
  }

  public record Stage(int index, String description, List<String> inputGroupIds) {
    public Stage {
      if (index < 0 || index > 127) {
        throw new IllegalArgumentException("stage index is out of range");
      }
      description = ContractChecks.text(description, "description", 512);
      inputGroupIds =
          ContractChecks.list(inputGroupIds, "inputGroupIds", 128).stream()
              .map(value -> ContractChecks.symbolicId(value, "inputGroupId"))
              .toList();
      if (new HashSet<>(inputGroupIds).size() != inputGroupIds.size()) {
        throw new IllegalArgumentException("stage inputGroupIds must be unique");
      }
    }
  }
}
