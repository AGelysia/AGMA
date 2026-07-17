package dev.minecraftagent.paper.proposal;

/** Security classification for a proposed operation. */
public enum RiskLevel {
  READ,
  WRITE_TEMPORARY,
  WRITE_WORLD,
  WRITE_PLAYER,
  SERVER_ADMIN;

  public boolean requiresProposal() {
    return this != READ;
  }
}
