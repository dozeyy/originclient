package com.origin.client.client.ext;

import net.minecraft.client.gui.ActiveTextCollector;
import net.minecraft.client.gui.TextAlignment;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.joml.Matrix3x2f;
import org.joml.Vector2f;

// Chat -> Right-Click to Copy: the line hit-test for 1.21.11+ (used by ChatCopyMixin).
// 1.21.11 dropped ChatComponent's screenToChatX/Y + getMessageLineIndexAt; vanilla's
// own link clicks now replay the chat layout through captureClickableText into an
// ActiveTextCollector. This collector rides the same replay, so its hits follow
// exactly where vanilla lays the lines out (chat scale + scroll through the pose).
// Each chat line arrives as accept(LEFT, 0, textY, params-with-pose, line.content()).
// The hit band is the whole row, not just the glyphs, with the row geometry read from
// ChatComponent.render / its line consumer in the 1.21.11 and 26.x bytecode:
//   entryHeight            = (int) (9 * (lineSpacing + 1))
//   entryBottomToMessageY  = round(8 * (lineSpacing + 1) - 4 * lineSpacing)
//   row = [textY + e2m - entryHeight, textY + e2m)   (9 = the font's line height)
// and the x band of the pre-1.21.11 getMessageLineIndexAt: [-4, floor(width/scale)].
public final class ChatLineFinder implements ActiveTextCollector {
	private final float mouseX;
	private final float mouseY;
	private final int maxChatX;
	private final int entryHeight;
	private final int entryBottomToMessageY;
	private Parameters defaults = new Parameters(new Matrix3x2f());
	private FormattedCharSequence hit;

	public ChatLineFinder(double mouseX, double mouseY, int maxChatX, double lineSpacing) {
		this.mouseX = (float) mouseX;
		this.mouseY = (float) mouseY;
		this.maxChatX = maxChatX;
		this.entryHeight = (int) (9.0 * (lineSpacing + 1.0));
		this.entryBottomToMessageY = (int) Math.round(8.0 * (lineSpacing + 1.0) - 4.0 * lineSpacing);
	}

	/** The content object of the line under the mouse (identity-equal to its
	 *  GuiMessage.Line.content()), or null. */
	public FormattedCharSequence hit() {
		return hit;
	}

	@Override
	public Parameters defaultParameters() {
		return defaults;
	}

	@Override
	public void defaultParameters(Parameters parameters) {
		defaults = parameters;
	}

	@Override
	public void accept(TextAlignment alignment, int x, int y, Parameters parameters, FormattedCharSequence text) {
		if (hit != null) {
			return;
		}
		Vector2f local = new Matrix3x2f(parameters.pose()).invert().transformPosition(new Vector2f(mouseX, mouseY));
		int rowBottom = y + entryBottomToMessageY;
		if (local.x >= -4.0F && local.x <= maxChatX && local.y >= rowBottom - entryHeight && local.y < rowBottom) {
			hit = text;
		}
	}

	@Override
	public void acceptScrolling(Component text, int centerX, int left, int right, int top, int bottom,
								Parameters parameters) {
		// chat never scrolls text sideways
	}
}
