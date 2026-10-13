package com.origin.client.client.shaders;

import com.origin.client.client.gui.OriginDeck;
import com.origin.client.client.gui.OriginWindow;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.GlStateManager;
import org.lwjgl.input.Keyboard;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static com.origin.client.client.gui.OriginDeck.*;

// The in-client shader store, on the shared OriginWindow frame (Will, 2026-10-12:
// every Origin window in the mod menu's style). A curated set of the
// most-downloaded shaderpacks (live Modrinth download ranking, 2026-07-09), each
// with its real preview image and a short description. Download shows a
// progress bar, then "Installed" (hover -> Remove, which deletes it from
// shaderpacks/). Type anywhere to filter by name/description; Esc clears the
// search first, then closes. Opened from the "Download Shaders" button Origin
// adds to the shader menu (MenuHooks); closing returns there, and the menu's
// re-init rescans the folder, so the pack is ready to select.
//
// 1.8.9: the shader menu is OptiFine's (GuiShaders), so the list holds only the
// packs that publish an OptiFine build tagged 1.8.9 on Modrinth -- re-verified
// against Modrinth's API on 2026-10-13 (20 of the 64 later-version packs; the
// rest have no 1.8.9 build and would only ever show UNAVAILABLE). Downloads ask
// Modrinth for the "optifine" loader (ShaderDownloader). Keys are LWJGL2
// (OriginScreen's modern key shape, Keyboard.KEY_* codes).
public class ShaderBrowserScreen extends OriginWindow {
	private static final float SEARCH_H = 36;
	private static final float LIST_TOP = BODY_Y + SEARCH_H + 16;
	private static final float LIST_BOTTOM = BODY_Y + BODY_H;
	private static final float ROW_H = 76, ROW_GAP = 8;
	private static final float PREV_W = 100, PREV_H = 56;   // 16:9, like Modrinth's gallery images
	private static final float CTRL_W = 148, CTRL_H = 32;

	private String query = "";
	private final List<Integer> view = new ArrayList<>();

	// {display name, modrinth slug, short description}, in Modrinth download-rank
	// order. Slugs drive both the download and the preview image.
	private static final String[][] DIR = {
			{"Complementary Reimagined", "complementary-reimagined", "Vanilla-faithful with top quality & performance"},
			{"Complementary Unbound", "complementary-unbound", "Full visual transform, high detail & performance"},
			{"BSL Shaders", "bsl-shaders", "Bright, colorful and distinct — a classic"},
			{"Insanity Shader", "insanity-shader", "Stylized horror shader, highly tunable"},
			{"Mellow", "mellow", "Painterly, gameplay-first look"},
			{"Miniature Shader", "miniature-shader", "Ultra-light with shadows and reflections"},
			{"Hysteria Shaders", "hysteria-shaders", "Atmospheric horror, successor to Insanity"},
			{"Spooklementary", "spooklementary", "Moody, atmospheric Complementary edit"},
			{"BSL Shaders Classic", "bsl-shaders-classic", "Vanilla-oriented classic BSL edition"},
			{"Lux V1", "lux-v1", "High-fidelity pack based on BSL"},
			{"RedHat Shaders", "redhat-shaders", "Based on classic Chocapic13 shaders"},
			{"BVS — Best Vanilla Shader", "bvs", "Simple, great-looking vanilla shader"},
			{"Cursed Fog", "cursed-fog", "Creepy, tense horror atmosphere"},
			{"LIGHT Shaders", "light-shaders", "High FPS with painting-like quality"},
			{"DrDestens Shaders", "drdestens-shaders", "High-performance shadowless PBR"},
			{"Sildur's Vibrant Shaders", "sildurs-vibrant-shaders", "Optimized classic; runs on any hardware"},
			{"Stracciatella Shaders", "stracciatella-shaders", "Vanilla-style, colored lights, high perf"},
			{"PixelCraft Shaders", "pixelcraft-shaders", "Complementary edit replicating pixel art"},
			{"Snowimagined", "snowimagined", "Turns your world into a winter wonderland"},
			{"Tea Shaders", "tea-shaders", "Vanilla Minecraft with a little extra"},
	};

	/** parent = OptiFine's shader menu (null in the dev shots: back to the game). */
	public ShaderBrowserScreen(GuiScreen parent) {
		super("Download Shaders", parent);
	}

	@Override
	protected void init() {
		super.init();
		// Packs deleted from shaderpacks/ outside the client revert to Download.
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

	/** 1.8.9: Forge's Loader.MC_VERSION (no SharedConstants in this era). */
	private static String gameVersion() {
		return net.minecraftforge.fml.common.Loader.MC_VERSION;
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
		ShaderPreviews.Preview prev = ShaderPreviews.get(slug);
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
	 * 1/guiScale, so these coordinates are window pixels). Gfx.blit draws
	 * immediately (tinted by the GL colour), so the list's scissor clip covers it.
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
		GlStateManager.enableBlend();
		GlStateManager.color(1f, 1f, 1f, d.alpha);
		d.g.blit(prev.id(), x0 + 1, y0 + 1, x1 - x0 - 2, y1 - y0 - 2, u, v, uw, vh, prev.w(), prev.h());
		GlStateManager.color(1f, 1f, 1f, 1f);
	}

	private void drawControl(OriginDeck d, final String slug, float bx, float by) {
		ShaderDownloader.State st = ShaderDownloader.state(slug);
		switch (st.status()) {
			case WORKING: {
				float cy = by + CTRL_H / 2;
				String pct = Math.round(st.progress() * 100) + "%";
				d.text("DOWNLOADING", bx, cy - 3, 10.5f, true, CAPS_TRACK, MUTE);
				d.text(pct, bx + CTRL_W - d.textW(pct, 12, true, 0), cy - 3, 12, true, 0, DIM);
				d.rect(bx, cy + 5, CTRL_W, 4, 2, 0x1FFFFFFF);
				d.rect(bx, cy + 5, CTRL_W * (float) Math.max(0.04, Math.min(1, st.progress())), 4, 2, ACC);
				break;
			}
			case DONE: {
				// Installed -> hovering offers Remove (deletes it from shaderpacks/).
				if (d.over(bx, by, CTRL_W, CTRL_H)) {
					button(d, "Remove", "@trash", bx, by, CTRL_W, CTRL_H, Style.DANGER, true, new Runnable() {
						@Override
						public void run() {
							ShaderDownloader.remove(slug);
						}
					});
				} else {
					d.rect(bx, by, CTRL_W, CTRL_H, 6, ACC_SOFT);
					String t = "INSTALLED";
					float tw = 14 + 8 + d.textW(t, 11, true, CAPS_TRACK);
					float tx = bx + (CTRL_W - tw) / 2;
					d.icon("@check", tx, by + CTRL_H / 2 - 7, 14, ACC);
					d.textMid(t, tx + 22, by + CTRL_H / 2, 11, true, CAPS_TRACK, ACC);
				}
				break;
			}
			case ERROR: {
				d.rect(bx, by, CTRL_W, CTRL_H, 6, RED_SOFT);
				d.textCenter("UNAVAILABLE", bx + CTRL_W / 2, by + CTRL_H / 2, 11, true, CAPS_TRACK, RED);
				break;
			}
			default:
				button(d, "Download", null, bx, by, CTRL_W, CTRL_H, Style.SECONDARY, true, new Runnable() {
					@Override
					public void run() {
						ShaderDownloader.start(slug, gameVersion());
					}
				});
		}
	}

	// ---- input ----

	/** Esc / back chevron: clear the search first, then close (back to the shader menu). */
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
		if (keyCode == Keyboard.KEY_BACK && !query.isEmpty()) {
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
