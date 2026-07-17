package dev.minecraftagent.paper.request;

import dev.minecraftagent.paper.transport.AuthenticatedRuntimeConnection;

public interface RuntimeApplicationLifecycle {
  void attach(AuthenticatedRuntimeConnection connection, String serverId);

  void detach(AuthenticatedRuntimeConnection connection);

  static RuntimeApplicationLifecycle empty() {
    return new RuntimeApplicationLifecycle() {
      @Override
      public void attach(AuthenticatedRuntimeConnection connection, String serverId) {}

      @Override
      public void detach(AuthenticatedRuntimeConnection connection) {}
    };
  }
}
