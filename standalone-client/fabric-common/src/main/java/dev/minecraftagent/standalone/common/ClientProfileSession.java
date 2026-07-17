package dev.minecraftagent.standalone.common;

import dev.minecraftagent.standalone.core.contract.RuntimeClientProfile;
import java.util.Objects;
import java.util.Set;

/** Thread-safe owner of the configured client profile and its UI-safe lifecycle state. */
public final class ClientProfileSession {
  private RuntimeClientProfile profile;
  private ClientLifecycleState state = ClientLifecycleState.UNCONFIGURED;
  private String failureCode;
  private long revision;

  public synchronized ClientProfileSnapshot configure(RuntimeClientProfile next) {
    Objects.requireNonNull(next, "next");
    if (Set.of(
            ClientLifecycleState.STARTING,
            ClientLifecycleState.READY,
            ClientLifecycleState.STOPPING)
        .contains(state)) {
      throw new IllegalStateException("Active standalone profile cannot be replaced");
    }
    profile = next;
    revision++;
    state = ClientLifecycleState.STOPPED;
    failureCode = null;
    return snapshot();
  }

  public synchronized ClientProfileSnapshot transition(ClientLifecycleState next) {
    Objects.requireNonNull(next, "next");
    requireConfigured();
    if (!allowed(state, next) || next == ClientLifecycleState.ERROR) {
      throw new IllegalStateException("Invalid standalone lifecycle transition");
    }
    state = next;
    failureCode = null;
    return snapshot();
  }

  public synchronized ClientProfileSnapshot fail(String code) {
    requireConfigured();
    Objects.requireNonNull(code, "code");
    if (!code.matches("[A-Z][A-Z0-9_]{2,63}")) {
      throw new IllegalArgumentException("Client failure code is invalid");
    }
    if (state == ClientLifecycleState.UNCONFIGURED) {
      throw new IllegalStateException("Unconfigured client cannot fail a profile");
    }
    state = ClientLifecycleState.ERROR;
    failureCode = code;
    return snapshot();
  }

  public synchronized ClientProfileSnapshot shutdown() {
    if (profile == null) {
      return snapshot();
    }
    state = ClientLifecycleState.STOPPED;
    failureCode = null;
    return snapshot();
  }

  public synchronized ClientProfileSnapshot clear() {
    if (Set.of(
            ClientLifecycleState.STARTING,
            ClientLifecycleState.READY,
            ClientLifecycleState.STOPPING)
        .contains(state)) {
      throw new IllegalStateException("Active standalone profile cannot be cleared");
    }
    profile = null;
    revision++;
    state = ClientLifecycleState.UNCONFIGURED;
    failureCode = null;
    return snapshot();
  }

  public synchronized ClientProfileSnapshot snapshot() {
    if (profile == null) {
      return new ClientProfileSnapshot(state, revision, null, null, null, null);
    }
    return new ClientProfileSnapshot(
        state,
        revision,
        profile.identity().installationId(),
        profile.model().provider(),
        profile.model().model(),
        failureCode);
  }

  public synchronized RuntimeClientProfile configuredProfile() {
    requireConfigured();
    return profile;
  }

  private void requireConfigured() {
    if (profile == null) {
      throw new IllegalStateException("Standalone client profile is not configured");
    }
  }

  private static boolean allowed(ClientLifecycleState current, ClientLifecycleState next) {
    return switch (current) {
      case STOPPED -> next == ClientLifecycleState.STARTING || next == ClientLifecycleState.STOPPED;
      case STARTING ->
          next == ClientLifecycleState.READY
              || next == ClientLifecycleState.STOPPING
              || next == ClientLifecycleState.STOPPED;
      case READY -> next == ClientLifecycleState.STOPPING || next == ClientLifecycleState.READY;
      case STOPPING -> next == ClientLifecycleState.STOPPED;
      case ERROR -> next == ClientLifecycleState.STARTING || next == ClientLifecycleState.STOPPED;
      case UNCONFIGURED -> false;
    };
  }
}
