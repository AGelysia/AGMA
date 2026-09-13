package dev.minecraftagent.standalone.fabric.preview;

import dev.minecraftagent.standalone.common.ClientToolCall;
import dev.minecraftagent.standalone.common.ClientToolCancellation;
import dev.minecraftagent.standalone.common.ClientToolHandler;
import dev.minecraftagent.standalone.common.ClientToolOutcome;
import dev.minecraftagent.standalone.fabric.PlayerContextToolExecutor;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/**
 * Routes build preview calls to the preview executor, the live player context read to its own
 * executor, and everything else to the catalog tools.
 */
public final class StandaloneToolRouter implements ClientToolHandler {
  private final BuildPreviewToolExecutor previewExecutor;
  private final PlayerContextToolExecutor playerContextExecutor;
  private final ClientToolHandler catalogExecutor;

  public StandaloneToolRouter(
      BuildPreviewToolExecutor previewExecutor,
      PlayerContextToolExecutor playerContextExecutor,
      ClientToolHandler catalogExecutor) {
    this.previewExecutor = Objects.requireNonNull(previewExecutor, "previewExecutor");
    this.playerContextExecutor =
        Objects.requireNonNull(playerContextExecutor, "playerContextExecutor");
    this.catalogExecutor = Objects.requireNonNull(catalogExecutor, "catalogExecutor");
  }

  @Override
  public CompletionStage<? extends ClientToolOutcome> execute(ClientToolCall call) {
    Objects.requireNonNull(call, "call");
    var handler =
        BuildPreviewToolExecutor.TOOL.equals(call.tool())
            ? previewExecutor
            : PlayerContextToolExecutor.TOOL.equals(call.tool())
                ? playerContextExecutor
                : catalogExecutor;
    return handler.execute(call);
  }

  @Override
  public void cancel(ClientToolCancellation cancellation) {
    Objects.requireNonNull(cancellation, "cancellation");
    previewExecutor.cancel(cancellation);
    playerContextExecutor.cancel(cancellation);
    catalogExecutor.cancel(cancellation);
  }
}
