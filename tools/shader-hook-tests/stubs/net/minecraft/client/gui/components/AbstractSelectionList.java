package net.minecraft.client.gui.components;

import java.util.ArrayList;
import java.util.List;

/** Test stub: the rows a list shows. */
public abstract class AbstractSelectionList<E> {
	private final List<E> children = new ArrayList<E>();

	public final List<E> children() {
		return children;
	}
}
