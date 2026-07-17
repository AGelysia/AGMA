package dev.minecraftagent.standalone.supervisor;

import java.time.Duration;
import java.util.Objects;

/** Bounded lifecycle timing and automatic restart policy. */
public record SupervisorPolicy(
    Duration startTimeout,
    Duration healthPollInterval,
    Duration stopTimeout,
    Duration initialRestartBackoff,
    Duration maximumRestartBackoff,
    int maximumRestartAttempts) {
  public SupervisorPolicy {
    startTimeout = positive(startTimeout, "startTimeout");
    healthPollInterval = positive(healthPollInterval, "healthPollInterval");
    stopTimeout = positive(stopTimeout, "stopTimeout");
    initialRestartBackoff = positive(initialRestartBackoff, "initialRestartBackoff");
    maximumRestartBackoff = positive(maximumRestartBackoff, "maximumRestartBackoff");
    if (maximumRestartBackoff.compareTo(initialRestartBackoff) < 0) {
      throw new IllegalArgumentException("Maximum restart backoff is below the initial backoff");
    }
    if (maximumRestartAttempts < 0 || maximumRestartAttempts > 16) {
      throw new IllegalArgumentException("Maximum restart attempts are out of bounds");
    }
  }

  public static SupervisorPolicy defaults() {
    return new SupervisorPolicy(
        Duration.ofSeconds(30),
        Duration.ofMillis(250),
        Duration.ofSeconds(5),
        Duration.ofSeconds(1),
        Duration.ofSeconds(30),
        3);
  }

  Duration restartDelay(int attempt) {
    if (attempt < 1) {
      throw new IllegalArgumentException("Restart attempt must be positive");
    }
    var delay = initialRestartBackoff;
    for (var index = 1; index < attempt && delay.compareTo(maximumRestartBackoff) < 0; index++) {
      try {
        delay = delay.multipliedBy(2);
      } catch (ArithmeticException ignored) {
        return maximumRestartBackoff;
      }
      if (delay.compareTo(maximumRestartBackoff) > 0) {
        return maximumRestartBackoff;
      }
    }
    return delay;
  }

  private static Duration positive(Duration value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isZero() || value.isNegative()) {
      throw new IllegalArgumentException(name + " must be positive");
    }
    return value;
  }
}
