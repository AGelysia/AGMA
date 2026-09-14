package dev.minecraftagent.standalone.ui.preview;

import dev.minecraftagent.standalone.common.ClientToolCall;
import dev.minecraftagent.standalone.common.ClientToolCancellation;
import dev.minecraftagent.standalone.common.ClientToolHandler;
import dev.minecraftagent.standalone.common.ClientToolOutcome;
import dev.minecraftagent.standalone.ui.BlockInspectToolExecutor;
import dev.minecraftagent.standalone.ui.PlayerContextToolExecutor;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/**
 * Routes build preview calls to the preview executor, the live player context read and block
 * inspection to their own executors, and everything else to the catalog tools.
 */
public final class StandaloneToolRouter implements ClientToolHandler {
  private final BuildPreviewToolExecutor previewExecutor;
  private final PlayerContextToolExecutor playerContextExecutor;
  private final BlockInspectToolExecutor blockInspectExecutor;
  private final ClientToolHandler catalogExecutor;

  public StandaloneToolRouter(
      BuildPreviewToolExecutor previewExecutor,
      PlayerContextToolExecutor playerContextExecutor,
      BlockInspectToolExecutor blockInspectExecutor,
      ClientToolHandler catalogExecutor) {
    this.previewExecutor = Objects.requireNonNull(previewExecutor, "previewExecutor");
    this.playerContextExecutor =
        Objects.requireNonNull(playerContextExecutor, "playerContextExecutor");
    this.blockInspectExecutor =
        Objects.requireNonNull(blockInspectExecutor, "blockInspectExecutor");
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
                : BlockInspectToolExecutor.TOOL.equals(call.tool())
                    ? blockInspectExecutor
                    : catalogExecutor;
    return handler.execute(call);
  }

  @Override
  public void cancel(ClientToolCancellation cancellation) {
    Objects.requireNonNull(cancellation, "cancellation");
    previewExecutor.cancel(cancellation);
    playerContextExecutor.cancel(cancellation);
    blockInspectExecutor.cancel(cancellation);
    catalogExecutor.cancel(cancellation);
  }
}
