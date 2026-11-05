package net.minecraft.network.chat;

/** Test stub: a literal text, equal by its string. */
public final class Component {
	private final String text;

	private Component(String text) {
		this.text = text;
	}

	public static Component literal(String text) {
		return new Component(text);
	}

	@Override
	public boolean equals(Object o) {
		return o instanceof Component && ((Component) o).text.equals(text);
	}

	@Override
	public int hashCode() {
		return text.hashCode();
	}
}
