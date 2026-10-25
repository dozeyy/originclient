package com.origin.client.client.packs;

import com.origin.client.client.gui.OriginTextInput;
import com.origin.client.client.gui.OriginDeck;
import com.origin.client.client.gui.OriginWindow;
import com.origin.client.client.mixin.PackSelectionScreenAccessor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.packs.PackSelectionModel;
import net.minecraft.client.gui.screens.packs.PackSelectionScreen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static com.origin.client.client.gui.OriginDeck.*;

// The Resource Pack Hub, opened from the "Browse Packs" button Origin adds to
// Minecraft's own Resource Packs screen (OriginClientMod). Same OriginWindow
// frame and row style as the shader browser.
//
//   Browse     live Modrinth search for THIS game version: search, sort,
//              resolution, categories, infinite scroll, install with progress +
//              cancel, then Enable.
//   Installed  everything in resourcepacks/: on/off, order (top wins), updates,
//              uninstall (asks first), presets, open folder.
//
// Edits are STAGED and applied when the hub closes, into the vanilla screen's
// own selection (PackSelectionScreenAccessor), so vanilla's Done performs the
// one resource reload as usual and both lists always agree. Built-in packs and
// locked ones (vanilla's default) keep their place - see PackOrder. If the
// accessor didn't apply, the hub still installs/updates/uninstalls and tells the
// player to switch packs on in vanilla's list (Mode.INSTALL_ONLY).
public class ResourcePackHubScreen extends OriginWindow {
	private enum Tab { BROWSE, INSTALLED }

	private enum Mode { ATTACHED, INSTALL_ONLY }

	private static final float ROW_GAP = 8;
	private static final float B_ROW_H = 76, PREV_W = 100, PREV_H = 56, CTRL_W = 148, CTRL_H = 32;
	private static final float I_ROW_H = 60, ICON = 40;
	private static final float FOOT_H = 22;
	private static final long DEBOUNCE_MS = 350;
	/** Start the next page this far (design px) before the end of the list. */
	private static final float PREFETCH = 600;

	// Projects this game session started installing/updating. Static so a hub
	// reopened mid-download still picks up the finished file.
	private static final Set<String> STARTED = new LinkedHashSet<>();
	private static final Map<String, String> UPDATING = new HashMap<>(); // projectId -> file it replaces
	// Selection edits waiting for the vanilla screen to finish re-initialising.
	private static PackSelectionScreen pendingFor;
	private static Runnable pendingApply;

	private final Mode mode;
	private final PackSelectionModel model;
	private Tab tab = Tab.BROWSE;

	// Browse
	private String typed = "";
	private long typedAt = 0;
	private ModrinthPacks.Sort sort = ModrinthPacks.Sort.DOWNLOADS;
	private ModrinthPacks.Category category = ModrinthPacks.Category.ALL;
	private ModrinthPacks.Resolution resolution = ModrinthPacks.Resolution.ANY;

	// Installed (staged until close)
	private final List<String> order = new ArrayList<>();      // enabled file packs, top first
	private final Set<String> removals = new LinkedHashSet<>(); // files to uninstall on close
	private boolean dirty = false;
	private String confirmDelete = null;
	private String presetName = null;                        // non-null = typing a preset name
	private String notice = null;
	private long noticeAt = 0;

	// cached per library snapshot
	private PackLibrary.Snapshot libSeen;
	private final Map<String, PackLibrary.Local> byName = new HashMap<>();

	/** parent = Minecraft's Resource Packs screen. */
	public ResourcePackHubScreen(Screen parent) {
		super(Component.literal("Resource Packs"), parent);
		PackSelectionModel m = null;
		if (parent instanceof PackSelectionScreen) {
			try {
				m = ((PackSelectionScreenAccessor) parent).originclient$model();
			} catch (Throwable t) {
				com.origin.client.OriginClient.LOGGER.warn("Origin pack hub: can't attach to the pack screen; install-only", t);
			}
		}
		model = m;
		mode = m == null ? Mode.INSTALL_ONLY : Mode.ATTACHED;
		if (model != null) {
			try {
				model.getSelected().forEach(e -> {
					if (PackOrder.managed(e.getId())) {
						order.add(e.getId().substring(5));
					}
				});
			} catch (Throwable t) {
				order.clear();
			}
		}
		ModrinthPacks.Query q = PackHub.catalog().query();
		if (q != null) {
			// reopening keeps the last search, so the list is instant
			typed = q.text();
			sort = q.sort();
			category = q.category();
			resolution = q.resolution();
		}
	}

	@Override
	protected void init() {
		super.init();
		pushQuery();
		PackHub.library().rescan(PackHub.packDir(), true);
	}

	private void pushQuery() {
		PackHub.catalog().setQuery(new ModrinthPacks.Query(typed, sort, category, resolution));
	}

	private void resetScroll() {
		scroll = 0;
		scrollTarget = 0;
	}

	private void say(String msg) {
		notice = msg;
		noticeAt = System.currentTimeMillis();
	}

	// ---- header ----

	@Override
	protected String heading() {
		return "Resource Packs";
	}

	@Override
	protected String subheading() {
		return "Modrinth packs for Minecraft " + PackHub.gameVersion();
	}

	@Override
	protected String headingIcon() {
		return "packdisplay";
	}

	@Override
	protected void drawHeaderActions(OriginDeck d, float right) {
		int n = libraryPacks().size();
		long updates = libraryPacks().stream().filter(l -> l.update() != null).count();
		String inst = updates > 0 ? "Installed · " + updates + (updates == 1 ? " update" : " updates")
				: "Installed (" + n + ")";
		float wi = buttonW(d, inst, null), wb = buttonW(d, "Browse", "@find");
		float x = right - wi;
		button(d, inst, null, x, 16, wi, 32, tab == Tab.INSTALLED ? Style.PRIMARY : Style.GHOST, true,
				() -> switchTab(Tab.INSTALLED));
		button(d, "Browse", "@find", x - 8 - wb, 16, wb, 32, tab == Tab.BROWSE ? Style.PRIMARY : Style.GHOST, true,
				() -> switchTab(Tab.BROWSE));
	}

	private void switchTab(Tab t) {
		if (tab != t) {
			tab = t;
			confirmDelete = null;
			presetName = null;
			resetScroll();
			if (t == Tab.INSTALLED) {
				PackHub.library().rescan(PackHub.packDir(), true);
			}
		}
	}

	// ---- body ----

	@Override
	protected void drawBody(OriginDeck d) {
		try {
			syncLibrary();
			absorbFinishedInstalls();
			if (!typed.equals(PackHub.catalog().query() == null ? "" : PackHub.catalog().query().text())
					&& System.currentTimeMillis() - typedAt >= DEBOUNCE_MS) {
				pushQuery();
			}
			if (tab == Tab.BROWSE) {
				drawBrowse(d);
			} else {
				drawInstalled(d);
			}
		} catch (Throwable t) {
			// a draw failure leaves the bare frame; Esc / close still work
		}
	}

	private void syncLibrary() {
		PackLibrary.Snapshot s = PackHub.library().snapshot();
		if (s != libSeen) {
			libSeen = s;
			byName.clear();
			for (PackLibrary.Local l : s.packs()) {
				byName.put(l.name(), l);
			}
			// a pack deleted outside the game can't stay staged as enabled
			if (s.scanned()) {
				order.removeIf(n -> !byName.containsKey(n));
			}
		}
	}

	private List<PackLibrary.Local> libraryPacks() {
		return PackHub.library().snapshot().packs();
	}

	/** Turns finished downloads into staged changes (an update swaps in at the same place). */
	private void absorbFinishedInstalls() {
		PackInstaller inst = PackHub.installer();
		for (String pid : new ArrayList<>(STARTED)) {
			PackInstaller.State st = inst.state(pid);
			if (st.status() == PackInstaller.Status.WORKING) {
				continue;
			}
			STARTED.remove(pid);
			String old = UPDATING.remove(pid);
			if (st.status() != PackInstaller.Status.DONE) {
				continue;
			}
			PackHub.library().rescan(PackHub.packDir(), false);
			if (old != null && st.fileName() != null && !old.equals(st.fileName())
					&& Files.isRegularFile(PackHub.packDir().resolve(st.fileName()))) {
				PackLibrary.Local was = byName.get(old);
				if (was != null) {
					PackHub.library().dropUpdate(was.sha1());
				}
				int at = order.indexOf(old);
				if (at >= 0 && mode == Mode.ATTACHED) {
					order.set(at, st.fileName());
				}
				removals.add(old);
				dirty = true;
				say("Updated - the old version is removed when you close");
			}
		}
	}

	// ================= Browse =================

	private float browseListTop() {
		return BODY_Y + 36 + 10 + 28 + 12;
	}

	private float listBottom() {
		return BODY_Y + BODY_H - FOOT_H;
	}

	private void drawBrowse(OriginDeck d) {
		float bw = 152;
		float fw = BODY_W - 2 * (bw + 8);
		field(d, BODY_X, BODY_Y, fw, 36, typed, "Search resource packs", presetName == null, "@find");
		button(d, sortLabel(), "@chev-down", BODY_X + fw + 8, BODY_Y + 2, bw, 32, Style.SECONDARY, true, () -> {
			ModrinthPacks.Sort[] all = {ModrinthPacks.Sort.DOWNLOADS, ModrinthPacks.Sort.RELEVANCE,
					ModrinthPacks.Sort.FOLLOWS, ModrinthPacks.Sort.NEWEST, ModrinthPacks.Sort.UPDATED};
			int i = 0;
			while (all[i] != sort) {
				i++;
			}
			sort = all[(i + 1) % all.length];
			resetScroll();
			pushQuery();
		});
		button(d, resolution.label, "@chev-down", BODY_X + fw + bw + 16, BODY_Y + 2, bw, 32, Style.SECONDARY, true,
				() -> {
					var all = ModrinthPacks.Resolution.values();
					resolution = all[(resolution.ordinal() + 1) % all.length];
					resetScroll();
					pushQuery();
				});

		// category chips
		float cx = BODY_X, cy = BODY_Y + 36 + 10;
		for (ModrinthPacks.Category c : ModrinthPacks.Category.values()) {
			String t = c.label.toUpperCase(Locale.ROOT);
			float w = d.textW(t, 10.5f, true, CAPS_TRACK) + 24;
			if (cx + w > BODY_X + BODY_W) {
				break;
			}
			boolean on = c == category, hov = d.over(cx, cy, w, 28);
			d.rect(cx, cy, w, 28, 14, on ? ACC_SOFT : hov ? 0x1AFFFFFF : 0x0FFFFFFF);
			d.textCenter(t, cx + w / 2, cy + 14, 10.5f, true, CAPS_TRACK, on ? ACC : hov ? TEXT : DIM);
			d.hit(cx, cy, w, 28, () -> {
				category = c;
				resetScroll();
				pushQuery();
			});
			cx += w + 6;
		}

		PackCatalog.Snapshot snap = PackHub.catalog().snapshot();
		float top = browseListTop(), bottom = listBottom();
		List<ModrinthPacks.Hit> hits = snap.hits();
		float statusH = 48;
		float contentH = hits.isEmpty() ? 0 : hits.size() * (B_ROW_H + ROW_GAP) + statusH;
		maxScroll = Math.max(0, contentH - (bottom - top));
		scrollTarget = Math.min(scrollTarget, maxScroll);
		scroll = Math.min(scroll, maxScroll);

		if (hits.isEmpty()) {
			drawBrowseEmpty(d, snap, top);
		} else {
			if (!snap.done() && !snap.loading() && snap.error() == null && scroll + (bottom - top) + PREFETCH >= contentH) {
				PackHub.catalog().loadMore();
			}
			d.clip(0, top, W, bottom - top);
			for (int i = 0; i < hits.size(); i++) {
				float y = top + i * (B_ROW_H + ROW_GAP) - (float) scroll;
				if (y + B_ROW_H < top || y > bottom) {
					continue;
				}
				drawHitRow(d, hits.get(i), y);
			}
			float sy = top + hits.size() * (B_ROW_H + ROW_GAP) - (float) scroll;
			if (sy < bottom) {
				drawListStatus(d, snap, sy + 4);
			}
			d.unclip();
			scrollbar(d, top, bottom);
		}

		String foot = "Results from Modrinth";
		if (snap.total() >= 0) {
			foot += " · " + snap.total() + (snap.total() == 1 ? " pack" : " packs") + " for " + PackHub.gameVersion();
		}
		if (snap.cached()) {
			foot += " · offline copy";
		}
		drawFooter(d, foot);
	}

	private String sortLabel() {
		// an empty-text relevance search is ranked by downloads (ModrinthPacks.searchUrl)
		return sort == ModrinthPacks.Sort.RELEVANCE && typed.isBlank() ? "Relevance" : sort.label;
	}

	private void drawBrowseEmpty(OriginDeck d, PackCatalog.Snapshot snap, float top) {
		String msg;
		if (snap.error() != null) {
			msg = snap.error();
			float bw = buttonW(d, "Retry", "@reset");
			button(d, "Retry", "@reset", W / 2 - bw / 2, top + 72, bw, 32, Style.SECONDARY, true,
					() -> PackHub.catalog().retry());
		} else if (snap.loading() || snap.total() < 0) {
			msg = "Loading packs...";
		} else {
			msg = typed.isBlank() ? "No packs in this filter for " + PackHub.gameVersion()
					: "No packs match \"" + typed + "\"";
		}
		d.textCenter(d.fit(msg, BODY_W - 48, 14, false, 0), W / 2, top + 48, 14, false, 0, MUTE);
	}

	private void drawListStatus(OriginDeck d, PackCatalog.Snapshot snap, float y) {
		float cy = y + 18;
		if (snap.error() != null) {
			float bw = buttonW(d, "Retry", "@reset");
			String m = d.fit(snap.error(), BODY_W - bw - 40, 13, false, 0);
			float mw = d.textW(m, 13, false, 0);
			float x = W / 2 - (mw + 12 + bw) / 2;
			d.textMid(m, x, cy, 13, false, 0, RED);
			button(d, "Retry", "@reset", x + mw + 12, cy - 16, bw, 32, Style.SECONDARY, true,
					() -> PackHub.catalog().retry());
		} else if (snap.done()) {
			d.textCenter("That's every pack" + (snap.total() > PackCatalog.MAX_RESULTS ? " shown - search to narrow it" : ""),
					W / 2, cy, 12, false, 0, MUTE);
		} else {
			d.textCenter("Loading more...", W / 2, cy, 12, false, 0, MUTE);
		}
	}

	private void drawHitRow(OriginDeck d, ModrinthPacks.Hit h, float y) {
		float x = BODY_X, w = BODY_W;
		card(d, x, y, w, B_ROW_H, d.over(x, y, w, B_ROW_H));

		float ix = x + 12, iy = y + (B_ROW_H - PREV_H) / 2;
		d.rect(ix, iy, PREV_W, PREV_H, 4, 0xFF0B0E0C, LINE);
		PackPreviews.Preview gallery = PackPreviews.remote(h.imageUrl());
		PackPreviews.Preview icon = gallery == null ? PackPreviews.remote(h.iconUrl()) : null;
		if (gallery != null) {
			blit(d, gallery, ix, iy, PREV_W, PREV_H, true);
		} else if (icon != null) {
			blit(d, icon, ix + (PREV_W - PREV_H) / 2 + 2, iy + 2, PREV_H - 4, PREV_H - 4, true);
		} else {
			d.logoFaint(ix + PREV_W / 2, iy + PREV_H / 2, 24, 0.25f);
		}

		float bx = x + w - 16 - CTRL_W, by = y + (B_ROW_H - CTRL_H) / 2;
		drawHitControl(d, h, bx, by);
		float pw = 64;
		float px = bx - 8 - pw;
		button(d, "Page", null, px, by, pw, CTRL_H, Style.GHOST, true, () -> openLink(h.pageUrl()));

		float tx = ix + PREV_W + 20, tw = px - 16 - tx;
		String title = d.fit(h.title(), tw, 15, true, 0);
		float titleW = d.text(title, tx, y + 26, 15, true, 0, TEXT);
		if (!h.author().isEmpty() && titleW + 40 < tw) {
			d.text(d.fit("by " + h.author(), tw - titleW - 8, 12, false, 0), tx + titleW + 8, y + 26, 12, false, 0, MUTE);
		}
		d.text(d.fit(h.description(), tw, 13, false, 0), tx, y + 45, 13, false, 0, DIM);
		d.text(d.fit(metaLine(h), tw, 11.5f, false, 0), tx, y + 63, 11.5f, false, 0, MUTE);
	}

	private static String metaLine(ModrinthPacks.Hit h) {
		StringBuilder sb = new StringBuilder(compact(h.downloads())).append(" downloads");
		if (!h.resolution().isEmpty()) {
			sb.append(" · ").append(h.resolution());
		}
		List<String> tags = new ArrayList<>();
		for (String c : h.categories()) {
			for (ModrinthPacks.Category k : ModrinthPacks.Category.values()) {
				if (c.equals(k.slug) && tags.size() < 3) {
					tags.add(k.label);
				}
			}
		}
		if (!tags.isEmpty()) {
			sb.append(" · ").append(String.join(", ", tags));
		}
		String lic = license(h.license());
		if (!lic.isEmpty()) {
			sb.append(" · ").append(lic);
		}
		return sb.toString();
	}

	private static String license(String spdx) {
		if (spdx == null || spdx.isEmpty()) {
			return "";
		}
		String s = spdx.startsWith("LicenseRef-") ? spdx.substring(11).replace('-', ' ') : spdx;
		return s.equalsIgnoreCase("Unknown") ? "" : s;
	}

	private static String compact(long n) {
		if (n >= 1_000_000) {
			return String.format(Locale.ROOT, "%.1fM", n / 1_000_000.0);
		}
		if (n >= 1_000) {
			return String.format(Locale.ROOT, "%.1fK", n / 1_000.0);
		}
		return Long.toString(n);
	}

	/** An installed file of this project that is still on disk and not being removed, or null. */
	private String installedFile(String projectId) {
		for (String f : PackHub.index().filesOfProject(projectId)) {
			if (byName.containsKey(f) && !removals.contains(f)) {
				return f;
			}
		}
		return null;
	}

	private void drawHitControl(OriginDeck d, ModrinthPacks.Hit h, float bx, float by) {
		PackInstaller.State st = PackHub.installer().state(h.projectId());
		String file = st.status() == PackInstaller.Status.DONE && st.fileName() != null && !removals.contains(st.fileName())
				? st.fileName() : installedFile(h.projectId());
		switch (st.status()) {
			case WORKING -> drawProgress(d, h.projectId(), st, bx, by, CTRL_W);
			case ERROR -> {
				boolean hov = d.over(bx, by, CTRL_W, CTRL_H);
				d.rect(bx, by, CTRL_W, CTRL_H, 6, RED_SOFT);
				String t = hov ? "TRY AGAIN" : d.fit(st.message().toUpperCase(Locale.ROOT), CTRL_W - 16, 10, true, 1.2f);
				d.textCenter(t, bx + CTRL_W / 2, by + CTRL_H / 2, 10, true, hov ? CAPS_TRACK : 1.2f, RED);
				d.hit(bx, by, CTRL_W, CTRL_H, () -> {
					PackHub.installer().clear(h.projectId());
					startInstall(h.projectId(), h.title(), h.slug(), null);
				});
			}
			default -> {
				if (file != null) {
					drawEnableControl(d, file, bx, by, CTRL_W);
				} else {
					button(d, "Install", null, bx, by, CTRL_W, CTRL_H, Style.SECONDARY, true,
							() -> startInstall(h.projectId(), h.title(), h.slug(), null));
				}
			}
		}
	}

	private void drawProgress(OriginDeck d, String projectId, PackInstaller.State st, float bx, float by, float w) {
		float cy = by + CTRL_H / 2;
		float bw = w - 30;
		boolean dl = st.message().startsWith("Downloading");
		String label = dl ? "DOWNLOADING" : st.message().replace("...", "").toUpperCase(Locale.ROOT);
		d.text(d.fit(label, bw - 36, 10.5f, true, CAPS_TRACK), bx, cy - 3, 10.5f, true, CAPS_TRACK, MUTE);
		if (dl) {
			String pct = Math.round(st.progress() * 100) + "%";
			d.text(pct, bx + bw - d.textW(pct, 12, true, 0), cy - 3, 12, true, 0, DIM);
		}
		d.rect(bx, cy + 5, bw, 4, 2, 0x1FFFFFFF);
		d.rect(bx, cy + 5, bw * (float) Math.max(0.04, Math.min(1, st.progress())), 4, 2, ACC);
		// cancel
		float cx = bx + w - 24, ccy = cy - 12;
		boolean hov = d.over(cx, ccy, 24, 24);
		d.rect(cx, ccy, 24, 24, 6, hov ? 0x1AFFFFFF : 0x0AFFFFFF);
		d.icon("@close", cx + 6, ccy + 6, 12, hov ? TEXT : DIM);
		d.hit(cx, ccy, 24, 24, () -> PackHub.installer().cancel(projectId));
	}

	/** Enabled / Enable for an installed file (install-only mode: a plain badge). */
	private void drawEnableControl(OriginDeck d, String file, float bx, float by, float w) {
		if (mode != Mode.ATTACHED) {
			badge(d, "Installed", bx, by, w);
			return;
		}
		if (order.contains(file)) {
			if (d.over(bx, by, w, CTRL_H)) {
				button(d, "Turn off", null, bx, by, w, CTRL_H, Style.SECONDARY, true, () -> {
					order.remove(file);
					dirty = true;
				});
			} else {
				badge(d, "Enabled", bx, by, w);
			}
		} else {
			button(d, "Enable", null, bx, by, w, CTRL_H, Style.PRIMARY, true, () -> {
				order.remove(file);
				order.add(0, file);
				dirty = true;
				say("Enabled on top - applies when you close");
			});
		}
	}

	private void badge(OriginDeck d, String text, float bx, float by, float w) {
		d.rect(bx, by, w, CTRL_H, 6, ACC_SOFT);
		String t = text.toUpperCase(Locale.ROOT);
		float tw = 14 + 8 + d.textW(t, 11, true, CAPS_TRACK);
		float tx = bx + (w - tw) / 2;
		d.icon("@check", tx, by + CTRL_H / 2 - 7, 14, ACC);
		d.textMid(t, tx + 22, by + CTRL_H / 2, 11, true, CAPS_TRACK, ACC);
	}

	private void startInstall(String projectId, String title, String slug, String replaces) {
		STARTED.add(projectId);
		if (replaces != null) {
			UPDATING.put(projectId, replaces);
		} else {
			// reinstalling a pack staged for removal keeps the file instead
			// (the installer recognises the identical copy and stops there)
			for (String f : PackHub.index().filesOfProject(projectId)) {
				removals.remove(f);
				PackHub.index().cancelDelete(f);
			}
		}
		PackHub.installer().start(projectId, title, slug, PackHub.gameVersion(), PackHub.gameFormat(),
				PackHub.packDir(), replaces);
	}

	private void openLink(String url) {
		try {
			Util.getPlatform().openUri(url);
		} catch (Throwable ignored) {
			// no browser: nothing to do
		}
	}

	// ================= Installed =================

	private void drawInstalled(OriginDeck d) {
		float top = BODY_Y;
		// toolbar: presets (left), open folder (right)
		float ow = buttonW(d, "Open folder", null);
		button(d, "Open folder", null, BODY_X + BODY_W - ow, top + 2, ow, 32, Style.SECONDARY, true, this::openFolder);
		if (mode == Mode.ATTACHED) {
			drawPresets(d, BODY_X, top, BODY_W - ow - 12);
		} else {
			d.text(d.fit("Turn packs on and order them in Minecraft's list (the hub couldn't attach to it this time).",
					BODY_W - ow - 24, 12.5f, false, 0), BODY_X, top + 22, 12.5f, false, 0, GOLD);
		}

		float listTop = top + 36 + 14, bottom = listBottom();
		PackLibrary.Snapshot snap = PackHub.library().snapshot();
		List<Object> rows = installedRows(snap);
		float contentH = 0;
		for (Object r : rows) {
			contentH += (r instanceof String ? 30 : I_ROW_H + 6);
		}
		maxScroll = Math.max(0, contentH - (bottom - listTop));
		scrollTarget = Math.min(scrollTarget, maxScroll);
		scroll = Math.min(scroll, maxScroll);

		if (snap.packs().isEmpty()) {
			String msg = !snap.scanned() ? "Reading your packs..." : "No resource packs yet - find some in Browse";
			d.textCenter(msg, W / 2, listTop + 48, 14, false, 0, MUTE);
		} else {
			d.clip(0, listTop, W, bottom - listTop);
			float y = listTop - (float) scroll;
			for (Object r : rows) {
				float h = r instanceof String ? 30 : I_ROW_H + 6;
				if (y + h >= listTop && y <= bottom) {
					if (r instanceof String s) {
						label(d, s, BODY_X + 4, y + 20);
					} else {
						drawLocalRow(d, (PackLibrary.Local) r, y);
					}
				}
				y += h;
			}
			d.unclip();
			scrollbar(d, listTop, bottom);
		}

		String foot;
		if (notice != null && System.currentTimeMillis() - noticeAt < 6000) {
			foot = notice;
		} else if (snap.netError() != null) {
			foot = snap.netError();
		} else if (mode == Mode.ATTACHED) {
			foot = dirty ? "Changes apply when you close · built-in packs keep their place"
					: "Top of the list wins · built-in packs keep their place";
		} else {
			foot = "Updates and removals happen when you close";
		}
		drawFooter(d, foot);
	}

	/** Section labels (String) and packs (Local), in display order. */
	private List<Object> installedRows(PackLibrary.Snapshot snap) {
		List<Object> rows = new ArrayList<>();
		if (mode == Mode.ATTACHED) {
			List<PackLibrary.Local> on = new ArrayList<>();
			for (String n : order) {
				PackLibrary.Local l = byName.get(n);
				if (l != null) {
					on.add(l);
				}
			}
			if (!on.isEmpty()) {
				rows.add("Enabled · top wins");
				rows.addAll(on);
			}
			List<Object> off = new ArrayList<>();
			for (PackLibrary.Local l : snap.packs()) {
				if (!order.contains(l.name())) {
					off.add(l);
				}
			}
			if (!off.isEmpty()) {
				rows.add("Available");
				rows.addAll(off);
			}
		} else {
			rows.addAll(snap.packs());
		}
		return rows;
	}

	private void drawLocalRow(OriginDeck d, PackLibrary.Local l, float y) {
		float x = BODY_X, w = BODY_W, h = I_ROW_H;
		boolean removing = removals.contains(l.name()) || PackHub.index().isPendingDelete(l.name());
		card(d, x, y, w, h, d.over(x, y, w, h));

		float ix = x + 12, iy = y + (h - ICON) / 2;
		d.rect(ix, iy, ICON, ICON, 4, 0xFF0B0E0C, LINE);
		Path file = PackHub.packDir().resolve(l.name());
		PackPreviews.Preview p = PackPreviews.local(file, l.folder(), l.name() + "|" + l.size() + "|" + l.sha1());
		if (p != null) {
			blit(d, p, ix + 1, iy + 1, ICON - 2, ICON - 2, false);
		} else {
			d.logoFaint(ix + ICON / 2, iy + ICON / 2, 18, 0.25f);
		}

		// controls, right to left
		float right = x + w - 14, cy = y + h / 2;
		if (removing) {
			right = drawRemovingControls(d, l, right, cy);
		} else if (l.name().equals(confirmDelete)) {
			right = drawConfirmDelete(d, l, right, cy);
		} else {
			right = drawLocalControls(d, l, right, cy);
		}

		float tx = ix + ICON + 16, tw = right - 16 - tx;
		String title = l.entry() != null && !l.entry().title().isEmpty() ? l.entry().title() : stripZip(l.name());
		d.text(d.fit(title, tw, 14, true, 0), tx, y + 25, 14, true, 0, removing ? MUTE : TEXT);
		String sub;
		int subColor = MUTE;
		if (removing) {
			sub = "Removed when you close";
		} else if (!l.info().ok()) {
			sub = l.info().problem();
			subColor = RED;
		} else if (PackHub.gameFormat() > 0 && !l.info().supports(PackHub.gameFormat())) {
			sub = "Made for a different Minecraft version - may look wrong";
			subColor = GOLD;
		} else {
			sub = (l.folder() ? "Folder" : sizeText(l.size())) + " · " + l.name();
			if (l.update() != null) {
				sub = "Update available: " + l.update().versionNumber() + " · " + sub;
				subColor = ACC;
			}
		}
		d.text(d.fit(sub, tw, 12, false, 0), tx, y + 44, 12, false, 0, subColor);
	}

	private float drawLocalControls(OriginDeck d, PackLibrary.Local l, float right, float cy) {
		// uninstall (zips only - the hub never deletes folders; Open folder does that by hand)
		if (!l.folder()) {
			float tx = right - 32;
			boolean hov = d.over(tx, cy - 16, 32, 32);
			d.rect(tx, cy - 16, 32, 32, 6, hov ? RED_SOFT : 0x0AFFFFFF);
			d.icon("@trash", tx + 9, cy - 7, 14, hov ? RED : DIM);
			d.hit(tx, cy - 16, 32, 32, () -> confirmDelete = l.name());
			right = tx - 10;
		}
		if (mode == Mode.ATTACHED) {
			boolean on = order.contains(l.name());
			float sx = right - 36;
			d.toggle("pack:" + l.name(), sx, cy - 10, on);
			d.hit(sx - 4, cy - 14, 44, 28, () -> {
				if (order.contains(l.name())) {
					order.remove(l.name());
				} else {
					order.add(0, l.name());
				}
				dirty = true;
			});
			right = sx - 12;
			if (on) {
				int at = order.indexOf(l.name());
				right = arrowButton(d, right - 26, cy, false, at < order.size() - 1, () -> move(l.name(), 1)) - 4;
				right = arrowButton(d, right - 26, cy, true, at > 0, () -> move(l.name(), -1)) - 8;
			}
		}
		PackInstaller.State st = l.entry() == null ? PackInstaller.State.IDLE : PackHub.installer().state(l.entry().projectId());
		if (st.status() == PackInstaller.Status.WORKING) {
			drawProgress(d, l.entry().projectId(), st, right - 140, cy - CTRL_H / 2, 140);
			right -= 150;
		} else if (l.update() != null) {
			float uw = buttonW(d, "Update", null);
			button(d, "Update", null, right - uw, cy - 16, uw, 32, Style.PRIMARY, true,
					() -> startInstall(l.entry().projectId(), l.entry().title(), l.entry().slug(), l.name()));
			right -= uw + 10;
		} else if (st.status() == PackInstaller.Status.ERROR) {
			String m = d.fit(st.message(), 150, 12, false, 0);
			right -= d.textW(m, 12, false, 0);
			d.textMid(m, right, cy, 12, false, 0, RED);
			right -= 10;
		}
		return right;
	}

	private float drawConfirmDelete(OriginDeck d, PackLibrary.Local l, float right, float cy) {
		float kw = buttonW(d, "Keep", null), rw = buttonW(d, "Remove", "@trash");
		button(d, "Keep", null, right - kw, cy - 16, kw, 32, Style.GHOST, true, () -> confirmDelete = null);
		right -= kw + 6;
		button(d, "Remove", "@trash", right - rw, cy - 16, rw, 32, Style.DANGER, true, () -> {
			removals.add(l.name());
			order.remove(l.name());
			confirmDelete = null;
			dirty = true;
		});
		right -= rw + 10;
		String q = "Delete this pack from your computer?";
		right -= d.textW(q, 12.5f, false, 0);
		d.textMid(q, right, cy, 12.5f, false, 0, DIM);
		return right - 8;
	}

	private float drawRemovingControls(OriginDeck d, PackLibrary.Local l, float right, float cy) {
		if (!removals.contains(l.name())) {
			return right; // already pending from an earlier session: nothing to undo here
		}
		float kw = buttonW(d, "Keep it", "@reset");
		button(d, "Keep it", "@reset", right - kw, cy - 16, kw, 32, Style.SECONDARY, true, () -> {
			removals.remove(l.name());
			dirty = true;
		});
		return right - kw - 10;
	}

	/** A small square button with a drawn up/down triangle (the icon atlas has no up chevron). */
	private float arrowButton(OriginDeck d, float x, float cy, boolean up, boolean enabled, Runnable action) {
		float y = cy - 13;
		boolean hov = enabled && d.over(x, y, 26, 26);
		d.rect(x, y, 26, 26, 6, hov ? 0x1AFFFFFF : 0x0AFFFFFF);
		int c = !enabled ? 0x30F1E9D2 : hov ? TEXT : DIM;
		// 4 stacked bars, 1.6 design px each, widening away from the tip
		for (int i = 0; i < 4; i++) {
			float rowY = up ? cy - 3.2f + i * 1.6f : cy + 1.6f - i * 1.6f;
			float half = 0.8f + i * 1.6f;
			d.fill(x + 13 - half, rowY, 2 * half, 1.6f, c);
		}
		if (enabled) {
			d.hit(x, y, 26, 26, action);
		}
		return x;
	}

	private void move(String name, int delta) {
		int at = order.indexOf(name), to = at + delta;
		if (at < 0 || to < 0 || to >= order.size()) {
			return;
		}
		order.remove(at);
		order.add(to, name);
		dirty = true;
	}

	private void drawPresets(OriginDeck d, float x, float y, float maxW) {
		float end = x + maxW;
		if (presetName != null) {
			float sw = buttonW(d, "Save", null), cw = buttonW(d, "Cancel", null);
			float fw = Math.min(320, maxW - sw - cw - 16);
			field(d, x, y, fw, 36, presetName, "Preset name (Enter to save)", true, "@plus");
			button(d, "Save", null, x + fw + 8, y + 2, sw, 32, Style.PRIMARY, !presetName.isBlank(), this::savePreset);
			button(d, "Cancel", null, x + fw + sw + 16, y + 2, cw, 32, Style.GHOST, true, () -> presetName = null);
			return;
		}
		label(d, "Presets", x, y + 22);
		float cx = x + d.textW("PRESETS", 11, true, CAPS_TRACK) + 14;
		float saveW = buttonW(d, "Save preset", "@plus");
		for (String name : PackHub.index().presetNames()) {
			String t = d.fit(name, 140, 12, true, 0);
			float w = d.textW(t, 12, true, 0) + 40;
			if (cx + w > end - saveW - 8) {
				break;
			}
			boolean hov = d.over(cx, y + 4, w, 28);
			d.rect(cx, y + 4, w, 28, 14, hov ? 0x1AFFFFFF : 0x0FFFFFFF);
			d.textMid(t, cx + 14, y + 18, 12, true, 0, hov ? TEXT : DIM);
			d.hit(cx, y + 4, w - 24, 28, () -> applyPreset(name));
			float kx = cx + w - 22;
			boolean khov = d.over(kx, y + 9, 16, 18);
			d.icon("@close", kx + 2, y + 12, 11, khov ? RED : hov ? MUTE : 0x30F1E9D2);
			d.hit(kx, y + 4, 22, 28, () -> {
				PackHub.index().deletePreset(name);
				say("Preset \"" + name + "\" deleted");
			});
			cx += w + 6;
		}
		button(d, "Save preset", "@plus", cx, y + 2, saveW, 32, Style.GHOST, true, () -> presetName = "");
	}

	private void savePreset() {
		String n = presetName == null ? "" : presetName.strip();
		if (n.isEmpty()) {
			return;
		}
		PackHub.index().savePreset(n, new ArrayList<>(order));
		presetName = null;
		say("Saved preset \"" + n + "\"");
	}

	private void applyPreset(String name) {
		List<String> want = PackHub.index().preset(name);
		order.clear();
		int missing = 0;
		for (String f : want) {
			if (byName.containsKey(f) && !removals.contains(f) && !order.contains(f)) {
				order.add(f);
			} else if (!byName.containsKey(f)) {
				missing++;
			}
		}
		dirty = true;
		say("Preset \"" + name + "\" staged" + (missing > 0 ? " · " + missing + " pack(s) no longer installed" : "")
				+ " - applies when you close");
	}

	private void openFolder() {
		try {
			Path dir = PackHub.packDir();
			Files.createDirectories(dir);
			Util.getPlatform().openFile(dir.toFile());
		} catch (Throwable ignored) {
			// no file manager: nothing to do
		}
	}

	private static String stripZip(String n) {
		return n.toLowerCase(Locale.ROOT).endsWith(".zip") ? n.substring(0, n.length() - 4) : n;
	}

	private static String sizeText(long b) {
		if (b >= 1L << 20) {
			return String.format(Locale.ROOT, "%.1f MB", b / (double) (1L << 20));
		}
		return Math.max(1, b >> 10) + " KB";
	}

	// ---- shared drawing ----

	private void drawFooter(OriginDeck d, String text) {
		d.text(d.fit(text, BODY_W, 11.5f, false, 0), BODY_X, BODY_Y + BODY_H - 4, 11.5f, false, 0, MUTE);
	}

	/**
	 * An image in the deck's physical-pixel space (see ShaderBrowserScreen.blitPreview).
	 * cover = crop to fill the box; otherwise the whole image is squeezed into it.
	 */
	private static void blit(OriginDeck d, PackPreviews.Preview p, float x, float y, float w, float h, boolean cover) {
		int x0 = Math.round(d.ox + x * d.s), y0 = Math.round(d.oy + y * d.s);
		int x1 = Math.round(d.ox + (x + w) * d.s), y1 = Math.round(d.oy + (y + h) * d.s);
		int uw = p.w(), vh = p.h();
		float u = 0, v = 0;
		if (cover) {
			float want = w / h, have = p.w() / (float) p.h();
			if (have > want) {
				uw = Math.round(p.h() * want);
				u = (p.w() - uw) / 2f;
			} else if (have < want) {
				vh = Math.round(p.w() / want);
				v = (p.h() - vh) / 2f;
			}
		}
		// 26.x: the blit names its pipeline (u/v before the size) and the fade rides
		// its trailing ARGB - there is no shader colour on the retained GUI.
		int argb = (Math.round(Math.max(0f, Math.min(1f, d.alpha)) * 255f) << 24) | 0xFFFFFF;
		d.g.blit(RenderPipelines.GUI_TEXTURED, p.id(), x0 + 1, y0 + 1, u, v, x1 - x0 - 2, y1 - y0 - 2,
				uw, vh, p.w(), p.h(), argb);
	}

	// ---- input ----

	@Override
	protected void back() {
		if (presetName != null) {
			presetName = null;
		} else if (confirmDelete != null) {
			confirmDelete = null;
		} else if (tab == Tab.BROWSE && !typed.isEmpty()) {
			typed = "";
			typedAt = 0;
			resetScroll();
			pushQuery();
		} else {
			beginClose();
		}
	}

	@Override
	public boolean charTyped(CharacterEvent event) {
		int cp = event.codepoint();
		if (cp < ' ' || cp == 127 || !Character.isValidCodePoint(cp)) {
			return super.charTyped(event);
		}
		String c = new String(Character.toChars(cp));
		if (presetName != null) {
			if (presetName.length() < 24) {
				presetName += c;
			}
			return true;
		}
		if (tab == Tab.BROWSE && typed.length() < 64) {
			typed += c;
			typedAt = System.currentTimeMillis();
			resetScroll();
			return true;
		}
		return super.charTyped(event);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		int keyCode = event.key();
		if (presetName != null) {
			if (keyCode == GLFW.GLFW_KEY_BACKSPACE && !presetName.isEmpty()) {
				presetName = OriginTextInput.dropLast(presetName);
				return true;
			}
			if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
				savePreset();
				return true;
			}
		} else if (tab == Tab.BROWSE && keyCode == GLFW.GLFW_KEY_BACKSPACE && !typed.isEmpty()) {
			typed = OriginTextInput.dropLast(typed);
			typedAt = System.currentTimeMillis();
			resetScroll();
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public void onClose() {
		beginClose();
	}

	// ---- applying the staged edits ----

	/**
	 * Leaving the hub. Uninstalls happen now (a pack Minecraft still has loaded is
	 * queued and deleted after the next resource reload - Windows locks it until
	 * then). Enable/order edits wait for the vanilla screen's re-init (its reload()
	 * rescans the folder), then land in its selection via onPackScreenInit.
	 */
	@Override
	public void removed() {
		super.removed();
		if (!dirty) {
			return;
		}
		dirty = false;
		Path dir = PackHub.packDir();
		PackIndex idx = PackHub.index();
		for (String name : removals) {
			try {
				Files.deleteIfExists(PackFiles.inside(dir, name));
				idx.forget(name);
			} catch (IOException e) {
				idx.markForDelete(name);
			}
		}
		PackHub.library().rescan(dir, false);
		if (mode == Mode.ATTACHED && returnTo instanceof PackSelectionScreen parent) {
			List<String> want = new ArrayList<>(order);
			want.removeAll(removals);
			pendingFor = parent;
			pendingApply = () -> applySelection(parent, model, want);
		}
	}

	/** Called by OriginClientMod after the Resource Packs screen (re)initialises. */
	public static void onPackScreenInit(Screen screen) {
		if (pendingApply != null && screen != pendingFor) {
			// a different pack screen: the one the edits were for is gone
			pendingApply = null;
			pendingFor = null;
		}
		if (pendingApply != null) {
			Runnable r = pendingApply;
			pendingApply = null;
			pendingFor = null;
			try {
				r.run();
			} catch (Throwable t) {
				com.origin.client.OriginClient.LOGGER.warn("Origin pack hub: couldn't apply pack changes", t);
			}
		}
	}

	private static void applySelection(PackSelectionScreen parent, PackSelectionModel model, List<String> filesTopFirst) {
		((PackSelectionScreenAccessor) parent).originclient$reload();
		List<PackSelectionModel.Entry> selected = model.getSelected().toList();
		Set<String> known = new HashSet<>();
		model.getSelected().forEach(e -> known.add(e.getId()));
		model.getUnselected().forEach(e -> known.add(e.getId()));

		List<PackOrder.Slot> current = new ArrayList<>();
		Set<String> pinned = new HashSet<>();
		for (PackSelectionModel.Entry e : selected) {
			boolean pin = e.isFixedPosition() || e.isRequired();
			current.add(new PackOrder.Slot(e.getId(), pin));
			if (pin) {
				pinned.add(e.getId());
			}
		}
		List<String> files = new ArrayList<>();
		for (String f : filesTopFirst) {
			if (known.contains("file/" + f)) {
				files.add("file/" + f);
			}
		}
		List<String> next = PackOrder.apply(current, files);
		if (next.equals(current.stream().map(PackOrder.Slot::id).toList())) {
			return;
		}
		// Rebuild the unpinned part: take every unpinned pack off, then put them
		// back bottom-first - each select() lands on top (below top-locked packs).
		for (PackSelectionModel.Entry e : selected) {
			if (!pinned.contains(e.getId())) {
				e.unselect();
			}
		}
		for (int i = next.size() - 1; i >= 0; i--) {
			String id = next.get(i);
			if (pinned.contains(id)) {
				continue;
			}
			model.getUnselected().filter(e -> e.getId().equals(id)).findFirst()
					.ifPresent(PackSelectionModel.Entry::select);
		}
	}
}
