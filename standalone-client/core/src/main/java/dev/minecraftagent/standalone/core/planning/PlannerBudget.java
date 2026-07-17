package dev.minecraftagent.standalone.core.planning;

public record PlannerBudget(
    int nodeMaximum, int depthMaximum, int topK, long wallClockMilliseconds) {
  public static final PlannerBudget DEFAULT = new PlannerBudget(2_000, 12, 3, 500);

  public PlannerBudget {
    if (nodeMaximum < 1 || nodeMaximum > 100_000) {
      throw new IllegalArgumentException("nodeMaximum is out of range");
    }
    if (depthMaximum < 1 || depthMaximum > 128) {
      throw new IllegalArgumentException("depthMaximum is out of range");
    }
    if (topK < 1 || topK > 3) {
      throw new IllegalArgumentException("topK is out of range");
    }
    if (wallClockMilliseconds < 1 || wallClockMilliseconds > 60_000) {
      throw new IllegalArgumentException("wallClockMilliseconds is out of range");
    }
  }
}
