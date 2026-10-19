package com.origin.client.client.shaders;

import com.origin.client.client.OriginStyle;
import com.origin.client.client.gui.Gfx;
import com.origin.client.client.gui.OriginUi;
import com.origin.client.client.theme.OriginTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiButton;

// A dark, Origin-themed button injected into OptiFine's Shaders screen so the
// "Download Shaders" entry point reads as ours and stands out against OptiFine's
// widgets. Same behaviour as a vanilla button; only the paint is overridden
// (rounded dark panel + centred label).
// 1.8.9: GuiButton has no OnPress callback -- the screen's actionPerformed
// receives every click -- so the action rides on the button and MenuHooks runs
// it from GuiScreenEvent.ActionPerformedEvent.Pre (cancelling the event, so
// OptiFine's own actionPerformed never sees an unknown button). Overriding
// drawButton also keeps it out of the generic GuiButton skin.
public class OriginShaderButton extends GuiButton {
	/** An id no vanilla / OptiFine screen uses, so their id switches ignore it. */
	public static final int ID = 0x4F5253;

	private final Runnable onPress;

	public OriginShaderButton(int x, int y, int w, int h, String label, Runnable onPress) {
		super(ID, x, y, w, h, label);
		this.onPress = onPress;
	}

	/** Runs the button's action (MenuHooks, on click). */
	public void onPress() {
		if (onPress != null) {
			onPress.run();
		}
	}

	@Override
	public void drawButton(Minecraft mc, int mouseX, int mouseY) {
		// Button Style = Minecraft: this entry is a plain stock button like its neighbours.
		if (OriginStyle.vanillaButtonsOn(mc.currentScreen)) {
			super.drawButton(mc, mouseX, mouseY);
			return;
		}
		if (!visible) {
			return;
		}
		Gfx g = new Gfx();
		hovered = mouseX >= xPosition && mouseY >= yPosition
				&& mouseX < xPosition + width && mouseY < yPosition + height;
		float hp = OriginUi.hover("shader-entry:" + System.identityHashCode(this), hovered);
		OriginUi.panel(g, xPosition, yPosition, width, height, 7,
				OriginTheme.lerpColor(0xF0161616, 0xF0242424, hp),
				OriginTheme.lerpColor(OriginTheme.STROKE, OriginTheme.STROKE_STRONG, hp));
		FontRenderer font = mc.fontRendererObj;
		int tw = font.getStringWidth(displayString);
		g.drawString(font, displayString, xPosition + (width - tw) / 2, yPosition + (height - 8) / 2,
				OriginTheme.lerpColor(OriginTheme.TEXT_DIM, OriginTheme.TEXT, hp), false);
	}
}
