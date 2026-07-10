package com.origin.client.gui;

import com.origin.client.OriginFeatures;
import com.origin.client.OriginState;
import com.origin.client.render.OriginGl;
import com.origin.client.theme.OriginTheme;
import net.minecraft.client.gui.GuiScreen;

import java.util.ArrayList;
import java.util.List;

/**
 * Origin mod menu (Right Shift) for the classic versions — a non-pausing,
 * Deskify-styled toggle list mirroring the Fabric build's OriginModMenuScreen.
 * Each row flips a feature and persists immediately.
 */
public class OriginModMenu extends GuiScreen {
    private static final int ROW_H = 24;
    private static final int PANEL_W = 240;
    private static final int HEADER_H = 40;

    private final List<Row> rows = new ArrayList<Row>();
    private int panelX, panelY, panelH;

    private static final class Row {
        final String id;
        final String label;

        Row(String id, String label) {
            this.id = id;
            this.label = label;
        }
    }

    public OriginModMenu() {
        rows.add(new Row("zoom", "Zoom"));
        rows.add(new Row("freelook", "Freelook"));
        rows.add(new Row("hud", "HUD Info"));
        rows.add(new Row("togglesprint", "Toggle Sprint"));
        rows.add(new Row("togglesneak", "Toggle Sneak"));
        rows.add(new Row("fullbright", "Fullbright"));
    }

    @Override
    public void initGui() {
        panelH = HEADER_H + rows.size() * ROW_H + 12;
        panelX = (this.width - PANEL_W) / 2;
        panelY = (this.height - panelH) / 2;
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        // Dim the game behind, then the Origin panel.
        OriginGl.fill(0, 0, this.width, this.height, 0xB3050505);
        OriginGl.fill(panelX, panelY, panelX + PANEL_W, panelY + panelH, OriginTheme.PANEL);
        OriginGl.fill(panelX, panelY, panelX + PANEL_W, panelY + 1, OriginTheme.STROKE_STRONG);

        String title = "ORIGIN";
        int tw = OriginGl.textWidth(title);
        OriginGl.text(title, panelX + (PANEL_W - tw) / 2, panelY + 14, OriginTheme.TEXT, false);

        OriginFeatures f = OriginState.features();
        int y = panelY + HEADER_H;
        for (Row row : rows) {
            boolean hover = mouseX >= panelX && mouseX <= panelX + PANEL_W
                    && mouseY >= y && mouseY < y + ROW_H;
            if (hover) {
                OriginGl.fill(panelX, y, panelX + PANEL_W, y + ROW_H, OriginTheme.PANEL_ALT);
            }
            OriginGl.text(row.label, panelX + 14, y + (ROW_H - OriginGl.fontHeight()) / 2,
                    OriginTheme.TEXT, false);
            drawPill(panelX + PANEL_W - 52, y + (ROW_H - 14) / 2, get(f, row.id));
            y += ROW_H;
        }

        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    private void drawPill(int x, int y, boolean on) {
        int w = 38, h = 14;
        int track = on ? OriginTheme.ACCENT : OriginTheme.STROKE_STRONG;
        OriginGl.fill(x, y, x + w, y + h, track);
        int knob = on ? x + w - h : x;
        OriginGl.fill(knob, y, knob + h, y + h, on ? OriginTheme.BG : OriginTheme.TEXT_DIM);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws java.io.IOException {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        if (mouseButton != 0) {
            return;
        }
        int y = panelY + HEADER_H;
        OriginFeatures f = OriginState.features();
        for (Row row : rows) {
            if (mouseX >= panelX && mouseX <= panelX + PANEL_W && mouseY >= y && mouseY < y + ROW_H) {
                set(f, row.id, !get(f, row.id));
                OriginState.save();
                return;
            }
            y += ROW_H;
        }
    }

    private boolean get(OriginFeatures f, String id) {
        if ("zoom".equals(id)) return f.zoomEnabled;
        if ("freelook".equals(id)) return f.freelookEnabled;
        if ("hud".equals(id)) return f.hudInfoEnabled;
        if ("togglesprint".equals(id)) return f.toggleSprintEnabled;
        if ("togglesneak".equals(id)) return f.toggleSneakEnabled;
        if ("fullbright".equals(id)) return f.fullbrightEnabled;
        return false;
    }

    private void set(OriginFeatures f, String id, boolean v) {
        if ("zoom".equals(id)) f.zoomEnabled = v;
        else if ("freelook".equals(id)) f.freelookEnabled = v;
        else if ("hud".equals(id)) f.hudInfoEnabled = v;
        else if ("togglesprint".equals(id)) f.toggleSprintEnabled = v;
        else if ("togglesneak".equals(id)) f.toggleSneakEnabled = v;
        else if ("fullbright".equals(id)) f.fullbrightEnabled = v;
    }
}
