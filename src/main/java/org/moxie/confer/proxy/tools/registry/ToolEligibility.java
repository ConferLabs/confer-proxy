package org.moxie.confer.proxy.tools.registry;

import org.moxie.confer.proxy.tools.ToolRequirement;

import java.util.Set;

public final class ToolEligibility {

  private final Set<ToolRequirement> satisfiedRequirements;

  public ToolEligibility(Set<ToolRequirement> satisfiedRequirements) {
    this.satisfiedRequirements = Set.copyOf(satisfiedRequirements);
  }

  public boolean satisfies(Set<ToolRequirement> requirements) {
    return satisfiedRequirements.containsAll(requirements);
  }
}
