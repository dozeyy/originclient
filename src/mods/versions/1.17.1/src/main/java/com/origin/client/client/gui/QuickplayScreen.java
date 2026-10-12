package com.origin.client.client.gui;

import com.origin.client.client.mods.Quickplay;
import com.origin.client.client.theme.OriginTheme;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import com.origin.client.client.gui.Gfx;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextComponent;

import java.util.List;

// Quickplay's menu: Hypixel's games on the left, the picked game's modes as a
// button grid on the right, its lobby pinned underneath. One click joins and
// closes. Same panel / button language as the Waypoints screen. Geometry is
// computed by the same helpers for drawing and clicking so hit-boxes always
// match what is drawn.
//
// 1.17.1: Screen.render(PoseStack, ...) draws only widgets (1.20.2+ draws the
// backdrop inside super.render), so the in-world backdrop is requested first
// with renderBackground -- the same backdrop 1.21.1 gets from super.render.
public class QuickplayScreen extends Screen {
	private static final int ROW_H = 18, BTN_H = 20, GAP = 6;

	// The picked game survives reopening the menu.
	private static int selected = 0;

	private double listScroll = 0, modeScroll = 0;

	public QuickplayScreen() {
		super(new TextComponent("Quickplay"));
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	// ---- geometry ----
	private int pw() {
		return Math.min(430, (int) (width * 0.8));
	}

	private int ph() {
		return Math.min(310, (int) (height * 0.86));
	}

	private int px() {
		return (width - pw()) / 2;
	}

	private int py() {
		return (height - ph()) / 2;
	}

	private int top() {
		return py() + 42;
	}

	private int bottom() {
		return py() + ph() - 12;
	}

	private int listX0() {
		return px() + 12;
	}

	private int listX1() {
		return px() + 12 + Math.max(110, pw() / 3);
	}

	private int modeX0() {
		return listX1() + 12;
	}

	private int modeX1() {
		return px() + pw() - 12;
	}

	// Mode grid area: below the game title, above the lobby button (if any).
	private int gridTop() {
		return top() + 16;
	}

	private int gridBottom(Quickplay.Game game) {
		return game.lobby() != null ? bottom() - BTN_H - GAP : bottom();
	}

	private int[] modeRect(int i, double scroll) {
		int colW = (modeX1() - modeX0() - GAP) / 2;
		int col = i % 2, row = i / 2;
		int x = modeX0() + col * (colW + GAP);
		int y = gridTop() + row * (BTN_H + GAP) - (int) Math.round(scroll);
		return new int[]{x, y, x + colW, y + BTN_H};
	}

	private int[] lobbyRect() {
		return new int[]{modeX0(), bottom() - BTN_H, modeX1(), bottom()};
	}

	private Quickplay.Game game() {
		selected = Math.max(0, Math.min(Quickplay.GAMES.size() - 1, selected));
		return Quickplay.GAMES.get(selected);
	}

	// ---- render ----
	@Override
	public void render(PoseStack poseStack, int mouseX, int mouseY, float partialTick) {
		this.renderBackground(poseStack);
		super.render(poseStack, mouseX, mouseY, partialTick);
		Gfx g = new Gfx(poseStack);
		int x = px(), y = py(), w = pw(), h = ph();
		OriginUi.panel(g, x, y, w, h, 10, 0xC80E0E0E, OriginTheme.STROKE);
		OriginUi.logo(g, x + 22, y + 20, 22, 1f);
		OriginText.drawBold(g, font, "Quickplay", x + 42, y + 10, OriginTheme.TEXT, true);
		boolean hypixel = Quickplay.onHypixel(Minecraft.getInstance());
		OriginText.draw(g, font, hypixel ? "Hypixel games" : "These games are on Hypixel -- join it to play",
				x + 42, y + 22, hypixel ? OriginTheme.TEXT_DIM : OriginTheme.WARNING, true);

		// games
		int top = top(), bottom = bottom();
		List<Quickplay.Game> games = Quickplay.GAMES;
		g.enableScissor(listX0(), top, listX1(), bottom);
		for (int i = 0; i < games.size(); i++) {
			int ry = top + i * ROW_H - (int) Math.round(listScroll);
			if (ry + ROW_H < top || ry > bottom) {
				continue;
			}
			boolean sel = i == selected;
			boolean hover = in(mouseX, mouseY, listX0(), ry, listX1(), ry + ROW_H - 2) && mouseY >= top && mouseY <= bottom;
			float hp = OriginUi.hover("quickplay:game:" + i, hover);
			int fill = sel ? OriginTheme.ACCENT_SOFT : OriginTheme.lerpColor(0x00000000, OriginTheme.BOX_FILL, hp);
			OriginUi.panel(g, listX0(), ry, listX1() - listX0(), ROW_H - 2, 5, fill,
					sel ? OriginTheme.ACCENT_BORDER : OriginTheme.lerpColor(0x00000000, OriginTheme.BOX_BORDER, hp));
			OriginText.draw(g, font, games.get(i).name(), listX0() + 8, ry + 4,
					sel ? OriginTheme.TEXT : OriginTheme.TEXT_DIM, true);
		}
		g.disableScissor();

		// modes of the picked game
		Quickplay.Game game = game();
		OriginText.drawBold(g, font, game.name().toUpperCase(java.util.Locale.ROOT), modeX0(), top + 2, OriginTheme.MUTED, true);
		int gb = gridBottom(game);
		g.enableScissor(modeX0(), gridTop(), modeX1(), gb);
		for (int i = 0; i < game.modes().size(); i++) {
			int[] r = modeRect(i, modeScroll);
			if (r[3] < gridTop() || r[1] > gb) {
				continue;
			}
			boolean hover = in(mouseX, mouseY, r[0], r[1], r[2], r[3]) && mouseY >= gridTop() && mouseY <= gb;
			button(g, r, game.modes().get(i).name(), "quickplay:mode:" + i, hover);
		}
		g.disableScissor();
		if (game.lobby() != null) {
			int[] r = lobbyRect();
			button(g, r, "Go to Lobby", "quickplay:lobby", in(mouseX, mouseY, r[0], r[1], r[2], r[3]));
		}
		String hint = "Esc";
		OriginText.draw(g, font, hint, x + w - 10 - OriginText.width(font, hint), y + 10, OriginTheme.MUTED, true);
	}

	private void button(Gfx g, int[] r, String label, String id, boolean hover) {
		float hp = OriginUi.hover(id, hover);
		OriginUi.bevelPanel(g, r[0], r[1], r[2] - r[0], r[3] - r[1], 3,
				OriginTheme.lerpColor(OriginTheme.BOX_FILL, OriginTheme.BOX_FILL_HOVER, hp),
				OriginTheme.lerpColor(OriginTheme.BOX_BORDER, 0xFFFFFFFF, hp));
		String shown = OriginText.ellipsize(font, label, r[2] - r[0] - 8);
		OriginText.draw(g, font, shown, r[0] + (r[2] - r[0] - OriginText.width(font, shown)) / 2,
				r[1] + (r[3] - r[1] - 8) / 2, OriginTheme.TEXT, true);
	}

	// ---- input ----
	@Override
	public boolean mouseClicked(double mx, double my, int button) {
		if (button != 0) {
			return super.mouseClicked(mx, my, button);
		}
		int top = top(), bottom = bottom();
		if (my >= top && my <= bottom && mx >= listX0() && mx <= listX1()) {
			int i = (int) Math.floor((my - top + listScroll) / ROW_H);
			if (i >= 0 && i < Quickplay.GAMES.size() && i != selected) {
				selected = i;
				modeScroll = 0;
			}
			return true;
		}
		Quickplay.Game game = game();
		int gb = gridBottom(game);
		if (my >= gridTop() && my <= gb) {
			for (int i = 0; i < game.modes().size(); i++) {
				int[] r = modeRect(i, modeScroll);
				if (in(mx, my, r[0], r[1], r[2], r[3])) {
					onClose();
					Quickplay.play(Minecraft.getInstance(), game, game.modes().get(i));
					return true;
				}
			}
		}
		int[] lr = lobbyRect();
		if (game.lobby() != null && in(mx, my, lr[0], lr[1], lr[2], lr[3])) {
			onClose();
			Quickplay.lobby(Minecraft.getInstance(), game);
			return true;
		}
		return super.mouseClicked(mx, my, button);
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double sy) {   // 3-arg before 1.20.2
		if (mx <= listX1()) {
			double max = Math.max(0, Quickplay.GAMES.size() * ROW_H - (bottom() - top()));
			listScroll = Math.max(0, Math.min(max, listScroll - sy * ROW_H * 2));
		} else {
			Quickplay.Game game = game();
			int rows = (game.modes().size() + 1) / 2;
			double max = Math.max(0, rows * (BTN_H + GAP) - GAP - (gridBottom(game) - gridTop()));
			modeScroll = Math.max(0, Math.min(max, modeScroll - sy * (BTN_H + GAP)));
		}
		return true;
	}

	private static boolean in(double mx, double my, int x0, int y0, int x1, int y1) {
		return mx >= x0 && mx < x1 && my >= y0 && my < y1;
	}
}
