// Origin compatibility stub for VulkaIris (LGPL-3.0-only). Written by Origin from the call sites in
// Iris 1.8.14-beta.1 and VulkaIris; no Sodium code. Same binary name as the Sodium type Iris links
// against, so Iris loads without Sodium under VulkanMod. See sodium-compat/README.md.
package net.caffeinemc.mods.sodium.client.gl.attribute;

import net.caffeinemc.mods.sodium.client.render.vertex.VertexFormatAttribute;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A chunk vertex layout description: stride plus (attribute, index, offset) entries. Data only. */
public final class GlVertexFormat {
	public record Element(VertexFormatAttribute attribute, int index, int offset) { }

	private final int stride;
	private final List<Element> elements;

	private GlVertexFormat(int stride, List<Element> elements) {
		this.stride = stride;
		this.elements = Collections.unmodifiableList(elements);
	}

	public static Builder builder(int stride) {
		return new Builder(stride);
	}

	public int getStride() {
		return stride;
	}

	public List<Element> getElements() {
		return elements;
	}

	public static final class Builder {
		private final int stride;
		private final List<Element> elements = new ArrayList<>();

		private Builder(int stride) {
			this.stride = stride;
		}

		public Builder addElement(VertexFormatAttribute attribute, int index, int offset) {
			elements.add(new Element(attribute, index, offset));
			return this;
		}

		public GlVertexFormat build() {
			return new GlVertexFormat(stride, new ArrayList<>(elements));
		}
	}
}
