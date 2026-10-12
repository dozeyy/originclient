package com.origin.client.client.shaders;

import com.mojang.blaze3d.systems.RenderSystem;
import com.origin.client.client.gui.OriginDeck;
import com.origin.client.client.gui.OriginWindow;
import net.minecraft.SharedConstants;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static com.origin.client.client.gui.OriginDeck.*;

// The in-client shader store, on the shared OriginWindow frame (Will, 2026-10-12:
// every Origin window in the mod menu's style). A curated set of the
// most-downloaded shaderpacks (live Modrinth download ranking, 2026-07-09), each
// with its real preview image and a short description. Every listed pack is
// verified to have a native Iris build for the current game version. Download
// shows a progress bar, then "Installed" (hover -> Remove, which deletes it from
// shaderpacks/). Type anywhere to filter by name/description; Esc clears the
// search first, then closes. Downloads land in shaderpacks/ for the RUNNING game
// version, so on return to Iris's shader menu (its re-init rescans the folder)
// the pack is there, ready to select. This is the only shader-download entry
// point -- Iris ships none. Opened from the "Download Shaders" button Origin adds
// to Iris's shader menu (OriginClientMod); closing returns there.
public class ShaderBrowserScreen extends OriginWindow {
	private static final float SEARCH_H = 36;
	private static final float LIST_TOP = BODY_Y + SEARCH_H + 16;
	private static final float LIST_BOTTOM = BODY_Y + BODY_H;
	private static final float ROW_H = 76, ROW_GAP = 8;
	private static final float PREV_W = 100, PREV_H = 56;   // 16:9, like Modrinth's gallery images
	private static final float CTRL_W = 148, CTRL_H = 32;

	private String query = "";
	private final List<Integer> view = new ArrayList<>();

	// {display name, modrinth slug, short description}. Ordered by Modrinth
	// download rank so the most popular packs sit up top. Slugs are verified
	// against Modrinth's API — correct slug drives both the download and the
	// preview image. No duplicate slugs.
	private static final String[][] DIR = {
			{"Complementary Reimagined", "complementary-reimagined", "Vanilla-faithful with top quality & performance"},
			{"Complementary Unbound", "complementary-unbound", "Full visual transform, high detail & performance"},
			{"BSL Shaders", "bsl-shaders", "Bright, colorful and distinct — a classic"},
			{"Photon Shaders", "photon-shader", "Gameplay-focused, semi-realistic style"},
			{"Solas Shader", "solas-shader", "Modern fantasy look with colored lighting"},
			{"Bliss Shaders", "bliss-shader", "Fantasy-styled with heavy customization"},
			{"Rethinking Voxels", "rethinking-voxels", "Colored block light with sharp shadows"},
			{"MakeUp — Ultra Fast", "makeup-ultra-fast-shaders", "Best quality-to-FPS; scales to any PC"},
			{"Super Duper Vanilla", "super-duper-vanilla", "Recreates the cancelled Super Duper pack"},
			{"Insanity Shader", "insanity-shader", "Stylized horror shader, highly tunable"},
			{"Mellow", "mellow", "Painterly, gameplay-first look (not \"Mello\")"},
			{"AstraLex Shaders", "astralex", "Effects-packed with deep customization"},
			{"Nostalgia Shader", "nostalgia-shader", "Modern take on classic old-school shaders"},
			{"Miniature Shader", "miniature-shader", "Ultra-light with shadows and reflections"},
			{"Hysteria Shaders", "hysteria-shaders", "Atmospheric horror, successor to Insanity"},
			{"Kappa Shader", "kappa-shader", "High-quality realistic, subtly stylized"},
			{"Spooklementary", "spooklementary", "Moody, atmospheric Complementary edit"},
			{"Shrimple", "shrimple", "Vanilla look + optional shadows & ray-tracing"},
			{"BSL Shaders Classic", "bsl-shaders-classic", "Vanilla-oriented classic BSL edition"},
			{"Noble Shaders", "noble", "Clean, immersive, deeply customizable"},
			{"FastPBR", "fastpbr", "Fast realistic PBR with atmospherics"},
			{"Visual Vibrance", "visual-vibrance", "Mojang's Vibrant Visuals, for Iris"},
			{"Vanilla Plus Shader", "vanilla-plus-shader", "Modern overhaul, faithful to vanilla"},
			{"Bloop Shaders", "bloop-shaders", "Max speed with good visuals, scalable"},
			{"I Like Vanilla", "i-like-vanilla", "Improves vanilla's style, doesn't replace it"},
			{"RenderPearl", "renderpearl", "Clean, high performance for modern GPUs"},
			{"Lux V1", "lux-v1", "High-fidelity pack based on BSL"},
			{"E-LITE Shaders", "lite-shaders", "Fast, beautiful MakeUp edit"},
			{"Aurora's Shaders", "auroras-shaders", "Colorful Vanilla+ Complementary edit"},
			{"RedHat Shaders", "redhat-shaders", "Based on classic Chocapic13 shaders"},
			{"Fantasy Shaders Reimagined", "fantasy-shaders", "Vivid, striking fantasy colors"},
			{"Pixel Perfect", "pixel-perfect-shaders", "Better visuals without interrupting play"},
			{"Pegasus Shaders", "pegasus", "High performance, lots of stylistic range"},
			{"Fantasy Shaders Unbound", "fantasy-shader-unbound", "A colorful fantasy setting"},
			{"UShader", "ushader", "Loose recreation of an old SEUS mod"},
			{"BVS — Best Vanilla Shader", "bvs", "Simple, great-looking vanilla shader"},
			{"Ebin Resurrected", "ebin-resurrected", "Performant and vibrant"},
			{"Cursed Fog", "cursed-fog", "Creepy, tense horror atmosphere"},
			{"Just Colored Lighting", "just-colored-lighting", "Fast colored lighting for low-end PCs"},
			{"ReShaded", "re-shaded", "Shaders with no mods and no FPS loss"},
			{"Exposa Shaders", "exposa-shaders", "A mix of natural and fantasy"},
			{"Glimmer", "glimmer-shaders", "Simple, performant, physically-based"},
			{"Simply Upscaled", "simply-upscaled", "Vanilla look with live texture upscaling"},
			{"LIGHT Shaders", "light-shaders", "High FPS with painting-like quality"},
			{"Vanilletix Shaders", "vanilletix", "Better graphics, keeps vanilla style"},
			{"OPAL Shaders", "opal-shaders", "Fantasy atmosphere, natural BSL colors"},
			{"Eclipse", "eclipseshaders", "Revamped version of EclipseLite"},
			{"DrDestens Shaders", "drdestens-shaders", "High-performance shadowless PBR"},
			{"Allium Shaders", "allium-shaders", "Creative Complementary Unbound edit"},
			{"Reverie", "reverie_shader", "Foggy, warm look using new Iris features"},
			{"Spring Shaders", "spring-shaders", "Fresh, clear spring-themed pack"},
			{"Pyvtron Shaders", "pyvtron-shaders-official-modrinth", "Very vibrant with good performance"},
			{"Mello", "mello", "High-perf visuals — a different pack from \"Mellow\""},
			{"Alpha Piscium", "alpha-piscium", "High-quality realistic shaderpack"},
			{"Lethal Shaders", "lethal-shaders", "Recreates the Lethal Company look"},
			{"Sildur's Vibrant Shaders", "sildurs-vibrant-shaders", "Optimized classic; runs on any hardware"},
			{"Phoxel PT", "phoxel-pt", "WIP path-traced beauty, blocky charm"},
			{"Stracciatella Shaders", "stracciatella-shaders", "Vanilla-style, colored lights, high perf"},
			{"PixelCraft Shaders", "pixelcraft-shaders", "Complementary edit replicating pixel art"},
			{"Snowimagined", "snowimagined", "Turns your world into a winter wonderland"},
			{"Trailer Shaders", "trailershaders", "Trailer-quality visuals with higher FPS"},
			{"Cyanide Shaders", "cyanide-shaders", "Vanilla+ that barely affects performance"},
			{"Tea Shaders", "tea-shaders", "Vanilla Minecraft with a little extra"},
			{"AstralCore", "astralcore", "Pink-themed dreamy Complementary edit"},
	};

	/** parent = Iris's shader menu (null in the dev shots: back to the game). */
	public ShaderBrowserScreen(Screen parent) {
		super(Component.literal("Download Shaders"), parent);
	}

	@Override
	protected void init() {
		super.init();
		// If packs were deleted from shaderpacks/ outside the client, revert
		// their rows from Installed back to Download.
		ShaderDownloader.syncWithDisk();
		rebuildView();
	}

	private void rebuildView() {
		view.clear();
		String q = query.trim().toLowerCase(Locale.ROOT);
		for (int i = 0; i < DIR.length; i++) {
			if (q.isEmpty()
					|| DIR[i][0].toLowerCase(Locale.ROOT).contains(q)
					|| DIR[i][2].toLowerCase(Locale.ROOT).contains(q)) {
				view.add(i);
			}
		}
		scroll = 0;
		scrollTarget = 0;
	}

	private static String gameVersion() {
		return SharedConstants.getCurrentVersion().getName();
	}

	// ---- header ----

	@Override
	protected String heading() {
		return "Shaders";
	}

	@Override
	protected String subheading() {
		return "Popular shader packs for Minecraft " + gameVersion();
	}

	@Override
	protected String headingIcon() {
		return "fullbright";
	}

	// ---- body ----

	@Override
	protected void drawBody(OriginDeck d) {
		try {
			drawSearch(d);
			drawList(d);
		} catch (Throwable t) {
			// a draw failure leaves the bare frame; Esc / close still work
		}
	}

	private void drawSearch(OriginDeck d) {
		// Always focused: typing anywhere filters (there is nothing else to type into).
		field(d, BODY_X, BODY_Y, BODY_W, SEARCH_H, query, "Search shader packs", true, "@find");
		String count = view.size() + (view.size() == 1 ? " pack" : " packs");
		d.textRight(count, BODY_X + BODY_W - 14, BODY_Y + SEARCH_H / 2, 12, false, 0, MUTE);
	}

	private void drawList(OriginDeck d) {
		float contentH = view.isEmpty() ? 0 : view.size() * (ROW_H + ROW_GAP) - ROW_GAP;
		maxScroll = Math.max(0, contentH - (LIST_BOTTOM - LIST_TOP));
		scrollTarget = Math.min(scrollTarget, maxScroll);
		scroll = Math.min(scroll, maxScroll);

		if (view.isEmpty()) {
			d.textCenter(d.fit("No shaders match \"" + query + "\"", BODY_W - 48, 14, false, 0),
					W / 2, LIST_TOP + 48, 14, false, 0, MUTE);
			return;
		}
		d.clip(0, LIST_TOP, W, LIST_BOTTOM - LIST_TOP);
		for (int vi = 0; vi < view.size(); vi++) {
			float y = LIST_TOP + vi * (ROW_H + ROW_GAP) - (float) scroll;
			if (y + ROW_H < LIST_TOP || y > LIST_BOTTOM) {
				continue;
			}
			drawRow(d, view.get(vi), y);
		}
		d.unclip();
		scrollbar(d, LIST_TOP, LIST_BOTTOM);
	}

	private void drawRow(OriginDeck d, int i, float y) {
		String name = DIR[i][0], slug = DIR[i][1], desc = DIR[i][2];
		float x = BODY_X, w = BODY_W;
		card(d, x, y, w, ROW_H, d.over(x, y, w, ROW_H));

		// preview thumbnail (or the Origin mark on a dark tile while it loads)
		float ix = x + 12, iy = y + (ROW_H - PREV_H) / 2;
		d.rect(ix, iy, PREV_W, PREV_H, 4, 0xFF0B0E0C, LINE);
		var prev = ShaderPreviews.get(slug);
		if (prev != null) {
			blitPreview(d, prev, ix, iy);
		} else {
			d.icon("@origin-mark", ix + PREV_W / 2 - 12, iy + PREV_H / 2 - 12, 24, 0x40F1E9D2);
		}

		// download control (right) -- placed first so the text knows its edge
		float bx = x + w - 16 - CTRL_W, by = y + (ROW_H - CTRL_H) / 2;
		drawControl(d, slug, bx, by);

		float tx = ix + PREV_W + 20, tw = bx - 24 - tx;
		d.text(d.fit(name, tw, 15, true, 0), tx, y + 33, 15, true, 0, TEXT);
		d.text(d.fit(desc, tw, 13, false, 0), tx, y + 53, 13, false, 0, DIM);
	}

	/**
	 * The preview image, drawn in the deck's physical-pixel space (the deck pose is
	 * 1/guiScale, so these coordinates are window pixels). GuiGraphics.blit draws
	 * immediately on 1.21.1, so the list's scissor clip covers it.
	 */
	private static void blitPreview(OriginDeck d, ShaderPreviews.Preview prev, float x, float y) {
		int x0 = Math.round(d.ox + x * d.s), y0 = Math.round(d.oy + y * d.s);
		int x1 = Math.round(d.ox + (x + PREV_W) * d.s), y1 = Math.round(d.oy + (y + PREV_H) * d.s);
		// cover-crop to the 16:9 tile (gallery images are mostly, not always, 16:9)
		float want = PREV_W / PREV_H, have = prev.w() / (float) prev.h();
		int uw = prev.w(), vh = prev.h();
		float u = 0, v = 0;
		if (have > want) {
			uw = Math.round(prev.h() * want);
			u = (prev.w() - uw) / 2f;
		} else if (have < want) {
			vh = Math.round(prev.w() / want);
			v = (prev.h() - vh) / 2f;
		}
		RenderSystem.enableBlend();
		RenderSystem.setShaderColor(1f, 1f, 1f, d.alpha);
		d.g.blit(prev.id(), x0 + 1, y0 + 1, x1 - x0 - 2, y1 - y0 - 2, u, v, uw, vh, prev.w(), prev.h());
		RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
	}

	private void drawControl(OriginDeck d, String slug, float bx, float by) {
		var st = ShaderDownloader.state(slug);
		switch (st.status()) {
			case WORKING -> {
				float cy = by + CTRL_H / 2;
				String pct = Math.round(st.progress() * 100) + "%";
				d.text("DOWNLOADING", bx, cy - 3, 10.5f, true, CAPS_TRACK, MUTE);
				d.text(pct, bx + CTRL_W - d.textW(pct, 12, true, 0), cy - 3, 12, true, 0, DIM);
				d.rect(bx, cy + 5, CTRL_W, 4, 2, 0x1FFFFFFF);
				d.rect(bx, cy + 5, CTRL_W * (float) Math.max(0.04, Math.min(1, st.progress())), 4, 2, ACC);
			}
			case DONE -> {
				// Installed -> hovering offers Remove (deletes it from shaderpacks/).
				if (d.over(bx, by, CTRL_W, CTRL_H)) {
					button(d, "Remove", "@trash", bx, by, CTRL_W, CTRL_H, Style.DANGER, true,
							() -> ShaderDownloader.remove(slug));
				} else {
					d.rect(bx, by, CTRL_W, CTRL_H, 6, ACC_SOFT);
					String t = "INSTALLED";
					float tw = 14 + 8 + d.textW(t, 11, true, CAPS_TRACK);
					float tx = bx + (CTRL_W - tw) / 2;
					d.icon("@check", tx, by + CTRL_H / 2 - 7, 14, ACC);
					d.textMid(t, tx + 22, by + CTRL_H / 2, 11, true, CAPS_TRACK, ACC);
				}
			}
			case ERROR -> {
				d.rect(bx, by, CTRL_W, CTRL_H, 6, RED_SOFT);
				d.textCenter("UNAVAILABLE", bx + CTRL_W / 2, by + CTRL_H / 2, 11, true, CAPS_TRACK, RED);
			}
			default -> button(d, "Download", null, bx, by, CTRL_W, CTRL_H, Style.SECONDARY, true,
					() -> ShaderDownloader.start(slug, gameVersion()));
		}
	}

	// ---- input ----

	/** Esc / back chevron: clear the search first, then close (back to Iris's shader menu). */
	@Override
	protected void back() {
		if (!query.isEmpty()) {
			query = "";
			rebuildView();
		} else {
			beginClose();
		}
	}

	@Override
	public boolean charTyped(char c, int modifiers) {
		if (c >= ' ' && c != 127) {
			query += c;
			rebuildView();
			return true;
		}
		return super.charTyped(c, modifiers);
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if (keyCode == GLFW.GLFW_KEY_BACKSPACE && !query.isEmpty()) {
			query = query.substring(0, query.length() - 1);
			rebuildView();
			return true;
		}
		return super.keyPressed(keyCode, scanCode, modifiers);
	}

	@Override
	public void onClose() {
		beginClose();
	}
}
