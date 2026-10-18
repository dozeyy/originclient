package com.origin.client.client.cosmetics;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.PoseStack;
import com.origin.client.OriginClient;
import net.fabricmc.fabric.api.client.rendering.v1.SpecialGuiElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.gui.render.state.pip.PictureInPictureRenderState;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.PlayerRenderState;
import org.joml.Matrix3x2f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.List;
import java.util.function.Supplier;

/**
 * The Cosmetics screen's model thumbnails on the deferred GUI (1.21.6-1.21.8).
 *
 * 1.21.1 draws each tile's thumbnail straight into the GUI buffers. Here a 3D
 * model in the GUI is a picture-in-picture: GuiRenderer renders it into a
 * texture later in the frame and blits that texture into the box. GuiRenderer
 * keeps ONE renderer -- one texture -- per render-state CLASS, and re-renders
 * that texture for every state of the class it meets in a frame, while the
 * blits all run afterwards. Several entity boxes in one frame therefore all
 * show the last one drawn (or, when their sizes differ, point at a texture the
 * renderer already freed). So every thumbnail gets its OWN state class
 * ({@link #SLOTS} of them, S0..S23) and with it its own renderer and texture,
 * registered through Fabric API's SpecialGuiElementRegistry. A tile takes the
 * next free slot each frame ({@link #beginFrame} resets the count).
 *
 * The renderer does exactly what vanilla's GuiEntityRenderer does with a
 * submitEntityRenderState box (entity-in-UI lighting, the state's translation
 * and rotation, the camera override, no shadow), so a thumbnail looks like
 * the big preview zoomed in. While a thumbnail renders, the menu preview's
 * look is swapped for the tile's own item (MenuPlayerPreview.swapLook), which
 * CosmeticLayer and CapePhysicsMixin read for MenuPlayerPreview.STATE_ID.
 *
 * Fails soft: when registration fails, no renderer was ever built, every slot
 * is taken, or a render throws (then thumbnails stay off for the session),
 * {@link #submit} returns false and the tile shows its icon.
 */
public final class MenuThumbs {
	private MenuThumbs() {
	}

	/** Thumbnails that can draw in one frame: four columns, a screenful of rows plus the one scrolling in. */
	public static final int SLOTS = 24;

	/** Vanilla's full-bright light, as GuiEntityRenderer draws entities in the GUI. */
	private static final int LIGHT = 0xF000F0;

	private static boolean registered = false;
	private static volatile boolean built = false;   // set when GuiRenderer constructs our renderers
	private static volatile boolean broken = false;
	private static int used = 0;   // slots taken this frame (render thread)

	/** Registers the slot renderers. Call once, from OriginClientMod (before the game renderer exists). */
	public static void register() {
		if (registered) {
			return;
		}
		registered = true;
		try {
			for (Supplier<Thumb> slot : NEW) {
				reg(slot.get().getClass());
			}
		} catch (Throwable t) {
			broken = true;
			OriginClient.LOGGER.warn("Origin cosmetics: thumbnail renderers failed to register; tiles show icons", t);
		}
	}

	@SuppressWarnings("unchecked")
	private static <T extends Thumb> void reg(Class<?> type) {
		Class<T> cls = (Class<T>) type;
		SpecialGuiElementRegistry.register(ctx -> new Renderer<>(ctx.vertexConsumers(), cls));
	}

	/** True when thumbnails can be drawn at all this session. */
	static boolean ready() {
		return built && !broken;
	}

	/** Frees every slot: call once per frame before the tiles draw. */
	public static void beginFrame() {
		used = 0;
	}

	/**
	 * Queues `state` (worn with `wear`) as the next free slot's picture in the
	 * GUI-px box x0..y1, clipped to the current scissor; `size`, `offset`,
	 * `rotation` and `camera` mean what they do for submitEntityRenderState.
	 * False when no slot is free or thumbnails are off.
	 */
	static boolean submit(GuiGraphics g, PlayerRenderState state, CosmeticsService.Look wear, float size,
			Vector3f offset, Quaternionf rotation, Quaternionf camera, int x0, int y0, int x1, int y1) {
		if (!ready() || used >= SLOTS) {
			return false;
		}
		Thumb t = NEW.get(used).get();
		ScreenRectangle scissor = g.scissorStack.peek();
		ScreenRectangle local = new ScreenRectangle(x0, y0, x1 - x0, y1 - y0);
		t.entity = state;
		t.look = wear;
		t.size = size;
		t.offset = offset;
		t.rotation = rotation;
		t.camera = camera;
		t.x0 = x0;
		t.y0 = y0;
		t.x1 = x1;
		t.y1 = y1;
		t.scissor = scissor;
		t.bounds = scissor != null ? scissor.intersection(local) : local;
		g.guiRenderState.submitPicturesInPictureState(t);
		used++;
		return true;
	}

	/** One thumbnail's box, as GuiEntityRenderState carries it. A new one per tile per frame. */
	abstract static class Thumb implements PictureInPictureRenderState {
		PlayerRenderState entity;
		CosmeticsService.Look look;
		float size;
		Vector3f offset;
		Quaternionf rotation;
		Quaternionf camera;
		int x0, y0, x1, y1;
		ScreenRectangle scissor;
		ScreenRectangle bounds;

		@Override
		public int x0() {
			return x0;
		}

		@Override
		public int y0() {
			return y0;
		}

		@Override
		public int x1() {
			return x1;
		}

		@Override
		public int y1() {
			return y1;
		}

		@Override
		public float scale() {
			return size;
		}

		@Override
		public ScreenRectangle scissorArea() {
			return scissor;
		}

		@Override
		public ScreenRectangle bounds() {
			return bounds;
		}

		/** No 2D pose on the box (the interface's identity default, spelled out). */
		public Matrix3x2f pose() {
			return new Matrix3x2f();
		}
	}

	// One class per slot: GuiRenderer finds a box's renderer by the state's class.
	static final class S0 extends Thumb { }
	static final class S1 extends Thumb { }
	static final class S2 extends Thumb { }
	static final class S3 extends Thumb { }
	static final class S4 extends Thumb { }
	static final class S5 extends Thumb { }
	static final class S6 extends Thumb { }
	static final class S7 extends Thumb { }
	static final class S8 extends Thumb { }
	static final class S9 extends Thumb { }
	static final class S10 extends Thumb { }
	static final class S11 extends Thumb { }
	static final class S12 extends Thumb { }
	static final class S13 extends Thumb { }
	static final class S14 extends Thumb { }
	static final class S15 extends Thumb { }
	static final class S16 extends Thumb { }
	static final class S17 extends Thumb { }
	static final class S18 extends Thumb { }
	static final class S19 extends Thumb { }
	static final class S20 extends Thumb { }
	static final class S21 extends Thumb { }
	static final class S22 extends Thumb { }
	static final class S23 extends Thumb { }

	private static final List<Supplier<Thumb>> NEW = List.of(
			S0::new, S1::new, S2::new, S3::new, S4::new, S5::new, S6::new, S7::new,
			S8::new, S9::new, S10::new, S11::new, S12::new, S13::new, S14::new, S15::new,
			S16::new, S17::new, S18::new, S19::new, S20::new, S21::new, S22::new, S23::new);

	/** One slot's renderer: its own texture, drawn like vanilla's GuiEntityRenderer. */
	private static final class Renderer<T extends Thumb> extends PictureInPictureRenderer<T> {
		private final MultiBufferSource.BufferSource buffers;
		private final Class<T> type;

		Renderer(MultiBufferSource.BufferSource buffers, Class<T> type) {
			super(buffers);
			this.buffers = buffers;
			this.type = type;
			built = true;
		}

		@Override
		public Class<T> getRenderStateClass() {
			return type;
		}

		@Override
		protected void renderToTexture(T t, PoseStack pose) {
			if (broken || t.entity == null) {
				return;
			}
			CosmeticsService.Look was = MenuPlayerPreview.swapLook(t.look);
			EntityRenderDispatcher dispatcher = null;
			try {
				Minecraft mc = Minecraft.getInstance();
				mc.gameRenderer.getLighting().setupFor(Lighting.Entry.ENTITY_IN_UI);
				dispatcher = mc.getEntityRenderDispatcher();
				pose.translate(t.offset.x, t.offset.y, t.offset.z);
				pose.mulPose(t.rotation);
				dispatcher.overrideCameraOrientation(t.camera.conjugate(new Quaternionf()).rotateY((float) Math.PI));
				dispatcher.setRenderShadow(false);
				dispatcher.render(t.entity, 0.0, 0.0, 0.0, pose, buffers, LIGHT);
			} catch (Throwable ex) {
				broken = true;
				OriginClient.LOGGER.warn("Origin cosmetics: tile thumbnail failed to render; tiles show icons", ex);
			} finally {
				if (dispatcher != null) {
					dispatcher.setRenderShadow(true);
				}
				MenuPlayerPreview.swapLook(was);
			}
		}

		/** The box's centre is the model's anchor, as for GuiEntityRenderer. */
		protected float getTranslateY(int height, int guiScale) {
			return height / 2f;
		}

		protected String getTextureLabel() {
			return "originclient cosmetic thumbnail";
		}
	}
}
