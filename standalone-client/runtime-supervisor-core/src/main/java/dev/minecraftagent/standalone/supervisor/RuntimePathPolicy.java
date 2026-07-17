package dev.minecraftagent.standalone.supervisor;

/**
 * Platform boundary for canonical paths, ownership, permissions, links, locks, and executable
 * policy. Implementations must return a fully prepared immutable launch specification.
 */
@FunctionalInterface
public interface RuntimePathPolicy {
  RuntimeLaunchSpec prepare(RuntimeLaunchSpec requested) throws SupervisorException;

  /** Only for an external development Runtime whose paths were verified by the caller. */
  static RuntimePathPolicy externallyVerifiedDevelopmentPaths() {
    return requested -> requested;
  }
}
