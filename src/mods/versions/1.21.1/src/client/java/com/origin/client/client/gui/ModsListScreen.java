package com.origin.client.client.gui;

import com.origin.client.client.theme.OriginTheme;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

// A STANDALONE viewer for every mod Fabric Loader has loaded — deliberately NOT a
// card inside the Right-Shift mod menu (Will 2026-07-21): its own screen, opened by
// its own keybind (OriginKeyBindings.modsList). Read-only: name + version + id, a
// search filter, scrollable. Origin-styled through OriginUi/OriginTheme so it reads
// as part of the client. Fails soft to closing if anything throws.
public class ModsListScreen extends Screen {
	private record Row(String name, String version, String id) {
	}

	private final List<Row> all = new ArrayList<>();
	private final List<Row> filtered = new ArrayList<>();
	private String search = "";
	private boolean searchFocused = false;
	private double scroll = 0, scrollTarget = 0;
	private long lastFrameNanos = 0;
	private final long openedAt = System.currentTimeMillis();

	private static final int ROW_H = 22, GAP = 4;

	public ModsListScreen() {
		super(Component.literal("Installed Mods"));
	}

	@Override
	protected void init() {
		super.init();
		if (all.isEmpty()) {
			for (ModContainer mc : FabricLoader.getInstance().getAllMods()) {
				var meta = mc.getMetadata();
				all.add(new Row(meta.getName(), meta.getVersion().getFriendlyString(), meta.getId()));
			}
			all.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
		}
		refilter();
	}

	private void refilter() {
		filtered.clear();
		String q = search.toLowerCase(Locale.ROOT);
		for (Row r : all) {
			if (q.isEmpty() || r.name.toLowerCase(Locale.ROOT).contains(q) || r.id.toLowerCase(Locale.ROOT).contains(q)) {
				filtered.add(r);
			}
		}
		scrollTarget = 0;
	}

	private int px() {
		return (int) (width * 0.18);
	}

	private int py() {
		return (int) (height * 0.1);
	}

	private int pw() {
		return (int) (width * 0.64);
	}

	private int ph() {
		return (int) (height * 0.8);
	}

	private int listTop() {
		return py() + 62;
	}

	private int listBottom() {
		return py() + ph() - 12;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		super.render(g, mouseX, mouseY, partialTick);
		float in = (float) OriginTheme.easeOut(Math.min(1.0, (System.currentTimeMillis() - openedAt) / 160.0));

		long nanos = System.nanoTime();
		double dt = lastFrameNanos == 0 ? 16.7 : Math.min(50.0, (nanos - lastFrameNanos) / 1_000_000.0);
		lastFrameNanos = nanos;
		scroll += (scrollTarget - scroll) * Math.min(1.0, dt / 60.0);

		int x = px(), y = py(), w = pw(), h = ph();
		OriginUi.panel(g, x, y, w, h, 10, 0xC80E0E0E, OriginTheme.STROKE);

		// header
		OriginUi.logo(g, x + 24, y + 22, 22, in);
		g.drawString(font, "Installed Mods", x + 44, y + 12, OriginTheme.TEXT, false);
		String count = filtered.size() + (filtered.size() == 1 ? " mod" : " mods");
		g.drawString(font, count, x + 44, y + 24, OriginTheme.MUTED, false);

		// search box
		int sx = x + 18, sy = y + 36, sw = w - 36;
		OriginUi.panel(g, sx, sy, sw, 18, 8,
				searchFocused ? OriginTheme.BOX_FILL_HOVER : OriginTheme.BOX_FILL,
				searchFocused ? OriginTheme.BOX_BORDER_HOVER : OriginTheme.BOX_BORDER);
		OriginUi.icon(g, "@search", sx + 4, sy + 1, 15, OriginTheme.MUTED);
		if (search.isEmpty() && !searchFocused) {
			g.drawString(font, "Search mods", sx + 22, sy + 5, OriginTheme.MUTED, false);
		} else {
			g.drawString(font, search, sx + 22, sy + 5, OriginTheme.TEXT, false);
		}

		// list
		int top = listTop(), bottom = listBottom();
		int content = filtered.size() * (ROW_H + GAP) - GAP;
		int maxScroll = Math.max(0, content - (bottom - top));
		scrollTarget = Math.max(0, Math.min(maxScroll, scrollTarget));

		g.enableScissor(x, top, x + w, bottom);
		int ry = top - (int) Math.round(scroll);
		for (Row r : filtered) {
			if (ry + ROW_H >= top && ry <= bottom) {
				boolean hover = mouseX >= sx && mouseX <= sx + sw && mouseY >= ry && mouseY < ry + ROW_H
						&& mouseY >= top && mouseY <= bottom;
				OriginUi.panel(g, sx, ry, sw, ROW_H, 8,
						hover ? OriginTheme.BOX_FILL_HOVER : OriginTheme.BOX_FILL,
						hover ? OriginTheme.BOX_BORDER_HOVER : OriginTheme.BOX_BORDER);
				g.drawString(font, r.name, sx + 8, ry + 3, OriginTheme.TEXT, false);
				g.drawString(font, r.id, sx + 8, ry + 13, OriginTheme.MUTED, false);
				String ver = "v" + r.version;
				g.drawString(font, ver, sx + sw - 8 - font.width(ver), ry + 8, OriginTheme.TEXT_DIM, false);
			}
			ry += ROW_H + GAP;
		}
		g.disableScissor();

		String hint = "Esc to close";
		g.drawString(font, hint, x + w - 10 - font.width(hint), y + h - 12, OriginTheme.MUTED, false);
	}

	@Override
	public boolean mouseClicked(double mx, double my, int button) {
		int sx = px() + 18, sy = py() + 36, sw = pw() - 36;
		searchFocused = mx >= sx && mx <= sx + sw && my >= sy && my <= sy + 18;
		return super.mouseClicked(mx, my, button);
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double sxAmt, double syAmt) {
		int top = listTop(), bottom = listBottom();
		int content = filtered.size() * (ROW_H + GAP) - GAP;
		int maxScroll = Math.max(0, content - (bottom - top));
		scrollTarget = Math.max(0, Math.min(maxScroll, scrollTarget - syAmt * 30));
		return true;
	}

	@Override
	public boolean charTyped(char chr, int modifiers) {
		if (searchFocused && chr >= 32 && search.length() < 32) {
			search += chr;
			refilter();
			return true;
		}
		return super.charTyped(chr, modifiers);
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if (keyCode == GLFW.GLFW_KEY_BACKSPACE && searchFocused && !search.isEmpty()) {
			search = search.substring(0, search.length() - 1);
			refilter();
			return true;
		}
		if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
			Minecraft.getInstance().setScreen(null);
			return true;
		}
		return super.keyPressed(keyCode, scanCode, modifiers);
	}
}
