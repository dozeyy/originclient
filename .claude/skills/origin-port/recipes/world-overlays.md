# Recipe: world overlays (hitboxes, ThickLine, block outline, chunk borders, waypoints)

## Hitbox hook per era (proven: ThickLine propagation)

| Module(s) | Hook |
|---|---|
| pre-1.20, 1.20.x | `renderHitbox(...)` with 1 float |
| 1.21 / 1.21.1 | `renderHitbox(...)` with 4 floats |
| 1.21.4 | `renderHitbox` with 4 floats, but `render` takes 1 float |
| 1.21.5–1.21.8 | `renderHitboxes(HitboxRenderState ...)` |
| 1.21.10 | `HitboxFeatureRenderer.render` |
| 1.21.11 | gizmos: `EntityHitboxDebugRenderer.emitGizmos` (lazily loaded; the target class may not exist until first use) |
| 26.x | submits on the COLLECT_SUBMITS level event (below) |

- Quad type for thick lines:
  - `RenderType.debugQuads()` exists on 1.19.4–1.21.10.
  - `RenderTypes.debugQuads` on 1.21.11.
  - Before 1.19.4, use the module's `render/FillQuads.INSTANCE`.
- `RenderType.lines` cannot do line width. 1.21.11 lines need `setLineWidth` (LineWidth is a required vertex element).
- Trap (proven twice): a clean merge of 1.21.1's BlockOverlayRenderer brought in a "mod off = hairline" branch drawing
  into `RenderType.debugQuads()`. ≤1.19.3 modules must swap it to FillQuads. Grep `debugQuads` on every pre-1.19.4 port.

## Block outline and world draw entry per era
- **1.21.10 / 1.21.11**:
  - Block-outline `interactionShape` is null; don't read it. Check the module's BlockOverlayRenderer for what it uses instead.
  - Fabric `WorldRenderEvents` lives in the `.world` package.
- **1.21.11**: no projection matrix is exposed. Rebuild it from the camera orientation and FOV (WaypointHud, Team View).
- **26.1**: cancel `LevelRenderer.renderBlockOutline(BufferSource, PoseStack, boolean, LevelRenderState)` at HEAD.
- **26.2 / 26.3**:
  - Cancel `LevelRenderer.submitBlockOutline` at HEAD.
  - END_EXTRACTION has no blockOutlines(); use `levelState().blockOutlineRenderState != null`.
- **26.x world overlays are SUBMITS.**
  - `LevelRenderEvents` / `LevelRenderContext` give `poseStack()`, `levelState()` and `submitNodeCollector()`. There is no MultiBufferSource.
  - OriginRenderTypes keeps the 1.21.11 begin()/beginLines()/draw() shape, but RECORDS the vertices and submits them via
    `submitCustomGeometry` to the current COLLECT_SUBMITS collector.
  - `OriginRenderTypes::beginFrame` must be registered FIRST.
  - Proven by the overlays_world shot.
- **26.x camera**: `getViewRotationProjectionMatrix(new Matrix4f())` gives this frame's projection × view.
- **1.16.5**: WaypointHud takes its projection from `WorldRenderContext.matrixStack()/projectionMatrix()`. RenderSystem has no getters there.
- **Pre-1.19**: a buffer begun but never drawn shifts later draws. Always draw or discard every buffer you begin.

## Proof
- Use `dev-shots set=world`; the overlays_world shot shows chunk borders and the block overlay.
- Use the mixin export to prove the hitbox hook landed. The gizmo path loads lazily, so a shot alone may miss it.
