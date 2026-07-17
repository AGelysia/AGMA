package dev.minecraftagent.paper.proposal;

import java.util.Optional;

/** Resolves explicit typed write-tool bindings; it is not a generic command registry. */
@FunctionalInterface
public interface ProposalToolCatalog {
  Optional<TypedProposalTool<?>> find(String toolId);
}
