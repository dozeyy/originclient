package net.irisshaders.iris.gui.element;

import net.minecraft.client.gui.components.AbstractSelectionList;

/** Iris 1.8's shape: a pinned "Download Shaders" row, shown only while there are no packs. */
public class ShaderPackSelectionList extends AbstractSelectionList<Object> {
	public static int linkOpened;

	private final PinnedEntry downloadButton = new PinnedEntry(new Runnable() {
		@Override
		public void run() {
			linkOpened++;
		}
	});

	public ShaderPackSelectionList(boolean noPacks) {
		children().add("top row");
		if (noPacks) {
			children().add(downloadButton);
		}
		children().add("pack");
	}

	/** What a click on Iris's row does. */
	public void clickDownloadRow() {
		downloadButton.onClick.run();
	}

	private static class PinnedEntry {
		private final Runnable onClick;

		PinnedEntry(Runnable onClick) {
			this.onClick = onClick;
		}
	}
}
