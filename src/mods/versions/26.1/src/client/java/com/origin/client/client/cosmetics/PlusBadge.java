package com.origin.client.client.cosmetics;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.origin.client.OriginClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;

import java.io.InputStream;

/**
 * The Origin badge: the Origin mark (three tilted stroke-only rings, #E0E0E0)
 * drawn small next to the name of anyone playing on Origin (it was Origin+
 * members only until 2026-10-18). Looks only. Three places draw it:
 *  - the nametag above the head ({@link #nametagBadge}, put in front of the
 *    name by NametagStyleMixin);
 *  - the Tab player list ({@link #gui});
 *  - the friends list (gui/FriendsScreen, through OriginDeck.logo) - there it
 *    still marks Origin+ members, since every friend uses Origin.
 *
 * The art is textures/ui/origin_logo.png (256 px, white on transparent). A
 * 256 px picture shrunk to ~10 px by the GPU shimmers and loses the thin
 * strokes, so for the GUI it is box-filtered once into 32 / 64 / 128 px
 * copies and the closest one above the on-screen size is used.
 *
 * Everything fails soft: any problem (missing texture, font, odd entity)
 * means no badge, never an error in game. Render thread only.
 *
 * PER-VERSION DELTA (26.x, extract/submit era): name tags are glyph runs built
 * from a Submit record (NametagStyleMixin) -- there is no buffer source to add
 * the badge's own quad to, as 1.21.1 does. So on the nametag the badge IS text:
 * one glyph of the bitmap font assets/originclient/font/plus_badge.json (its
 * picture textures/font/plus_badge.png is the same mark box-filtered to 32 px,
 * advance 9 + a 1-unit space = 1.21.1's NAME_SIZE 8 + NAME_GAP 2). Vanilla's
 * own centring, background box, see-through pass and sneak fade then hold it
 * exactly like the name. Glyphs sample NEAREST, so far away the badge is a
 * touch crisper/rougher than 1.21.1's smooth quad. The Submit has no entity,
 * so {@link #nametagBadge} matches the tag's text against the loaded players'
 * display names -- the way the mixin already matches your own tag; the
 * below-name score line ("<score> <objective>") never equals a name. The GUI
 * blit names its pipeline and tints through its trailing ARGB; DynamicTexture
 * has no setFilter, so the copies bind a LINEAR sampler (OriginUi.reg's way).
 */
public final class PlusBadge {
	private PlusBadge() {
	}

	/** Silver-white, the brand's one accent. */
	private static final int RGB = 0xE0E0E0;
	/** The badge glyph, then a 1-unit space glyph (font/plus_badge.json). */
	private static final String GLYPHS = "";

	private static final int[] LEVELS = {32, 64, 128};
	private static final String SOURCE = "/assets/originclient/textures/ui/origin_logo.png";

	// null = not built yet; set together with failed = true when building failed (never retried).
	private static Identifier[] textures = null;
	private static boolean failed = false;
	private static Component badge = null;

	// ------------------------------------------------------------ nametag

	/** Does this nametag text get a badge? (a loaded player whose name it is, who plays on Origin) */
	public static boolean wantsNametag(Component text) {
		return text != null && wantsNametag(text.getString());
	}

	/** {@link #wantsNametag(Component)} on the tag's plain text. */
	public static boolean wantsNametag(String shown) {
		try {
			Minecraft mc = Minecraft.getInstance();
			if (shown == null || mc.level == null) {
				return false;
			}
			for (AbstractClientPlayer p : mc.level.players()) {
				Component name = p.getDisplayName();
				if (name != null && shown.equals(name.getString())) {
					return CosmeticsService.usesOrigin(p.getUUID());   // Will, 2026-10-18: everyone on Origin, not only Origin+
				}
			}
			return false;
		} catch (Throwable t) {
			return false;
		}
	}

	/** The badge text to put IN FRONT of `text`, or null when this tag gets none. */
	public static Component nametagBadge(Component text) {
		return wantsNametag(text) ? badge() : null;
	}

	/** The badge as text: the mark glyph + its gap, in the badge colour (the pass's alpha is kept by the font). */
	public static Component badge() {
		Component b = badge;
		if (b == null) {
			b = Component.literal(GLYPHS).withStyle(Style.EMPTY
					.withFont(new FontDescription.Resource(Identifier.fromNamespaceAndPath("originclient", "plus_badge")))
					.withColor(RGB));
			badge = b;
		}
		return b;
	}

	/** Width of {@link #badge()} in nametag units (vanilla shifts the tag left by half of what it adds). */
	public static float width(Component badge) {
		return Minecraft.getInstance().font.width(badge);
	}

	// ------------------------------------------------------------ GUI

	/** Draws the badge at (x, y), `size` GUI px square (Tab list). Fails soft. */
	public static void gui(GuiGraphicsExtractor g, int x, int y, int size) {
		try {
			int phys = Math.max(1, Math.round(size * (float) Minecraft.getInstance().getWindow().getGuiScale()));
			Identifier tex = textureFor(phys);
			if (tex == null) {
				return;
			}
			g.blit(RenderPipelines.GUI_TEXTURED, tex, x, y, 0f, 0f, size, size, 64, 64, 64, 64, 0xFF000000 | RGB);
		} catch (Throwable t) {
			// no badge
		}
	}

	// ------------------------------------------------------------ textures

	/** The copy to use for a badge `px` screen pixels wide: the smallest one that is not smaller. */
	private static Identifier textureFor(int px) {
		ensure();
		Identifier[] t = textures;
		if (t == null) {
			return null;
		}
		for (int i = 0; i < LEVELS.length; i++) {
			if (LEVELS[i] >= px || i == LEVELS.length - 1) {
				return t[i];
			}
		}
		return null;
	}

	private static void ensure() {
		if (textures != null || failed) {
			return;
		}
		if (!RenderSystem.isOnRenderThread()) {
			return; // try again on the render thread; nothing is marked failed
		}
		try (InputStream in = PlusBadge.class.getResourceAsStream(SOURCE)) {
			if (in == null) {
				throw new IllegalStateException("origin_logo.png missing");
			}
			Identifier[] made = new Identifier[LEVELS.length];
			try (NativeImage src = NativeImage.read(in)) {
				for (int i = 0; i < LEVELS.length; i++) {
					made[i] = register(boxFilter(src, LEVELS[i]), "plus_badge_" + LEVELS[i]);
				}
			}
			textures = made;
		} catch (Throwable t) {
			failed = true;
			OriginClient.LOGGER.warn("Origin+ badge: couldn't build the badge textures; no Tab badge will draw", t);
		}
	}

	/**
	 * `src` shrunk to size x size by averaging whole blocks of pixels (alpha-
	 * weighted, so the edges don't darken). Every copy is drawn stretched to the
	 * whole 64 x 64 UV square, so each level must be square and the source's
	 * size must divide evenly (256 -> 32 / 64 / 128).
	 */
	private static NativeImage boxFilter(NativeImage src, int size) {
		int f = Math.max(1, src.getWidth() / size);
		NativeImage out = new NativeImage(size, size, false);
		for (int y = 0; y < size; y++) {
			for (int x = 0; x < size; x++) {
				long sa = 0, sr = 0, sg = 0, sb = 0;
				for (int dy = 0; dy < f; dy++) {
					for (int dx = 0; dx < f; dx++) {
						int p = src.getPixel(x * f + dx, y * f + dy); // ARGB in this era
						int a = (p >>> 24) & 0xFF;
						sa += a;
						sr += (long) ((p >> 16) & 0xFF) * a;
						sg += (long) ((p >> 8) & 0xFF) * a;
						sb += (long) (p & 0xFF) * a;
					}
				}
				int n = f * f;
				int a = (int) (sa / n);
				int r = sa > 0 ? (int) (sr / sa) : 255;
				int g = sa > 0 ? (int) (sg / sa) : 255;
				int b = sa > 0 ? (int) (sb / sa) : 255;
				out.setPixel(x, y, (a << 24) | (r << 16) | (g << 8) | b);
			}
		}
		return out;
	}

	private static Identifier register(NativeImage image, String name) {
		Identifier id = Identifier.fromNamespaceAndPath("originclient", name);
		DynamicTexture tex = new DynamicTexture(() -> "originclient:" + name, image) {
			{
				// no setFilter in this era: smooth sampling rides the texture's sampler
				this.sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
			}
		};
		Minecraft.getInstance().getTextureManager().register(id, tex);
		return id;
	}
}
