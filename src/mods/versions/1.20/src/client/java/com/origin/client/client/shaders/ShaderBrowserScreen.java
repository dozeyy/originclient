package com.origin.client.client.shaders;

import com.origin.client.client.gui.OriginUi;
import com.origin.client.client.theme.OriginTheme;
import net.minecraft.SharedConstants;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

// The in-client shader store: a curated set of the most-downloaded shaderpacks
// (live Modrinth download ranking, 2026-07-09), each with its real preview
// image and a short description. Every listed pack is verified to have a native
// Iris build for the current game version. A Download button fills 0→100%, then
// becomes "✓ Installed" (hover → Remove, which deletes it from shaderpacks/).
// Type anywhere to filter by name/description. Downloads land in shaderpacks/
// for the RUNNING game version, so on return to Iris's shader menu (re-init
// rescans the folder) the pack is there, ready to select. This is the only
// shader-download entry point — Iris ships none.
public class ShaderBrowserScreen extends Screen {
	private final Screen parent;

	private double scroll = 0, scrollTarget = 0;
	private int maxScroll = 0;
	private long lastNanos = 0;

	private String query = "";
	private final List<Integer> view = new ArrayList<>();

	private static final int ROW_H = 46;
	private static final int PREV_W = 72, PREV_H = 40;

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

	public ShaderBrowserScreen(Screen parent) {
		super(Component.literal("Download Shaders"));
		this.parent = parent;
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

	@Override
	public void onClose() {
		// back to Iris's shader menu — its re-init rescans shaderpacks/ so any
		// pack downloaded here now shows up in the list
		minecraft.setScreen(parent);
	}

	private int pw() {
		return Math.min(460, width - 40);
	}

	private int ph() {
		return Math.min(height - 40, 340);
	}

	private int px() {
		return (width - pw()) / 2;
	}

	private int py() {
		return (height - ph()) / 2;
	}

	private int searchX() {
		return px() + 12;
	}

	private int searchY() {
		return py() + 40;
	}

	private int searchW() {
		return pw() - 24;
	}

	private static final int SEARCH_H = 20;

	private int listTop() {
		return searchY() + SEARCH_H + 10;
	}

	private int listBottom() {
		return py() + ph() - 12;
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		super.render(g, mouseX, mouseY, partialTick);

		long nanos = System.nanoTime();
		double dt = lastNanos == 0 ? 16.7 : Math.min(50.0, (nanos - lastNanos) / 1_000_000.0);
		lastNanos = nanos;
		scroll += (scrollTarget - scroll) * Math.min(1.0, dt / 60.0);

		OriginUi.panel(g, px(), py(), pw(), ph(), 12, 0xF2101010, OriginTheme.STROKE_STRONG);
		OriginUi.logo(g, px() + 18, py() + 18, 20, 1f);
		g.drawString(font, "Download Shaders", px() + 34, py() + 13, OriginTheme.TEXT, false);
		String ver = SharedConstants.getCurrentVersion().getName();
		String sub = "Auto-installs the right build for " + ver;
		g.drawString(font, sub, px() + pw() - 12 - font.width(sub), py() + 14, OriginTheme.MUTED, false);

		// search field
		OriginUi.panel(g, searchX(), searchY(), searchW(), SEARCH_H, 7, 0x1AFFFFFF, 0x55FFFFFF);
		int textY = searchY() + (SEARCH_H - font.lineHeight) / 2 + 1;
		// This box is always focused (type anywhere): a flashing caret replaces
		// the placeholder text.
		int tx = searchX() + 8;
		String shown = query;
		if (!shown.isEmpty()) {
			int maxW = searchW() - 16 - 10;
			if (font.width(shown) > maxW) {
				shown = "…" + trimLeft(shown, maxW - font.width("…"));
			}
			g.drawString(font, shown, tx, textY, OriginTheme.TEXT, false);
		}
		float caretPulse = 0.35f + 0.65f * (float) Math.abs(Math.sin(System.currentTimeMillis() / 350.0));
		int caretX = tx + font.width(shown);
		int caretCol = ((int) (0xE0 * caretPulse) << 24) | 0xE0E0E0;
		g.fill(caretX + 1, searchY() + 4, caretX + 2, searchY() + SEARCH_H - 4, caretCol);
		// result count (right of the field)
		String count = view.size() + (view.size() == 1 ? " pack" : " packs");
		g.drawString(font, count, searchX() + searchW() - font.width(count) - 8, textY, OriginTheme.MUTED, false);

		int x0 = px() + 12, x1 = px() + pw() - 12;
		int top = listTop(), bottom = listBottom();
		maxScroll = Math.max(0, view.size() * ROW_H - (bottom - top));

		if (view.isEmpty()) {
			String msg = "No shaders match \"" + query + "\"";
			g.drawString(font, msg, px() + (pw() - font.width(msg)) / 2, top + 24, OriginTheme.MUTED, false);
			return;
		}

		g.enableScissor(px(), top, px() + pw(), bottom);
		int y = top - (int) Math.round(scroll);
		for (int vi = 0; vi < view.size(); vi++) {
			if (y + ROW_H >= top && y <= bottom) {
				drawRow(g, view.get(vi), x0, x1, y, mouseX, mouseY);
			}
			y += ROW_H;
		}
		g.disableScissor();
	}

	private void drawRow(GuiGraphics g, int i, int x0, int x1, int y, int mx, int my) {
		String name = DIR[i][0], slug = DIR[i][1], desc = DIR[i][2];
		OriginUi.panel(g, x0, y, x1 - x0, ROW_H - 6, 8, 0x12FFFFFF, OriginTheme.STROKE);

		// preview thumbnail (or a placeholder tile while it loads)
		int ix = x0 + 8, iy = y + (ROW_H - 6 - PREV_H) / 2;
		OriginUi.panel(g, ix - 1, iy - 1, PREV_W + 2, PREV_H + 2, 4, 0xFF000000, OriginTheme.STROKE);
		var prev = ShaderPreviews.get(slug);
		if (prev != null) {
			g.blit(prev.id(), ix, iy, PREV_W, PREV_H, 0f, 0f, prev.w(), prev.h(), prev.w(), prev.h());
		} else {
			OriginUi.mark(g, ix + PREV_W / 2.0, iy + PREV_H / 2.0, 20, 0.25f);
		}

		// download control (right) — position first so the description knows its edge
		int bw = 92;
		int bx = x1 - 10 - bw;
		int by = y + (ROW_H - 6 - 18) / 2;

		int textX = ix + PREV_W + 12;
		g.drawString(font, name, textX, y + 11, OriginTheme.TEXT, false);
		g.drawString(font, trimRight(desc, bx - 8 - textX), textX, y + 23, OriginTheme.MUTED, false);

		var st = ShaderDownloader.state(slug);
		switch (st.status()) {
			case WORKING -> {
				OriginUi.panel(g, bx, by + 4, bw, 10, 5, 0x30FFFFFF, OriginTheme.STROKE);
				int fill = (int) (bw * Math.max(0.04, st.progress()));
				OriginUi.panel(g, bx, by + 4, fill, 10, 5, 0xE6E0E0E0, 0);
				String pct = Math.round(st.progress() * 100) + "%";
				g.drawString(font, pct, bx + (bw - font.width(pct)) / 2, by - 6, OriginTheme.TEXT_DIM, false);
			}
			case DONE -> {
				// Installed → a click here removes the pack from shaderpacks/.
				boolean hover = in(mx, my, bx, by, bx + bw, by + 18);
				OriginUi.panel(g, bx, by, bw, 18, 7, hover ? 0x33B23A33 : 0x1EB23A33,
						hover ? 0x99B23A33 : 0x66B23A33);
				String t = hover ? "Remove" : "✓ Installed";
				g.drawString(font, t, bx + (bw - font.width(t)) / 2, by + 5,
						hover ? 0xFFC77A73 : 0xFF7FA98F, false);
			}
			case ERROR -> {
				OriginUi.panel(g, bx, by, bw, 18, 7, 0x1EB23A33, 0x66B23A33);
				String t = "Unavailable";
				g.drawString(font, t, bx + (bw - font.width(t)) / 2, by + 5, 0xFFC77A73, false);
			}
			default -> {
				boolean hover = in(mx, my, bx, by, bx + bw, by + 18);
				OriginUi.panel(g, bx, by, bw, 18, 7, hover ? 0x3EFFFFFF : 0x22FFFFFF,
						hover ? 0x66FFFFFF : OriginTheme.STROKE);
				String t = "Download";
				g.drawString(font, t, bx + (bw - font.width(t)) / 2, by + 5, OriginTheme.TEXT, false);
			}
		}
	}

	/** Truncates s to fit width w, appending an ellipsis when clipped. */
	private String trimRight(String s, int w) {
		if (font.width(s) <= w) {
			return s;
		}
		String ell = "…";
		int budget = Math.max(0, w - font.width(ell));
		return font.plainSubstrByWidth(s, budget) + ell;
	}

	/** Keeps the trailing part of s that fits width w (for the search field). */
	private String trimLeft(String s, int w) {
		int i = 0;
		while (i < s.length() && font.width(s.substring(i)) > w) {
			i++;
		}
		return s.substring(i);
	}

	@Override
	public boolean mouseClicked(double mx, double my, int button) {
		if (button == 0) {
			int x1 = px() + pw() - 12;
			int top = listTop(), bottom = listBottom();
			if (my >= top && my <= bottom && !view.isEmpty()) {
				String ver = SharedConstants.getCurrentVersion().getName();
				int y = top - (int) Math.round(scroll);
				int bw = 92, bx = x1 - 10 - bw;
				for (int vi = 0; vi < view.size(); vi++) {
					int by = y + (ROW_H - 6 - 18) / 2;
					int i = view.get(vi);
					if (in(mx, my, bx, by, bx + bw, by + 18)) {
						var status = ShaderDownloader.state(DIR[i][1]).status();
						if (status == ShaderDownloader.Status.IDLE) {
							ShaderDownloader.start(DIR[i][1], ver);
							return true;
						}
						if (status == ShaderDownloader.Status.DONE) {
							ShaderDownloader.remove(DIR[i][1]);
							return true;
						}
					}
					y += ROW_H;
				}
			}
		}
		return super.mouseClicked(mx, my, button);
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double amount) {
		scrollTarget = Math.max(0, Math.min(maxScroll, scrollTarget - amount * 30));
		return true;
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
		if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
			if (!query.isEmpty()) {
				query = "";
				rebuildView();
			} else {
				onClose();
			}
			return true;
		}
		if (keyCode == GLFW.GLFW_KEY_BACKSPACE && !query.isEmpty()) {
			query = query.substring(0, query.length() - 1);
			rebuildView();
			return true;
		}
		return super.keyPressed(keyCode, scanCode, modifiers);
	}

	private static boolean in(double mx, double my, int x0, int y0, int x1, int y1) {
		return mx >= x0 && mx < x1 && my >= y0 && my < y1;
	}
}
