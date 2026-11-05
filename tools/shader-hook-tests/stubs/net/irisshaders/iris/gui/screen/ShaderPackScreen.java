package net.irisshaders.iris.gui.screen;

import net.irisshaders.iris.gui.element.ShaderPackSelectionList;
import net.minecraft.client.gui.screens.Screen;

/** Iris's screen shape: its own init() makes a new list; Hide GUI is a private flag. */
public class ShaderPackScreen extends Screen {
	private ShaderPackSelectionList shaderPackList;
	private boolean guiHidden = false;

	public ShaderPackScreen(boolean noPacks) {
		shaderPackList = new ShaderPackSelectionList(noPacks);
	}

	/** Iris's own init(): Fabric's AFTER_INIT never sees it. */
	public void irisInit(boolean noPacks) {
		shaderPackList = new ShaderPackSelectionList(noPacks);
	}

	public void setGuiHidden(boolean hidden) {
		guiHidden = hidden;
	}

	public ShaderPackSelectionList list() {
		return shaderPackList;
	}
}
