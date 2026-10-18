package com.origin.client.client.render.terrain;

import com.origin.client.OriginClient;
import com.origin.client.client.mixin.SodiumDefaultChunkRendererAccessor;
import com.origin.client.client.mixin.SodiumShaderChunkRendererAccessor;
import com.origin.client.client.mods.Mods;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.caffeinemc.mods.sodium.client.SodiumClientMod;
import net.caffeinemc.mods.sodium.client.gl.device.CommandList;
import net.caffeinemc.mods.sodium.client.gl.device.DrawCommandList;
import net.caffeinemc.mods.sodium.client.gl.tessellation.GlTessellation;
import net.caffeinemc.mods.sodium.client.model.quad.properties.ModelQuadFacing;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.LocalSectionIndex;
import net.caffeinemc.mods.sodium.client.render.chunk.data.SectionRenderDataStorage;
import net.caffeinemc.mods.sodium.client.render.chunk.data.SectionRenderDataUnsafe;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderList;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderListIterable;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import net.caffeinemc.mods.sodium.client.render.chunk.shader.ChunkShaderInterface;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL40C;
import org.lwjgl.opengl.GL43C;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Iterator;

// 1.21.2-1.21.4 PORT (Sodium 0.6.1 / 0.6.8 / 0.6.13, the launcher's catalog pins):
// the same renderer hook as 1.21.1's 0.6.13, compile-only in build.gradle. Every
// Sodium member used here has the same shape in those three (checked against
// their sources). Iris is reached by reflection (not on this module's compile
// path), and the Sodium-typed region array is created on first use, so menu/F3
// calls into this class can never NoClassDefFoundError without Sodium.
//
// Origin Render (Settings > Performance, off by default): GPU occlusion culling
// for Sodium's opaque terrain. Sodium still decides WHICH sections are in
// view (frustum + cave culling on the CPU); this skips the ones hidden behind
// terrain already drawn this frame, decided on the graphics card (TerrainCuller),
// so a section is never skipped unless real geometry covers it.
//
// Per pass (SodiumChunkRendererMixin hands every DefaultChunkRenderer.render here):
//   SOLID   two-phase: draw last frame's visible set, build the depth pyramid,
//           test everything, draw what's visible but not yet drawn.
//   CUTOUT  single-phase against the finished solid depth.
//   TRANSLUCENT and anything we can't handle: return false, Sodium draws as usual.
// Same vertex arrays, shared index buffer, program and uniforms as Sodium's own
// loop (begin/end through the accessor, so Iris's hooks still run); only the draw
// call changes from glMultiDrawElementsBaseVertex to GPU-written indirect commands.
//
// Never broken (mandate 4): off by default; stock Sodium whenever a shaderpack is
// on (Iris owns the pipeline then, mandate 3), the card lacks compute shaders
// (TerrainCuller.supported: GL 4.3 features, any context version), the pass
// isn't drawing into Minecraft's main framebuffer, or anything ever throws or
// raises a GL error (then it stays off for the session and F3 says so).
public final class OriginRender {
	private OriginRender() {
	}

	private static TerrainCuller culler;
	private static boolean failed;
	private static boolean hookSeen;
	private static String failure = "";
	// the first frames after turning on check glGetError; any error -> stock Sodium
	private static int errorChecks;
	// what the last pass did, for the F3 line
	private static String state = "off";

	// this pass's regions: which commands in the culler's buffer belong to each
	private static RenderRegion[] regions; // created in create(): no Sodium class load before a real pass
	private static int[] regionFirst = new int[64], regionCount = new int[64];
	private static int regionTotal;

	private static final Matrix4f VIEW_PROJ = new Matrix4f();

	/** True when the whole pass was drawn here; false = let Sodium draw it. */
	public static boolean render(DefaultChunkRenderer renderer, ChunkRenderMatrices matrices, CommandList commandList,
			ChunkRenderListIterable renderLists, TerrainRenderPass pass, CameraTransform camera) {
		hookSeen = true;
		if (!enabled()) {
			if (culler != null) {
				release();
			}
			state = "off";
			return false;
		}
		if (failed) {
			state = "stopped: " + failure;
			return false;
		}
		if (pass.isTranslucent()) {
			return false;
		}
		if (shaderPackOn()) {
			state = "paused while a shaderpack is on";
			return false;
		}
		boolean twoPhase = pass == DefaultTerrainRenderPasses.SOLID;
		if (!twoPhase && pass != DefaultTerrainRenderPasses.CUTOUT) {
			return false; // a pass another mod added: not ours
		}
		if (!(renderer instanceof SodiumShaderChunkRendererAccessor base)
				|| !(renderer instanceof SodiumDefaultChunkRendererAccessor own)) {
			fail("Sodium hooks missing");
			return false;
		}
		RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
		int depthTex = main.getDepthTextureId();
		if (GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING) != main.frameBufferId || depthTex <= 0) {
			state = "waiting (terrain not drawn to the main screen)";
			return false;
		}
		if (culler == null && !create()) {
			return false;
		}

		boolean checkErrors = errorChecks > 0;
		if (checkErrors) {
			errorChecks--;
			// clear older errors so only ours count (capped: a lost context repeats its error forever)
			for (int i = 0; i < 16 && GL11C.glGetError() != GL11C.GL_NO_ERROR; i++) {
			}
		}

		// 1. everything before begin() can bail to Sodium with nothing drawn
		try {
			if (!collect(renderLists, pass, camera)) {
				return true; // nothing to draw this pass
			}
			own.originclient$sharedIndexBuffer().ensureCapacity(commandList, culler.maxElements());
			culler.upload();
		} catch (Throwable t) {
			fail(t);
			return false;
		}

		// 2. Sodium's own setup, our draws, Sodium's own teardown
		try {
			base.originclient$begin(pass);
		} catch (Throwable t) {
			// begin may have half-run (render state set, no program): undo what it can
			fail(t);
			try {
				base.originclient$end(pass);
			} catch (Throwable ignored) {
				// nothing bound to undo
			}
			return false;
		}
		try {
			ChunkShaderInterface shader = base.originclient$activeProgram().getInterface();
			shader.setProjectionMatrix(matrices.projection());
			shader.setModelViewMatrix(matrices.modelView());
			VIEW_PROJ.set(matrices.projection()).mul(matrices.modelView());
			if (twoPhase) {
				culler.phaseA();
				draw(own, commandList, shader, camera, culler.indirectBuffer(true));
				culler.buildHiZ(depthTex, main.width, main.height);
				culler.beginFrameStats();
				culler.phaseB(VIEW_PROJ, true);
				culler.endFrameStats();
			} else {
				culler.buildHiZ(depthTex, main.width, main.height);
				culler.phaseB(VIEW_PROJ, false);
			}
			draw(own, commandList, shader, camera, culler.indirectBuffer(false));
		} catch (Throwable t) {
			// part of the pass may be drawn; Sodium redraws it all (same pixels)
			fail(t);
			return false;
		} finally {
			GL15C.glBindBuffer(GL40C.GL_DRAW_INDIRECT_BUFFER, 0);
			base.originclient$end(pass);
		}

		if (checkErrors) {
			int err = GL11C.glGetError();
			if (err != GL11C.GL_NO_ERROR) {
				fail("GL error 0x" + Integer.toHexString(err));
			}
		}
		if (twoPhase) {
			int[] s = culler.lastStats();
			state = s == null ? "on" : "on, drawing " + s[1] + " of " + s[0] + " solid sections";
		}
		return true;
	}

	/** Fills the culler with Sodium's command list for this pass. False = nothing to draw. */
	private static boolean collect(ChunkRenderListIterable renderLists, TerrainRenderPass pass, CameraTransform camera) {
		culler.reset();
		// last pass's regions (kept until now: a two-phase pass draws the list twice)
		Arrays.fill(regions, 0, regionTotal, null);
		regionTotal = 0;
		boolean faceCulling = SodiumClientMod.options().performance.useBlockFaceCulling;
		Iterator<ChunkRenderList> lists = renderLists.iterator(false);
		while (lists.hasNext()) {
			ChunkRenderList list = lists.next();
			RenderRegion region = list.getRegion();
			SectionRenderDataStorage storage = region.getStorage(pass);
			if (storage == null) {
				continue;
			}
			var sections = list.sectionsWithGeometryIterator(false);
			if (sections == null) {
				continue;
			}
			int first = culler.commandCount();
			int regionSlot = (region.getX() * 73856093 ^ region.getY() * 19349663 ^ region.getZ() * 83492791)
					& (TerrainCuller.REGION_SLOTS - 1);
			while (sections.hasNext()) {
				int idx = sections.nextByteAsInt();
				long data = storage.getDataPointer(idx);
				int cx = region.getChunkX() + LocalSectionIndex.unpackX(idx);
				int cy = region.getChunkY() + LocalSectionIndex.unpackY(idx);
				int cz = region.getChunkZ() + LocalSectionIndex.unpackZ(idx);
				int slices = faceCulling ? visibleFaces(camera, cx, cy, cz) : ModelQuadFacing.ALL;
				slices &= SectionRenderDataUnsafe.getSliceMask(data);
				if (slices == 0) {
					continue;
				}
				// same camera-relative maths as Sodium's region offset + local position
				culler.addSection(((cx << 4) - camera.intX) - camera.fracX, ((cy << 4) - camera.intY) - camera.fracY,
						((cz << 4) - camera.intZ) - camera.fracZ, regionSlot * TerrainCuller.SECTIONS_PER_REGION + idx);
				for (int f = 0; f < ModelQuadFacing.COUNT; f++) {
					if ((slices >> f & 1) != 0) {
						culler.addCommand((int) SectionRenderDataUnsafe.getElementCount(data, f),
								(int) SectionRenderDataUnsafe.getVertexOffset(data, f));
					}
				}
			}
			int count = culler.commandCount() - first;
			if (count > 0) {
				addRegion(region, first, count);
			}
		}
		return regionTotal > 0;
	}

	/** Sodium's block-face culling: which facings of a section can face the camera (DefaultChunkRenderer.getVisibleFaces). */
	private static int visibleFaces(CameraTransform cam, int cx, int cy, int cz) {
		int minX = cx << 4, minY = cy << 4, minZ = cz << 4;
		int planes = 1 << ModelQuadFacing.UNASSIGNED.ordinal();
		if (cam.intX > minX - 3) planes |= 1 << ModelQuadFacing.POS_X.ordinal();
		if (cam.intY > minY - 3) planes |= 1 << ModelQuadFacing.POS_Y.ordinal();
		if (cam.intZ > minZ - 3) planes |= 1 << ModelQuadFacing.POS_Z.ordinal();
		if (cam.intX < minX + 16 + 3) planes |= 1 << ModelQuadFacing.NEG_X.ordinal();
		if (cam.intY < minY + 16 + 3) planes |= 1 << ModelQuadFacing.NEG_Y.ordinal();
		if (cam.intZ < minZ + 16 + 3) planes |= 1 << ModelQuadFacing.NEG_Z.ordinal();
		return planes;
	}

	private static void addRegion(RenderRegion region, int first, int count) {
		if (regionTotal == regions.length) {
			regions = Arrays.copyOf(regions, regionTotal * 2);
			regionFirst = Arrays.copyOf(regionFirst, regionTotal * 2);
			regionCount = Arrays.copyOf(regionCount, regionTotal * 2);
		}
		regions[regionTotal] = region;
		regionFirst[regionTotal] = first;
		regionCount[regionTotal] = count;
		regionTotal++;
	}

	/** One indirect multi-draw per region, with Sodium's vertex array and region offset. */
	private static void draw(SodiumDefaultChunkRendererAccessor own, CommandList commandList, ChunkShaderInterface shader,
			CameraTransform camera, int indirect) {
		for (int i = 0; i < regionTotal; i++) {
			RenderRegion region = regions[i];
			GlTessellation tessellation = own.originclient$prepareTessellation(commandList, region);
			shader.setRegionOffset((region.getOriginX() - camera.intX) - camera.fracX,
					(region.getOriginY() - camera.intY) - camera.fracY, (region.getOriginZ() - camera.intZ) - camera.fracZ);
			try (DrawCommandList ignored = commandList.beginTessellating(tessellation)) {
				// bound after the vertex array: the indirect binding is global GL state, not tracked by Sodium
				GL15C.glBindBuffer(GL40C.GL_DRAW_INDIRECT_BUFFER, indirect);
				GL43C.glMultiDrawElementsIndirect(GL11C.GL_TRIANGLES, GL11C.GL_UNSIGNED_INT,
						(long) regionFirst[i] * TerrainCuller.COMMAND_BYTES, regionCount[i], TerrainCuller.COMMAND_BYTES);
			}
		}
	}

	private static boolean enabled() {
		try {
			return Mods.bool(Mods.PERFORMANCE_ID, "originRender");
		} catch (Throwable t) {
			return false;
		}
	}

	// Iris's public API (net.irisshaders.iris.api.v0.IrisApi), looked up once by reflection
	private static Object irisApi;
	private static Method irisInUse;
	private static boolean irisLooked;

	/** Iris owns the terrain pipeline while a pack is on; any doubt counts as on. */
	private static boolean shaderPackOn() {
		try {
			if (!irisLooked) {
				irisLooked = true;
				if (FabricLoader.getInstance().isModLoaded("iris")) {
					Class<?> cls = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
					irisApi = cls.getMethod("getInstance").invoke(null);
					irisInUse = cls.getMethod("isShaderPackInUse");
				}
			}
			if (!FabricLoader.getInstance().isModLoaded("iris")) {
				return false; // no Iris: no shaderpack can be on
			}
			return irisInUse == null || (boolean) irisInUse.invoke(irisApi);
		} catch (Throwable t) {
			return true;
		}
	}

	private static boolean create() {
		if (!TerrainCuller.supported()) {
			fail("your graphics card has no compute shaders");
			return false;
		}
		try {
			if (regions == null) {
				regions = new RenderRegion[64];
			}
			culler = new TerrainCuller();
			errorChecks = 3;
			return true;
		} catch (Throwable t) {
			fail(t);
			return false;
		}
	}

	private static void release() {
		if (regions != null) {
			Arrays.fill(regions, 0, regionTotal, null);
		}
		regionTotal = 0;
		try {
			culler.delete();
		} catch (Throwable ignored) {
			// context teardown: nothing left to free
		}
		culler = null;
	}

	private static void fail(Throwable t) {
		OriginClient.LOGGER.warn("Origin Render stopped, using Sodium's renderer", t);
		fail(t.getClass().getSimpleName());
	}

	private static void fail(String why) {
		if (!failed) {
			OriginClient.LOGGER.warn("Origin Render stopped: {}", why);
		}
		failed = true;
		failure = why;
		state = "stopped: " + why;
		if (culler != null) {
			release();
		}
	}

	/** The F3 line (left column), or null while the setting is off. */
	public static String debugLine() {
		if (!enabled()) {
			return null;
		}
		if (!hookSeen) {
			return "Origin Render: not running (Sodium hook missing)";
		}
		return "Origin Render: " + state;
	}

	/** For the menu: why an enabled Origin Render can't run, or null when it can (or is off). */
	public static String blockedReason() {
		if (failed) {
			return "stopped (" + failure + ")";
		}
		if (!TerrainCuller.supported()) {
			return "your graphics card is too old for it (no compute shaders), so Sodium keeps drawing as usual";
		}
		if (shaderPackOn()) {
			return "pauses while a shaderpack is on";
		}
		return null;
	}
}
