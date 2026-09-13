package dev.minecraftagent.standalone.common.preview;

/** Change counts of a preview: air-to-block, block-to-different-block, and block-to-air cells. */
public record PreviewDifference(int added, int replaced, int removed) {
  public PreviewDifference {
    if (added < 0 || replaced < 0 || removed < 0) {
      throw new IllegalArgumentException("preview difference counts are invalid");
    }
  }

  public int total() {
    return added + replaced + removed;
  }
}
