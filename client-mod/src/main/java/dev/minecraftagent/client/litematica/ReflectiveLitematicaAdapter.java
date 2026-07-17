package dev.minecraftagent.client.litematica;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

/** Reflection boundary locked to one verified Litematica/MaLiLib public signature set. */
final class ReflectiveLitematicaAdapter implements LitematicaAdapter {
  private static final int MAX_PREVIEWS = 8;
  private static final long MAX_SCHEMATIC_BYTES = 16L * 1024L * 1024L;
  private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

  private final LitematicaSupportMatrix.Entry supportedCombination;
  private final Bindings bindings;
  private final Path managedPreviewRoot;
  private final BooleanSupplier clientThreadCheck;
  private final Map<UUID, LoadedPreview> loaded = new HashMap<>();

  private ReflectiveLitematicaAdapter(
      LitematicaSupportMatrix.Entry supportedCombination,
      Bindings bindings,
      Path managedPreviewRoot,
      BooleanSupplier clientThreadCheck)
      throws IOException {
    this.supportedCombination = Objects.requireNonNull(supportedCombination);
    this.bindings = Objects.requireNonNull(bindings);
    this.clientThreadCheck = Objects.requireNonNull(clientThreadCheck);
    this.managedPreviewRoot = managedPreviewRoot.toRealPath();
    if (!Files.isDirectory(this.managedPreviewRoot, LinkOption.NOFOLLOW_LINKS)) {
      throw new IOException("managed preview root is not a directory");
    }
  }

  static LitematicaAdapter link(
      LitematicaSupportMatrix.Entry supportedCombination,
      ClassLoader classLoader,
      Path managedPreviewRoot,
      BooleanSupplier clientThreadCheck)
      throws ReflectiveOperationException {
    return linkWithNames(
        supportedCombination,
        classLoader,
        managedPreviewRoot,
        clientThreadCheck,
        ApiClassNames.production());
  }

  static LitematicaAdapter linkWithNames(
      LitematicaSupportMatrix.Entry supportedCombination,
      ClassLoader classLoader,
      Path managedPreviewRoot,
      BooleanSupplier clientThreadCheck,
      ApiClassNames names)
      throws ReflectiveOperationException {
    try {
      return new ReflectiveLitematicaAdapter(
          supportedCombination,
          Bindings.link(classLoader, names),
          managedPreviewRoot,
          clientThreadCheck);
    } catch (IOException exception) {
      throw new ReflectiveOperationException("managed preview root is unavailable", exception);
    }
  }

  @Override
  public LitematicaSupportMatrix.Entry supportedCombination() {
    return supportedCombination;
  }

  @Override
  public synchronized LitematicaDisplayReport loadPreview(LitematicaPreviewRequest request) {
    Objects.requireNonNull(request);
    if (!onClientThread()) {
      return failed(request, LitematicaDisplayReport.Failure.WRONG_THREAD);
    }
    if (!validRequest(request)) {
      return failed(request, LitematicaDisplayReport.Failure.INVALID_REQUEST);
    }
    if (loaded.containsKey(request.previewId())) {
      return failed(request, LitematicaDisplayReport.Failure.PREVIEW_ALREADY_LOADED);
    }
    if (loaded.size() >= MAX_PREVIEWS) {
      return failed(request, LitematicaDisplayReport.Failure.PREVIEW_LIMIT_REACHED);
    }

    Path schematicFile;
    try {
      schematicFile = verifiedManagedFile(request);
    } catch (IOException | RuntimeException exception) {
      return failed(request, LitematicaDisplayReport.Failure.MANAGED_FILE_UNAVAILABLE);
    }

    Object holder = null;
    Object manager = null;
    Object schematic = null;
    Object placement = null;
    boolean ownedSchematic = false;
    try {
      holder = bindings.schematicHolderGetInstance.invoke(null);
      var before = new ArrayList<>(asCollection(bindings.schematicHolderGetAll.invoke(holder)));
      schematic = bindings.schematicHolderGetOrLoad.invoke(holder, schematicFile);
      if (schematic == null) {
        return failed(request, LitematicaDisplayReport.Failure.ADAPTER_CALL_FAILED);
      }
      ownedSchematic = !containsIdentity(before, schematic);
      manager = bindings.dataManagerGetPlacementManager.invoke(null);
      var origin =
          bindings.blockPositionConstructor.newInstance(
              request.originX(), request.originY(), request.originZ());
      placement =
          bindings.placementCreateFor.invoke(
              null, schematic, origin, request.displayName(), true, true);
      if (placement == null) {
        rollback(holder, manager, schematic, null, ownedSchematic);
        return failed(request, LitematicaDisplayReport.Failure.ADAPTER_CALL_FAILED);
      }
      bindings.placementManagerAdd.invoke(manager, placement, false);
      if (!containsIdentity(
          asCollection(bindings.placementManagerGetForSchematic.invoke(manager, schematic)),
          placement)) {
        rollback(holder, manager, schematic, placement, ownedSchematic);
        return failed(request, LitematicaDisplayReport.Failure.ADAPTER_CALL_FAILED);
      }

      loaded.put(
          request.previewId(),
          new LoadedPreview(
              request.contentSha256(), holder, manager, schematic, placement, ownedSchematic));
      return LitematicaDisplayReport.success(
          request.previewId(), request.contentSha256(), LitematicaDisplayReport.State.LOADED);
    } catch (ReflectiveOperationException | LinkageError | RuntimeException exception) {
      rollback(holder, manager, schematic, placement, ownedSchematic);
      return failed(request, LitematicaDisplayReport.Failure.ADAPTER_CALL_FAILED);
    }
  }

  @Override
  public synchronized LitematicaDisplayReport removePreview(UUID previewId) {
    Objects.requireNonNull(previewId);
    var preview = loaded.get(previewId);
    if (!onClientThread()) {
      return LitematicaDisplayReport.failed(
          previewId,
          preview == null ? null : preview.contentSha256,
          LitematicaDisplayReport.Failure.WRONG_THREAD);
    }
    if (preview == null) {
      return LitematicaDisplayReport.failed(
          previewId, null, LitematicaDisplayReport.Failure.PREVIEW_NOT_FOUND);
    }
    if (!removeLoaded(preview)) {
      return LitematicaDisplayReport.failed(
          previewId, preview.contentSha256, LitematicaDisplayReport.Failure.ADAPTER_CALL_FAILED);
    }
    loaded.remove(previewId);
    return LitematicaDisplayReport.success(
        previewId, preview.contentSha256, LitematicaDisplayReport.State.REMOVED);
  }

  @Override
  public synchronized LitematicaDisplayReport openMaterialList(UUID previewId) {
    Objects.requireNonNull(previewId);
    var preview = loaded.get(previewId);
    if (!onClientThread()) {
      return LitematicaDisplayReport.failed(
          previewId,
          preview == null ? null : preview.contentSha256,
          LitematicaDisplayReport.Failure.WRONG_THREAD);
    }
    if (preview == null) {
      return LitematicaDisplayReport.failed(
          previewId, null, LitematicaDisplayReport.Failure.PREVIEW_NOT_FOUND);
    }

    try {
      if (preview.materialList == null) {
        preview.materialList = bindings.materialListConstructor.newInstance(preview.placement);
        bindings.dataManagerSetMaterialList.invoke(null, preview.materialList);
        bindings.materialListRecreate.invoke(preview.materialList);
        preview.hudRenderer = bindings.materialListGetHudRenderer.invoke(preview.materialList);
      } else {
        bindings.dataManagerSetMaterialList.invoke(null, preview.materialList);
      }

      if (!(boolean) bindings.hudRendererShouldRender.invoke(preview.hudRenderer)) {
        bindings.hudRendererToggle.invoke(preview.hudRenderer);
      }
      if (!preview.hudRegistered) {
        var infoHud = bindings.infoHudGetInstance.invoke(null);
        bindings.infoHudAddRenderer.invoke(infoHud, preview.hudRenderer, true);
        bindings.infoHudSetEnabled.invoke(infoHud, true);
        preview.hudRegistered = true;
      }
      return LitematicaDisplayReport.success(
          previewId, preview.contentSha256, LitematicaDisplayReport.State.MATERIAL_LIST_OPEN);
    } catch (ReflectiveOperationException | LinkageError | RuntimeException exception) {
      return LitematicaDisplayReport.failed(
          previewId, preview.contentSha256, LitematicaDisplayReport.Failure.ADAPTER_CALL_FAILED);
    }
  }

  @Override
  public synchronized int loadedPreviewCount() {
    return loaded.size();
  }

  @Override
  public synchronized void close() {
    if (!onClientThread()) {
      return;
    }
    for (var entry : new ArrayList<>(loaded.entrySet())) {
      if (removeLoaded(entry.getValue())) {
        loaded.remove(entry.getKey());
      }
    }
  }

  private boolean removeLoaded(LoadedPreview preview) {
    try {
      removeMaterialHud(preview);
      boolean removed =
          (boolean) bindings.placementManagerRemove.invoke(preview.manager, preview.placement);
      if (!removed) {
        return false;
      }
      if (preview.ownedSchematic
          && asCollection(
                  bindings.placementManagerGetForSchematic.invoke(
                      preview.manager, preview.schematic))
              .isEmpty()) {
        bindings.schematicHolderRemove.invoke(preview.holder, preview.schematic);
      }
      return true;
    } catch (ReflectiveOperationException | LinkageError | RuntimeException exception) {
      return false;
    }
  }

  private void removeMaterialHud(LoadedPreview preview) throws ReflectiveOperationException {
    if (preview.hudRenderer == null) {
      return;
    }
    if ((boolean) bindings.hudRendererShouldRender.invoke(preview.hudRenderer)) {
      bindings.hudRendererToggle.invoke(preview.hudRenderer);
    }
    if (preview.hudRegistered) {
      var infoHud = bindings.infoHudGetInstance.invoke(null);
      bindings.infoHudRemoveRenderer.invoke(infoHud, preview.hudRenderer, true);
      preview.hudRegistered = false;
    }
    if (bindings.dataManagerGetMaterialList.invoke(null) == preview.materialList) {
      bindings.dataManagerSetMaterialList.invoke(null, new Object[] {null});
    }
  }

  private void rollback(
      Object holder, Object manager, Object schematic, Object placement, boolean ownedSchematic) {
    try {
      if (manager != null && placement != null) {
        bindings.placementManagerRemove.invoke(manager, placement);
      }
      if (holder != null && schematic != null && ownedSchematic) {
        bindings.schematicHolderRemove.invoke(holder, schematic);
      }
    } catch (ReflectiveOperationException | LinkageError | RuntimeException ignored) {
      // The operation already failed closed. Cleanup is best effort inside an optional mod.
    }
  }

  private Path verifiedManagedFile(LitematicaPreviewRequest request) throws IOException {
    Path candidate = request.managedFile().toAbsolutePath().normalize();
    if (!validManagedFileName(request, candidate.getFileName().toString())
        || !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
      throw new IOException("managed schematic is not a regular .litematica file");
    }
    Path real = candidate.toRealPath();
    BasicFileAttributes attributes =
        Files.readAttributes(candidate, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    if (!real.startsWith(managedPreviewRoot)
        || !attributes.isRegularFile()
        || attributes.size() < 1
        || attributes.size() > MAX_SCHEMATIC_BYTES
        || attributes.size() != request.managedFileBytes()
        || !attributes.lastModifiedTime().equals(request.lastModifiedTime())
        || !fileKey(attributes).equals(request.fileKey())) {
      throw new IOException("managed schematic is outside its bounded root");
    }
    return real;
  }

  private static boolean validManagedFileName(LitematicaPreviewRequest request, String fileName) {
    String[] components = fileName.split("\\.", -1);
    if (components.length != 4
        || !components[0].equals(request.previewId().toString())
        || !components[1].equals(Integer.toString(request.revision()))
        || !components[3].equals("litematica")) {
      return false;
    }
    try {
      return UUID.fromString(components[2]).toString().equals(components[2]);
    } catch (IllegalArgumentException exception) {
      return false;
    }
  }

  private boolean validRequest(LitematicaPreviewRequest request) {
    if (!SHA_256.matcher(request.contentSha256()).matches()
        || request.displayName().isBlank()
        || request.displayName().length() > 64) {
      return false;
    }
    return request.displayName().codePoints().noneMatch(Character::isISOControl);
  }

  private boolean onClientThread() {
    try {
      return clientThreadCheck.getAsBoolean();
    } catch (RuntimeException | LinkageError exception) {
      return false;
    }
  }

  private static java.util.Optional<String> fileKey(BasicFileAttributes attributes) {
    return java.util.Optional.ofNullable(attributes.fileKey()).map(Object::toString);
  }

  private static LitematicaDisplayReport failed(
      LitematicaPreviewRequest request, LitematicaDisplayReport.Failure failure) {
    return LitematicaDisplayReport.failed(request.previewId(), request.contentSha256(), failure);
  }

  private static Collection<?> asCollection(Object value) throws ReflectiveOperationException {
    if (value instanceof Collection<?> collection) {
      return collection;
    }
    throw new ReflectiveOperationException("adapter method returned a non-collection");
  }

  private static boolean containsIdentity(Collection<?> values, Object wanted) {
    return values.stream().anyMatch(value -> value == wanted);
  }

  static record ApiClassNames(
      String dataManager,
      String schematicHolder,
      String schematic,
      String placementManager,
      String placement,
      String materialListBase,
      String materialListPlacement,
      String hudRenderer,
      String infoHud,
      String infoHudRenderer) {
    static ApiClassNames production() {
      return new ApiClassNames(
          "fi.dy.masa.litematica.data.DataManager",
          "fi.dy.masa.litematica.data.SchematicHolder",
          "fi.dy.masa.litematica.schematic.LitematicaSchematic",
          "fi.dy.masa.litematica.schematic.placement.SchematicPlacementManager",
          "fi.dy.masa.litematica.schematic.placement.SchematicPlacement",
          "fi.dy.masa.litematica.materials.MaterialListBase",
          "fi.dy.masa.litematica.materials.MaterialListPlacement",
          "fi.dy.masa.litematica.materials.MaterialListHudRenderer",
          "fi.dy.masa.litematica.render.infohud.InfoHud",
          "fi.dy.masa.litematica.render.infohud.IInfoHudRenderer");
    }
  }

  private record Bindings(
      Method dataManagerGetPlacementManager,
      Method dataManagerGetMaterialList,
      Method dataManagerSetMaterialList,
      Method schematicHolderGetInstance,
      Method schematicHolderGetOrLoad,
      Method schematicHolderGetAll,
      Method schematicHolderRemove,
      Constructor<?> blockPositionConstructor,
      Method placementCreateFor,
      Method placementManagerAdd,
      Method placementManagerRemove,
      Method placementManagerGetForSchematic,
      Constructor<?> materialListConstructor,
      Method materialListRecreate,
      Method materialListGetHudRenderer,
      Method hudRendererShouldRender,
      Method hudRendererToggle,
      Method infoHudGetInstance,
      Method infoHudSetEnabled,
      Method infoHudAddRenderer,
      Method infoHudRemoveRenderer) {
    static Bindings link(ClassLoader classLoader, ApiClassNames names)
        throws ReflectiveOperationException {
      var dataManager = load(names.dataManager(), classLoader);
      var holder = load(names.schematicHolder(), classLoader);
      var schematic = load(names.schematic(), classLoader);
      var manager = load(names.placementManager(), classLoader);
      var placement = load(names.placement(), classLoader);
      var materialBase = load(names.materialListBase(), classLoader);
      var materialPlacement = load(names.materialListPlacement(), classLoader);
      var hudRenderer = load(names.hudRenderer(), classLoader);
      var infoHud = load(names.infoHud(), classLoader);
      var infoHudRenderer = load(names.infoHudRenderer(), classLoader);

      var placementCreate = findPlacementFactory(placement, schematic);
      var blockPosition = placementCreate.getParameterTypes()[1];
      var blockPositionConstructor = blockPosition.getConstructor(int.class, int.class, int.class);

      return new Bindings(
          requireStaticReturn(dataManager.getMethod("getSchematicPlacementManager"), manager),
          requireStaticReturn(dataManager.getMethod("getMaterialList"), materialBase),
          requireStaticReturn(dataManager.getMethod("setMaterialList", materialBase), void.class),
          requireStaticReturn(holder.getMethod("getInstance"), holder),
          requireReturn(holder.getMethod("getOrLoad", Path.class), schematic),
          requireCollection(holder.getMethod("getAllSchematics")),
          requireReturn(holder.getMethod("removeSchematic", schematic), boolean.class),
          blockPositionConstructor,
          placementCreate,
          requireReturn(
              manager.getMethod("addSchematicPlacement", placement, boolean.class), void.class),
          requireReturn(manager.getMethod("removeSchematicPlacement", placement), boolean.class),
          requireCollection(manager.getMethod("getAllPlacementsOfSchematic", schematic)),
          materialPlacement.getConstructor(placement),
          requireReturn(materialPlacement.getMethod("reCreateMaterialList"), void.class),
          requireReturn(materialBase.getMethod("getHudRenderer"), hudRenderer),
          requireReturn(hudRenderer.getMethod("getShouldRenderCustom"), boolean.class),
          requireReturn(hudRenderer.getMethod("toggleShouldRender"), void.class),
          requireStaticReturn(infoHud.getMethod("getInstance"), infoHud),
          requireReturn(infoHud.getMethod("setEnabled", boolean.class), void.class),
          requireReturn(
              infoHud.getMethod("addInfoHudRenderer", infoHudRenderer, boolean.class), void.class),
          requireReturn(
              infoHud.getMethod("removeInfoHudRenderer", infoHudRenderer, boolean.class),
              void.class));
    }

    private static Method findPlacementFactory(Class<?> placement, Class<?> schematic)
        throws ReflectiveOperationException {
      List<Method> matches =
          java.util.Arrays.stream(placement.getMethods())
              .filter(method -> method.getName().equals("createFor"))
              .filter(method -> Modifier.isStatic(method.getModifiers()))
              .filter(method -> method.getReturnType() == placement)
              .filter(method -> method.getParameterCount() == 5)
              .filter(
                  method -> {
                    var parameters = method.getParameterTypes();
                    return parameters[0] == schematic
                        && parameters[2] == String.class
                        && parameters[3] == boolean.class
                        && parameters[4] == boolean.class;
                  })
              .toList();
      if (matches.size() != 1) {
        throw new NoSuchMethodException("exact SchematicPlacement.createFor signature missing");
      }
      return matches.getFirst();
    }

    private static Class<?> load(String name, ClassLoader classLoader)
        throws ClassNotFoundException {
      return Class.forName(name, false, classLoader);
    }

    private static Method requireStaticReturn(Method method, Class<?> returnType)
        throws NoSuchMethodException {
      if (!Modifier.isStatic(method.getModifiers())) {
        throw new NoSuchMethodException(method.getName() + " is not static");
      }
      return requireReturn(method, returnType);
    }

    private static Method requireReturn(Method method, Class<?> returnType)
        throws NoSuchMethodException {
      if (method.getReturnType() != returnType) {
        throw new NoSuchMethodException(method.getName() + " return type changed");
      }
      return method;
    }

    private static Method requireCollection(Method method) throws NoSuchMethodException {
      if (!Collection.class.isAssignableFrom(method.getReturnType())) {
        throw new NoSuchMethodException(method.getName() + " no longer returns a collection");
      }
      return method;
    }
  }

  private static final class LoadedPreview {
    private final String contentSha256;
    private final Object holder;
    private final Object manager;
    private final Object schematic;
    private final Object placement;
    private final boolean ownedSchematic;
    private Object materialList;
    private Object hudRenderer;
    private boolean hudRegistered;

    private LoadedPreview(
        String contentSha256,
        Object holder,
        Object manager,
        Object schematic,
        Object placement,
        boolean ownedSchematic) {
      this.contentSha256 = contentSha256;
      this.holder = holder;
      this.manager = manager;
      this.schematic = schematic;
      this.placement = placement;
      this.ownedSchematic = ownedSchematic;
    }
  }
}
