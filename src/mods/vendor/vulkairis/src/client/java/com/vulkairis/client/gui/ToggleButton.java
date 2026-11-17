package com.vulkairis.client.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

/**
 * Boolean toggle with the exact plan contract: green face plus visible {@code ON} for true,
 * red face plus visible {@code OFF} for false, both derived from the actual value on every
 * render. Failed applies roll back to the last accepted value instead of displaying ON for
 * an OFF setting.
 */
public class ToggleButton extends Button {

    /** Decides whether a requested value applies; returns the accepted state. */
    public interface ToggleApplier {
        boolean apply(boolean requested);
    }

    private boolean value;
    private final ToggleApplier applier;

    public ToggleButton(int x, int y, int width, int height, boolean initial,
                        Tooltip tooltip, ToggleApplier applier) {
        super(x, y, width, height, Component.literal(ToggleState.labelFor(initial)),
                button -> {
                }, DEFAULT_NARRATION);
        this.value = initial;
        this.applier = applier != null ? applier : requested -> requested;
        if (tooltip != null) {
            setTooltip(tooltip);
        }
        updateMessage();
    }

    @Override
    public void onPress() {
        boolean requested = !this.value;
        boolean applied = this.applier.apply(requested);
        this.value = ToggleState.resolveAfterClick(requested, applied, this.value);
        updateMessage();
    }

    /** @return the currently displayed (and accepted) value */
    public boolean value() {
        return value;
    }

    /** Re-syncs the control from an externally changed setting value. */
    public void setValue(boolean accepted) {
        this.value = accepted;
        updateMessage();
    }

    private void updateMessage() {
        setMessage(Component.literal(ToggleState.labelFor(this.value)));
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int x = getX();
        int y = getY();
        int width = getWidth();
        int height = getHeight();
        graphics.fill(x, y, x + width, y + height, ToggleState.BORDER);
        graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, ToggleState.fillFor(this.value));
        graphics.fill(x + 1, y + 1, x + width - 1, y + 2, 0x40FFFFFF);
        if (!this.active) {
            graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, 0x80000000);
        } else if (this.isHovered() || this.isFocused()) {
            graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, 0x40FFFFFF);
        }
        int textColor = this.active ? 0xFFFFFFFF : 0xFFA0A0A0;
        graphics.drawCenteredString(net.minecraft.client.Minecraft.getInstance().font, getMessage(),
                x + width / 2, y + (height - 8) / 2, textColor);
    }
}
