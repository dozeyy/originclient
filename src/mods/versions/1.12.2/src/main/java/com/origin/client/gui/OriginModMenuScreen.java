package com.origin.client.gui;

import com.origin.client.hud.HudEditorScreen;
import com.origin.client.mods.ModOption;
import com.origin.client.mods.Mods;
import com.origin.client.theme.OriginTheme;
import com.origin.client.util.Gl;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The Origin mod menu — 1.12.2 port of the modern module's screen, pixel math
 * kept identical (panel at 12.5% margins, 180ms slide, 170ms page fade+scale,
 * cursor halo) so the menu looks the same on every version. All widgets are
 * drawn + hit-tested by hand; no GuiButton, because vanilla widgets can't
 * match the Origin look.
 *
 * Never-broken rule: every input/draw entry point catches Throwable, logs
 * once and bails to vanilla (screen closes) instead of crashing the client.
 */
public class OriginModMenuScreen extends GuiScreen {

    private static final String VERSION_STAMP = "Origin Client 0.4.1";
    private static final double OPEN_MS = 180, PAGE_MS = 170;

    // ---- Navigation state ----
    private int tab;                 // 0 = MODS, 1 = SETTINGS
    private String openMod;          // non-null = a mod's settings page (MODS tab only)
    private long openTime;
    private boolean closing;
    private long closeTime;
    private long pageTime;           // page transition start (grid <-> settings)

    // ---- Search state (grid + options page keep separate queries) ----
    private String search = "";
    private boolean searchFocused;
    private String optSearch = "";
    private boolean optSearchFocused;

    // ---- Smooth scroll (grid and options page each) ----
    private double scroll, scrollTarget;
    private double optScroll, optScrollTarget;

    // ---- Cursor halo (lerps toward mouse; starts on first frame's mouse) ----

    // ---- Interaction captures ----
    private String dragKey;          // option key of the slider being dragged
    private ModOption dragOpt;
    private double dragTrackX;
    private String captureKey;       // option key waiting for a keybind press
    private OriginColorPicker picker;

    /**
     * Time-stepped hover/toggle animations keyed by widget id. Value is
     * {progress 0..1, lastFrameMs}; progress moves linearly toward the target
     * and callers ease the result — same scheme the modern module uses.
     */
    private final Map<String, double[]> anims = new HashMap<String, double[]>();

    // Panel layout, recomputed every frame/click so draw + hit tests agree.
    private double px, py, pw, ph, x1;

    private boolean failed;

    private void fail(Throwable t) {
        if (!failed) System.err.println("[OriginClient] mod menu failed, closing: " + t);
        failed = true;
        mc.displayGuiScreen(null);
    }

    private void layout() {
        px = width * 0.125;
        py = height * 0.125;
        pw = width * 0.75;
        ph = height * 0.75;
        x1 = px + pw - 24; // inner right edge shared by chips/switches/rows
    }

    private double anim(String key, boolean target, double durMs) {
        double[] st = anims.get(key);
        long now = System.currentTimeMillis();
        if (st == null) { st = new double[]{target ? 1 : 0, now}; anims.put(key, st); }
        double dt = now - st[1];
        st[1] = now;
        st[0] = OriginTheme.clamp01(st[0] + (target ? dt : -dt) / durMs);
        return OriginTheme.easeOut(st[0]);
    }

    private FontRenderer font() { return mc.fontRenderer; }

    /** Trim with a real ellipsis instead of overflowing — house rule. */
    private String ellipsize(String s, int maxW) {
        FontRenderer f = font();
        if (f.getStringWidth(s) <= maxW) return s;
        String out = s;
        while (out.length() > 1 && f.getStringWidth(out + "…") > maxW)
            out = out.substring(0, out.length() - 1);
        return out + "…";
    }

    private static boolean in(int mx, int my, double x, double y, double w, double h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    /** Which settings page is showing, or null when the grid is. */
    private String settingsPage() {
        if (tab == 1) return Mods.GENERAL_ID;
        return openMod;
    }

    private void beginClose() {
        if (closing) return;
        closing = true;
        closeTime = System.currentTimeMillis();
    }

    private void switchPage(Runnable change) {
        change.run();
        pageTime = System.currentTimeMillis();
        optScroll = optScrollTarget = 0;
        optSearch = "";
        optSearchFocused = false;
        searchFocused = false;
    }

    // ------------------------------------------------------------------
    // GuiScreen lifecycle
    // ------------------------------------------------------------------

    /**
     * The menu renders at a FIXED effective scale of 2 physical pixels per
     * unit, independent of the game's GUI-scale setting. At GUI scale 4/5
     * (Auto on big monitors) the panel otherwise turns into three chunky
     * cards — the "bad quality" report. Overriding the screen's logical size
     * to displaySize/2 makes vanilla's own mouse mapping deliver coordinates
     * in our units; drawScreen adds the matching GL scale so a unit is
     * exactly 2 physical px everywhere, at every window size.
     */
    @Override
    public void setWorldAndResolution(Minecraft mcIn, int w, int h) {
        super.setWorldAndResolution(mcIn, Math.max(320, mcIn.displayWidth / 2), Math.max(240, mcIn.displayHeight / 2));
    }

    /** GL scale from our fixed-eff-2 units to the game's GUI-unit ortho. */
    private float uiToGui() {
        int sf = new ScaledResolution(mc).getScaleFactor();
        return 2f / sf;
    }

    @Override
    public void initGui() {
        openTime = System.currentTimeMillis();
        pageTime = 0; // no page transition on first open
        Keyboard.enableRepeatEvents(true); // hold-backspace in the search boxes
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
    }

    @Override
    public boolean doesGuiPauseGame() { return false; }

    @Override
    public void updateScreen() {}

    // ------------------------------------------------------------------
    // Draw
    // ------------------------------------------------------------------

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        try {
            // Everything below happens in fixed-eff-2 units (see
            // setWorldAndResolution); one scale maps them onto the GUI ortho.
            // NOTE the asymmetry in vanilla: click events arrive in screen-
            // field units (already ours), but drawScreen's mouse args come in
            // GUI units from EntityRenderer — convert them here.
            float s = uiToGui();
            int mx = (int) Math.round(mouseX / s);
            int my = (int) Math.round(mouseY / s);
            GlStateManager.pushMatrix();
            GlStateManager.scale(s, s, 1f);
            drawInner(mx, my);
            GlStateManager.popMatrix();
        } catch (Throwable t) {
            fail(t);
        }
    }

    private void drawInner(int mouseX, int mouseY) {
        layout();
        long now = System.currentTimeMillis();

        // Open/close slide progress (0 = fully off-screen below, 1 = resting).
        double p;
        if (closing) {
            p = 1.0 - OriginTheme.easeOut((now - closeTime) / OPEN_MS);
            if (p <= 0) { mc.displayGuiScreen(null); return; }
        } else {
            p = OriginTheme.easeOut((now - openTime) / OPEN_MS);
        }

        boolean backed = Mods.panelBacking();

        GlStateManager.pushMatrix();
        GlStateManager.translate(0, (float) ((1.0 - p) * (height - py)), 0);

        if (backed) OriginUi.panel(px, py, pw, ph, 10, 0xC80E0E0E, OriginTheme.STROKE);

        drawTopBar(mouseX, mouseY, backed);

        // Page content fades+scales in around the panel center on every
        // grid<->settings switch.
        double t = pageTime == 0 ? 1.0 : OriginTheme.easeOut((now - pageTime) / PAGE_MS);
        double s = 0.985 + 0.015 * t;
        double cx = px + pw / 2.0, cy = py + ph / 2.0;
        GlStateManager.pushMatrix();
        GlStateManager.translate((float) cx, (float) cy, 0);
        GlStateManager.scale((float) s, (float) s, 1f);
        GlStateManager.translate((float) -cx, (float) -cy, 0);

        String page = settingsPage();
        if (page == null) drawGrid(mouseX, mouseY, backed);
        else drawSettings(page, mouseX, mouseY, backed);

        GlStateManager.popMatrix();

        // Fade half of the page transition: veil the content area with the
        // panel tone. Only in backed mode — in clear mode a veil would darken
        // the game world behind the menu.
        if (t < 1.0 && backed)
            Gl.fill(px + 4, py + 36, px + pw - 4, py + ph - 4,
                    OriginTheme.withAlpha(0x0E0E0E, (int) ((1.0 - t) * 160)));

        String stamp = VERSION_STAMP;
        OriginUi.labelLeft(stamp, px + pw - 10 - font().getStringWidth(stamp), py + ph - 12, OriginTheme.MUTED);

        GlStateManager.popMatrix();

        // Modal color picker draws last, in un-slid screen space.
        if (picker != null) picker.draw(mouseX, mouseY);
    }

    // ---- Top bar: logo, tabs, right chips ----

    private void drawTopBar(int mouseX, int mouseY, boolean backed) {
        OriginUi.logo(px + 24, py + 20, 24, 1.0);

        double tx = px + 44;
        String[] tabs = {"MODS", "SETTINGS"};
        for (int i = 0; i < tabs.length; i++) {
            double tw = font().getStringWidth(tabs[i]) + 28;
            boolean active = tab == i;
            boolean hover = in(mouseX, mouseY, tx, py + 10, tw, 20);
            int underline = active ? OriginTheme.ACCENT : (hover ? OriginTheme.STROKE_HOVER : OriginTheme.STROKE);
            int text = active ? OriginTheme.TEXT : (hover ? OriginTheme.TEXT_DIM : OriginTheme.MUTED);
            OriginUi.label(tabs[i], tx + tw / 2.0, py + 19, text);
            Gl.fill(tx + 4, py + 28, tx + tw - 4, py + 30, underline);
            tx += tw + 8;
        }

        // Toggle chip (panel backing) sits at the inner right; HUD Editor
        // chip to its left. Icon is always white — state reads as alpha.
        double togX = x1 - 24;
        boolean togHover = in(mouseX, mouseY, togX, py + 10, 24, 20);
        OriginUi.panel(togX, py + 10, 24, 20, 6, OriginUi.chipFill(togHover, backed),
                       togHover ? OriginTheme.STROKE_HOVER : OriginTheme.STROKE);
        OriginUi.icon("blockoverlay", togX + 4, py + 12, 16, backed ? 1.0 : 0.45);

        String hudLabel = "HUD Editor";
        double hudW = font().getStringWidth(hudLabel) + 16;
        double hudX = togX - 8 - hudW;
        boolean hudHover = in(mouseX, mouseY, hudX, py + 10, hudW, 20);
        OriginUi.panel(hudX, py + 10, hudW, 20, 6, OriginUi.chipFill(hudHover, backed),
                       hudHover ? OriginTheme.STROKE_HOVER : OriginTheme.STROKE);
        OriginUi.label(hudLabel, hudX + hudW / 2.0, py + 19,
                       hudHover ? OriginTheme.TEXT : OriginTheme.TEXT_DIM);

        String shLabel = "Shaders";
        double shW = font().getStringWidth(shLabel) + 16;
        double shX = hudX - 8 - shW;
        boolean shHover = in(mouseX, mouseY, shX, py + 10, shW, 20);
        OriginUi.panel(shX, py + 10, shW, 20, 6, OriginUi.chipFill(shHover, backed),
                       shHover ? OriginTheme.STROKE_HOVER : OriginTheme.STROKE);
        OriginUi.label(shLabel, shX + shW / 2.0, py + 19,
                       shHover ? OriginTheme.TEXT : OriginTheme.TEXT_DIM);
    }

    // ---- Search box (shared visual for both pages) ----

    private void drawSearchBox(double sx, double sy, double sw, String value, boolean focused,
                               String placeholder, boolean backed) {
        OriginUi.panel(sx, sy, sw, 22, 8, OriginUi.chipFill(false, backed),
                       focused ? OriginTheme.STROKE_HOVER : OriginTheme.STROKE);
        OriginUi.icon("zoom", sx + 6, sy + 5, 12, 0.7);
        double textX = sx + 22;
        if (value.isEmpty()) {
            OriginUi.labelLeft(placeholder, textX, sy + 11, OriginTheme.MUTED);
        } else {
            String shown = ellipsize(value, (int) (sw - 32));
            OriginUi.labelLeft(shown, textX, sy + 11, OriginTheme.TEXT);
            if (focused && (System.currentTimeMillis() / 500) % 2 == 0)
                Gl.fill(textX + font().getStringWidth(shown) + 1, sy + 6,
                        textX + font().getStringWidth(shown) + 2, sy + 16, OriginTheme.TEXT);
        }
        if (value.isEmpty() && focused && (System.currentTimeMillis() / 500) % 2 == 0)
            Gl.fill(textX, sy + 6, textX + 1, sy + 16, OriginTheme.TEXT);
    }

    private double searchW() { return Math.min(300, pw - 24); }
    private double searchX() { return px + (pw - searchW()) / 2.0; }

    // ---- MODS grid ----

    private List<Mods.Def> filteredMods() {
        List<Mods.Def> out = new ArrayList<Mods.Def>();
        String q = search.trim().toLowerCase();
        for (Mods.Def d : Mods.all())
            if (q.isEmpty() || d.name.toLowerCase().contains(q)) out.add(d);
        return out;
    }

    private int gridCols() {
        int gap = 10;
        return Math.max(3, (int) ((pw - 24 + gap) / (118 + gap)));
    }

    private double gridCellW() {
        int cols = gridCols();
        return (pw - 48 - (cols - 1) * 10) / cols;
    }

    private double gridTop() { return py + 70; }
    private double gridBottom() { return py + ph - 18; }

    private double gridMaxScroll(int count) {
        int cols = gridCols();
        int rows = (count + cols - 1) / cols;
        double contentH = rows * 104 + Math.max(0, rows - 1) * 10 + 16;
        return Math.max(0, contentH - (gridBottom() - gridTop()));
    }

    private void drawGrid(int mouseX, int mouseY, boolean backed) {
        drawSearchBox(searchX(), py + 40, searchW(), search, searchFocused, "Search mods", backed);

        List<Mods.Def> defs = filteredMods();
        scrollTarget = Math.max(0, Math.min(scrollTarget, gridMaxScroll(defs.size())));
        scroll += (scrollTarget - scroll) * 0.45;

        Gl.enableScissorScaled((int) px, (int) gridTop(), (int) (px + pw), (int) gridBottom(), 2);
        int cols = gridCols();
        double cellW = gridCellW();
        boolean mouseInGrid = in(mouseX, mouseY, px, gridTop(), pw, gridBottom() - gridTop());
        for (int i = 0; i < defs.size(); i++) {
            Mods.Def def = defs.get(i);
            double cx0 = px + 24 + (i % cols) * (cellW + 10);
            double cy0 = gridTop() + 8 + (i / cols) * (104 + 10) - scroll;
            if (cy0 + 104 < gridTop() - 20 || cy0 > gridBottom() + 20) {
                anim("card:" + def.id, false, 130); // keep anim state stepping
                continue;
            }
            drawCard(def, cx0, cy0, cellW, mouseX, mouseY, mouseInGrid, backed);
        }
        Gl.disableScissor();
    }

    private void drawCard(Mods.Def def, double x, double y, double w,
                          int mouseX, int mouseY, boolean mouseInGrid, boolean backed) {
        boolean hover = mouseInGrid && in(mouseX, mouseY, x, y, w, 104);
        double hv = anim("card:" + def.id, hover, 130);

        int fill = backed
            ? OriginTheme.lerpColor(0x14FFFFFF, 0x24FFFFFF, hv)
            : OriginTheme.lerpColor(0xC8101010, 0xE0181818, hv);
        int border = OriginTheme.lerpColor(OriginTheme.STROKE, OriginTheme.STROKE_HOVER, hv);
        OriginUi.panel(x, y, w, 104, 10, fill, border);

        double ccx = x + w / 2.0;
        double iconSize = 30 + 2 * hv;
        // Icon top pinned at y+12 (exact 1.21.1) and grown DOWNWARD — matches
        // the modern card. The old y+18 + re-centering fudge sat it too low.
        OriginUi.icon(def.id, ccx - iconSize / 2.0, y + 12, iconSize, 1.0);
        OriginUi.label(ellipsize(def.name, (int) (w - 12)), ccx, y + 47, OriginTheme.TEXT);

        // OPTIONS sub-button.
        double bw = w - 24, bx = ccx - bw / 2.0;
        boolean optHover = mouseInGrid && in(mouseX, mouseY, bx, y + 61, bw, 15);
        OriginUi.panel(bx, y + 61, bw, 15, 7, optHover ? 0x1EFFFFFF : 0x10FFFFFF, 0);
        OriginUi.label("OPTIONS", ccx, y + 68, optHover ? OriginTheme.TEXT : OriginTheme.MUTED);

        // ENABLED / DISABLED state button.
        boolean on = Mods.isOn(def.id);
        boolean stHover = mouseInGrid && in(mouseX, mouseY, bx, y + 80, bw, 15);
        int stFill = on ? OriginTheme.GREEN_FILL : OriginTheme.RED_FILL;
        if (stHover) stFill = OriginTheme.withAlpha(stFill, 0x46);
        OriginUi.panel(bx, y + 80, bw, 15, 7, stFill, on ? OriginTheme.GREEN_EDGE : OriginTheme.RED_EDGE);
        OriginUi.label(on ? "ENABLED" : "DISABLED", ccx, y + 87,
                       on ? OriginTheme.GREEN_TEXT : OriginTheme.RED_TEXT);
    }

    // ---- Settings page (a mod's options, or "@general") ----

    private double rowsTop() { return py + 104; }

    private List<ModOption> visibleOptions(String id) {
        List<ModOption> out = new ArrayList<ModOption>();
        String q = optSearch.trim().toLowerCase();
        boolean searching = !q.isEmpty();
        for (ModOption o : Mods.optionsFor(id)) {
            if (searching) {
                // Search flattens the tree: headers drop out, dependsOn
                // children surface un-indented regardless of parent state.
                if (o.kind == ModOption.Kind.HEADER) continue;
                if (!o.label.toLowerCase().contains(q)) continue;
            } else {
                if (o.dependsOn != null && !Mods.bool(id, o.dependsOn)) continue;
            }
            out.add(o);
        }
        return out;
    }

    private double optionsMaxScroll(String id) {
        double h = 0;
        boolean searching = !optSearch.trim().isEmpty();
        for (ModOption o : visibleOptions(id))
            h += (o.kind == ModOption.Kind.HEADER && !searching ? 18 : 26) + 6;
        return Math.max(0, h + 10 - (gridBottom() - rowsTop()));
    }

    private void drawSettings(String id, int mouseX, int mouseY, boolean backed) {
        boolean general = Mods.GENERAL_ID.equals(id);
        Mods.Def def = general ? null : Mods.byId(id);
        String title = general ? "GENERAL" : (def != null ? def.name : id);
        String desc = general || def == null ? "" : def.description;

        // Header: back chip (mods only), icon, name + description, master switch.
        if (!general) {
            boolean backHover = in(mouseX, mouseY, px + 24, py + 46, 24, 20);
            OriginUi.panel(px + 24, py + 46, 24, 20, 6, OriginUi.chipFill(backHover, backed),
                           backHover ? OriginTheme.STROKE_HOVER : OriginTheme.STROKE);
            OriginUi.label("<", px + 36, py + 55, backHover ? OriginTheme.TEXT : OriginTheme.TEXT_DIM);
        }
        OriginUi.icon(id, px + 56, py + 43, 26, 1.0);
        OriginUi.labelLeft(title, px + 90, py + 55, OriginTheme.TEXT);
        if (!desc.isEmpty()) {
            double dx = px + 94 + font().getStringWidth(title);
            OriginUi.labelLeft(ellipsize(desc, (int) (x1 - 70 - dx)), dx, py + 55, OriginTheme.MUTED);
        }
        if (!general)
            OriginUi.switchAt(x1 - 58, py + 50, 34, anim("master:" + id, Mods.isOn(id), 170), true);

        drawSearchBox(searchX(), py + 74, searchW(), optSearch, optSearchFocused, "Search options", backed);

        optScrollTarget = Math.max(0, Math.min(optScrollTarget, optionsMaxScroll(id)));
        optScroll += (optScrollTarget - optScroll) * 0.45;

        boolean searching = !optSearch.trim().isEmpty();
        boolean mouseInRows = in(mouseX, mouseY, px, rowsTop(), pw, gridBottom() - rowsTop());
        Gl.enableScissorScaled((int) px, (int) rowsTop(), (int) (px + pw), (int) gridBottom(), 2);
        double y = rowsTop() + 4 - optScroll;
        for (ModOption o : visibleOptions(id)) {
            if (o.kind == ModOption.Kind.HEADER && !searching) {
                OriginUi.labelLeft(o.label, px + 24, y + 8, OriginTheme.MUTED);
                Gl.fill(px + 32 + font().getStringWidth(o.label), y + 8,
                        x1, y + 9, OriginTheme.STROKE);
                y += 18 + 6;
                continue;
            }
            double indent = (!searching && o.dependsOn != null) ? 16 : 0;
            drawRow(id, o, px + 24 + indent, y, x1, mouseX, mouseY, mouseInRows, backed);
            y += 26 + 6;
        }
        Gl.disableScissor();
    }

    private void drawRow(String id, ModOption o, double rx, double ry, double rx1,
                         int mouseX, int mouseY, boolean mouseInRows, boolean backed) {
        OriginUi.panel(rx, ry, rx1 - rx, 26, 8, backed ? 0x10FFFFFF : 0xC0101010, OriginTheme.STROKE);
        OriginUi.labelLeft(o.label, rx + 10, ry + 13, OriginTheme.TEXT);

        String akey = "opt:" + id + ":" + o.key;
        switch (o.kind) {
            case TOGGLE: {
                OriginUi.switchAt(rx1 - 40, ry + 5, 30, anim(akey, Mods.bool(id, o.key), 170), true);
                break;
            }
            case SLIDER: {
                double trackX = rx1 - 130;
                double v = Mods.num(id, o.key);
                double t = o.max > o.min ? (v - o.min) / (o.max - o.min) : 0;
                boolean dragging = o.key.equals(dragKey);
                OriginUi.slider(trackX, ry + 13, 90, OriginTheme.clamp01(t), dragging);
                String val = o.format(v);
                OriginUi.labelLeft(val, trackX - 10 - font().getStringWidth(val), ry + 13, OriginTheme.TEXT_DIM);
                break;
            }
            case COLOR: {
                int c = Mods.color(id, o.key);
                OriginUi.panel(rx1 - 56, ry + 5, 16, 16, 5, c, 0x40FFFFFF);
                String hex = String.format("#%06X", c & 0xFFFFFF);
                OriginUi.labelLeft(hex, rx1 - 62 - font().getStringWidth(hex), ry + 13, OriginTheme.TEXT_DIM);
                break;
            }
            case KEYBIND: {
                boolean capturing = o.key.equals(captureKey);
                boolean hover = mouseInRows && in(mouseX, mouseY, rx1 - 80, ry + 4, 70, 18);
                OriginUi.panel(rx1 - 80, ry + 4, 70, 18, 7, OriginUi.chipFill(hover, backed),
                               capturing ? OriginTheme.STROKE_HOVER : OriginTheme.STROKE);
                String label;
                if (capturing) label = "press a key";
                else {
                    int code = (int) Mods.num(id, o.key);
                    String name = code > 0 ? Keyboard.getKeyName(code) : null;
                    label = name != null ? name : "None";
                }
                OriginUi.label(ellipsize(label, 62), rx1 - 45, ry + 13,
                               capturing ? OriginTheme.TEXT_DIM : OriginTheme.TEXT);
                break;
            }
            case DROPDOWN: {
                boolean hover = mouseInRows && in(mouseX, mouseY, rx1 - 100, ry + 4, 90, 18);
                OriginUi.panel(rx1 - 100, ry + 4, 90, 18, 7, OriginUi.chipFill(hover, backed),
                               hover ? OriginTheme.STROKE_HOVER : OriginTheme.STROKE);
                OriginUi.label("< " + ellipsize(Mods.mode(id, o.key), 60) + " >", rx1 - 55, ry + 13,
                               hover ? OriginTheme.TEXT : OriginTheme.TEXT_DIM);
                break;
            }
            default:
                break;
        }
    }

    // ------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        try {
            if (picker != null) {
                picker.mouseClicked(mouseX, mouseY, mouseButton);
                if (picker.closeRequested()) picker = null;
                return; // picker is modal — nothing behind it gets the click
            }
            if (closing) return;
            layout();
            handleClick(mouseX, mouseY, mouseButton);
        } catch (Throwable t) {
            fail(t);
        }
    }

    private void handleClick(int mouseX, int mouseY, int button) {
        if (button != 0) return;
        searchFocused = false;
        optSearchFocused = false;
        captureKey = null;

        // Top bar: tabs.
        double tx = px + 44;
        String[] tabs = {"MODS", "SETTINGS"};
        for (int i = 0; i < tabs.length; i++) {
            double tw = font().getStringWidth(tabs[i]) + 28;
            if (in(mouseX, mouseY, tx, py + 10, tw, 20)) {
                if (tab != i) {
                    final int target = i;
                    switchPage(new Runnable() { public void run() { tab = target; openMod = null; } });
                }
                return;
            }
            tx += tw + 8;
        }

        // Top bar: right chips.
        double togX = x1 - 24;
        if (in(mouseX, mouseY, togX, py + 10, 24, 20)) {
            Mods.setPanelBacking(!Mods.panelBacking());
            return;
        }
        double hudW = font().getStringWidth("HUD Editor") + 16;
        double hudX = togX - 8 - hudW;
        if (in(mouseX, mouseY, hudX, py + 10, hudW, 20)) {
            mc.displayGuiScreen(new HudEditorScreen(false));
            return;
        }
        double shW = font().getStringWidth("Shaders") + 16;
        if (in(mouseX, mouseY, hudX - 8 - shW, py + 10, shW, 20)) {
            mc.displayGuiScreen(new OriginShaderBrowserScreen(this));
            return;
        }

        String page = settingsPage();
        if (page == null) clickGrid(mouseX, mouseY);
        else clickSettings(page, mouseX, mouseY);
    }

    private void clickGrid(int mouseX, int mouseY) {
        if (in(mouseX, mouseY, searchX(), py + 40, searchW(), 22)) {
            searchFocused = true;
            return;
        }
        if (!in(mouseX, mouseY, px, gridTop(), pw, gridBottom() - gridTop())) return;

        List<Mods.Def> defs = filteredMods();
        int cols = gridCols();
        double cellW = gridCellW();
        for (int i = 0; i < defs.size(); i++) {
            final Mods.Def def = defs.get(i);
            double cx0 = px + 24 + (i % cols) * (cellW + 10);
            double cy0 = gridTop() + 8 + (i / cols) * (104 + 10) - scroll;
            if (!in(mouseX, mouseY, cx0, cy0, cellW, 104)) continue;
            double bw = cellW - 24, bx = cx0 + 12;
            if (in(mouseX, mouseY, bx, cy0 + 80, bw, 15)) {
                Mods.setOn(def.id, !Mods.isOn(def.id));
            } else {
                // Card body and OPTIONS both open the settings page.
                switchPage(new Runnable() { public void run() { openMod = def.id; } });
            }
            return;
        }
    }

    private void clickSettings(final String id, int mouseX, int mouseY) {
        boolean general = Mods.GENERAL_ID.equals(id);
        if (!general && in(mouseX, mouseY, px + 24, py + 46, 24, 20)) {
            switchPage(new Runnable() { public void run() { openMod = null; } });
            return;
        }
        if (!general && in(mouseX, mouseY, x1 - 58, py + 50, 34, 34 * 8.0 / 15.0)) {
            Mods.setOn(id, !Mods.isOn(id));
            return;
        }
        if (in(mouseX, mouseY, searchX(), py + 74, searchW(), 22)) {
            optSearchFocused = true;
            return;
        }
        if (!in(mouseX, mouseY, px, rowsTop(), pw, gridBottom() - rowsTop())) return;

        boolean searching = !optSearch.trim().isEmpty();
        double y = rowsTop() + 4 - optScroll;
        for (ModOption o : visibleOptions(id)) {
            if (o.kind == ModOption.Kind.HEADER && !searching) { y += 18 + 6; continue; }
            if (in(mouseX, mouseY, px + 24, y, x1 - px - 24, 26))
                { clickRow(id, o, y, mouseX, mouseY); return; }
            y += 26 + 6;
        }
    }

    private void clickRow(String id, ModOption o, double ry, int mouseX, int mouseY) {
        switch (o.kind) {
            case TOGGLE:
                if (in(mouseX, mouseY, x1 - 40, ry + 3, 34, 20))
                    Mods.setBool(id, o.key, !Mods.bool(id, o.key));
                break;
            case SLIDER: {
                double trackX = x1 - 130;
                if (in(mouseX, mouseY, trackX - 7, ry + 4, 104, 18)) {
                    dragKey = o.key;
                    dragOpt = o;
                    dragTrackX = trackX;
                    applySlider(id, mouseX);
                }
                break;
            }
            case COLOR:
                if (in(mouseX, mouseY, x1 - 56, ry + 5, 16, 16))
                    picker = new OriginColorPicker(id, o.key);
                break;
            case KEYBIND:
                if (in(mouseX, mouseY, x1 - 80, ry + 4, 70, 18))
                    captureKey = o.key;
                break;
            case DROPDOWN:
                if (in(mouseX, mouseY, x1 - 100, ry + 4, 90, 18)) {
                    String[] cs = o.choices;
                    if (cs == null || cs.length == 0) break;
                    String cur = Mods.mode(id, o.key);
                    int idx = 0;
                    for (int i = 0; i < cs.length; i++) if (cs[i].equals(cur)) { idx = i; break; }
                    int dir = mouseX < x1 - 55 ? -1 : 1;
                    Mods.setMode(id, o.key, cs[((idx + dir) % cs.length + cs.length) % cs.length]);
                }
                break;
            default:
                break;
        }
    }

    private void applySlider(String id, int mouseX) {
        if (dragOpt == null) return;
        double t = OriginTheme.clamp01((mouseX - dragTrackX) / 90.0);
        double v = dragOpt.min + t * (dragOpt.max - dragOpt.min);
        if (dragOpt.step > 0) v = Math.round(v / dragOpt.step) * dragOpt.step;
        v = Math.max(dragOpt.min, Math.min(dragOpt.max, v));
        Mods.setNum(id, dragOpt.key, v);
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        try {
            if (picker != null) { picker.mouseDragged(mouseX, mouseY); return; }
            String page = settingsPage();
            if (dragKey != null && page != null) applySlider(page, mouseX);
        } catch (Throwable t) {
            fail(t);
        }
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        try {
            if (picker != null) { picker.mouseReleased(); return; }
            dragKey = null;
            dragOpt = null;
        } catch (Throwable t) {
            fail(t);
        }
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        try {
            if (picker != null) return;
            int wheel = Mouse.getEventDWheel();
            if (wheel == 0) return;
            double delta = wheel > 0 ? -30 : 30;
            String page = settingsPage();
            if (page == null) {
                scrollTarget = Math.max(0, Math.min(scrollTarget + delta, gridMaxScroll(filteredMods().size())));
            } else {
                optScrollTarget = Math.max(0, Math.min(optScrollTarget + delta, optionsMaxScroll(page)));
            }
        } catch (Throwable t) {
            fail(t);
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        try {
            if (picker != null) {
                picker.keyTyped(typedChar, keyCode);
                if (picker.closeRequested()) picker = null;
                return;
            }

            // Keybind capture eats the next key. Esc cancels without change.
            String page = settingsPage();
            if (captureKey != null && page != null) {
                if (keyCode != Keyboard.KEY_ESCAPE) Mods.setNum(page, captureKey, keyCode);
                captureKey = null;
                return;
            }

            // Route typing into whichever search box is focused.
            if (searchFocused || optSearchFocused) {
                boolean grid = searchFocused;
                String cur = grid ? search : optSearch;
                if (keyCode == Keyboard.KEY_BACK) {
                    if (!cur.isEmpty()) cur = cur.substring(0, cur.length() - 1);
                } else if (typedChar >= ' ' && typedChar != 127) {
                    cur = cur + typedChar;
                } else if (keyCode == Keyboard.KEY_ESCAPE || keyCode == Keyboard.KEY_RSHIFT) {
                    beginClose();
                    return;
                }
                if (grid) { search = cur; scrollTarget = 0; }
                else { optSearch = cur; optScrollTarget = 0; }
                return;
            }

            if (keyCode == Keyboard.KEY_ESCAPE || keyCode == Keyboard.KEY_RSHIFT) beginClose();
        } catch (Throwable t) {
            fail(t);
        }
    }
}
