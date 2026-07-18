package dev.minecraftagent.standalone.forge;

import dev.minecraftagent.standalone.common.ClientRuntimeView;
import dev.minecraftagent.standalone.common.ConnectorSessionState;
import dev.minecraftagent.standalone.supervisor.SupervisorState;
import java.util.Objects;

/** Pure presentation mapping for the Forge Runtime status band. */
record RuntimePanelStatus(State state, Action action) {
  RuntimePanelStatus {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(action, "action");
  }

  static RuntimePanelStatus from(ClientRuntimeView view, boolean statusUnavailable) {
    Objects.requireNonNull(view, "view");
    var lifecycle = view.profile().state();
    var state =
        switch (lifecycle) {
          case UNCONFIGURED -> State.UNCONFIGURED;
          case STOPPED -> State.STOPPED;
          case STOPPING -> State.STOPPING;
          case ERROR -> State.ERROR;
          case STARTING -> startingState(view);
          case READY -> readyState(view, statusUnavailable);
        };
    var action =
        switch (lifecycle) {
          case UNCONFIGURED -> Action.CONFIGURE;
          case STOPPED -> Action.START;
          case STARTING -> Action.CANCEL_START;
          case READY -> state == State.ERROR ? Action.RESTART : Action.STOP;
          case STOPPING -> Action.WAIT;
          case ERROR -> Action.RETRY;
        };
    return new RuntimePanelStatus(state, action);
  }

  private static State startingState(ClientRuntimeView view) {
    var supervisor = view.supervisor();
    if (supervisor == null || supervisor.state() == SupervisorState.STARTING) {
      return State.STARTING;
    }
    if (supervisor.state() == SupervisorState.ERROR) {
      return State.RECOVERING;
    }
    if (supervisor.state() == SupervisorState.READY
        && view.connectorState() != ConnectorSessionState.AUTHENTICATED) {
      return State.CONNECTING;
    }
    return State.STARTING;
  }

  private static State readyState(ClientRuntimeView view, boolean statusUnavailable) {
    var supervisor = view.supervisor();
    if (supervisor == null) {
      return State.ERROR;
    }
    if (supervisor.state() == SupervisorState.STARTING) {
      return State.RECOVERING;
    }
    if (supervisor.state() != SupervisorState.READY
        || view.connectorState() != ConnectorSessionState.AUTHENTICATED
        || statusUnavailable) {
      return State.ERROR;
    }
    return State.RUNNING;
  }

  enum State {
    UNCONFIGURED("runtime_unconfigured", 0xFF8B949E),
    STOPPED("runtime_stopped_state", 0xFF8B949E),
    STARTING("runtime_starting_state", 0xFFE5B567),
    CONNECTING("runtime_connecting_state", 0xFFE5B567),
    RUNNING("runtime_running_state", 0xFF56C596),
    RECOVERING("runtime_recovering_state", 0xFFE5B567),
    STOPPING("runtime_stopping_state", 0xFFE5B567),
    ERROR("runtime_error_state", 0xFFE06C75);

    private final String suffix;
    private final int color;

    State(String suffix, int color) {
      this.suffix = suffix;
      this.color = color;
    }

    String titleKey() {
      return "screen.agma_standalone." + suffix;
    }

    String detailKey() {
      return "screen.agma_standalone." + suffix + "_detail";
    }

    int color() {
      return color;
    }
  }

  enum Action {
    CONFIGURE("runtime_configure"),
    START("start_runtime"),
    CANCEL_START("runtime_cancel_start"),
    STOP("stop_runtime"),
    RESTART("runtime_restart"),
    WAIT("runtime_wait"),
    RETRY("runtime_retry");

    private final String suffix;

    Action(String suffix) {
      this.suffix = suffix;
    }

    String labelKey() {
      return "screen.agma_standalone." + suffix;
    }
  }
}
