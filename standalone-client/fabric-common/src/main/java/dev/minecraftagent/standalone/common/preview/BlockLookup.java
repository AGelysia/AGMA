package dev.minecraftagent.standalone.common.preview;

/** Caller-supplied current-state lookup; a null result means the chunk is unavailable. */
@FunctionalInterface
public interface BlockLookup {
  String stateAt(int x, int y, int z);
}
