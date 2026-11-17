package com.vulkairis.client.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

/**
 * Status badge: a colored dot plus explicit status text. Color is always a second cue —
 * the text carries the meaning, and a red badge is only used for failed diagnostics, never
 * for a merely-OFF toggle.
 */
public class StatusBadge extends AbstractWidget {

    /** Diagnostic status; OFF toggles never use these colors as failure signals. */
    public enum Status {
        PASS(0xFF2E7D32),
        WARN(0xFFB89A00),
        FAIL(0xFFB3261E),
        NEUTRAL(0xFF6E6E6E);

        private final int color;

        Status(int color) {
            this.color = color;
        }

        public int color() {
            return color;
        }
    }

    private Status status;
    private String text;

    public StatusBadge(int x, int y, int width, int height, Status status, String text) {
        super(x, y, width, height, Component.literal(text != null ? text : ""));
        this.status = status != null ? status : Status.NEUTRAL;
        this.text = text != null ? text : "";
        this.active = false;
    }

    /** Updates both dot and text together; neither updates alone. */
    public void setStatus(Status status, String text) {
        this.status = status != null ? status : Status.NEUTRAL;
        this.text = text != null ? text : "";
        setMessage(Component.literal(this.text));
    }

    public Status status() {
        return status;
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int x = getX();
        int y = getY();
        int height = getHeight();
        int dotSize = Math.min(8, height - 4);
        int dotY = y + (height - dotSize) / 2;
        graphics.fill(x, dotY, x + dotSize, dotY + dotSize, status.color());
        graphics.fill(x, dotY, x + dotSize, dotY + 1, 0x40FFFFFF);
        graphics.drawString(net.minecraft.client.Minecraft.getInstance().font, text,
                x + dotSize + 5, y + (height - 8) / 2, 0xFFFFFFFF);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput narration) {
        defaultButtonNarrationText(narration);
    }
}
