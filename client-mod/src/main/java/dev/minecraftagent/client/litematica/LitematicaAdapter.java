package dev.minecraftagent.client.litematica;

import java.util.UUID;

/**
 * Minimal preview-only integration. It exposes no paste, Easy Place, or world mutation operation.
 */
public interface LitematicaAdapter extends AutoCloseable {
  LitematicaSupportMatrix.Entry supportedCombination();

  LitematicaDisplayReport loadPreview(LitematicaPreviewRequest request);

  LitematicaDisplayReport removePreview(UUID previewId);

  LitematicaDisplayReport openMaterialList(UUID previewId);

  int loadedPreviewCount();

  @Override
  void close();
}
