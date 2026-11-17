// Origin compatibility stub for VulkaIris (LGPL-3.0-only). Written by Origin from the call sites in
// Iris 1.8.14-beta.1 and VulkaIris; no Sodium code. Same binary name as the Sodium type Iris links
// against, so Iris loads without Sodium under VulkanMod. See sodium-compat/README.md.
package net.caffeinemc.mods.sodium.client.gl.attribute;

/** A vertex attribute component type: its GL enum value and size in bytes. */
public final class GlVertexAttributeFormat {
	public static final GlVertexAttributeFormat FLOAT = new GlVertexAttributeFormat(0x1406, 4);
	public static final GlVertexAttributeFormat INT = new GlVertexAttributeFormat(0x1404, 4);
	public static final GlVertexAttributeFormat UNSIGNED_INT = new GlVertexAttributeFormat(0x1405, 4);
	public static final GlVertexAttributeFormat SHORT = new GlVertexAttributeFormat(0x1402, 2);
	public static final GlVertexAttributeFormat UNSIGNED_SHORT = new GlVertexAttributeFormat(0x1403, 2);
	public static final GlVertexAttributeFormat BYTE = new GlVertexAttributeFormat(0x1400, 1);
	public static final GlVertexAttributeFormat UNSIGNED_BYTE = new GlVertexAttributeFormat(0x1401, 1);

	private final int glId;
	private final int size;

	private GlVertexAttributeFormat(int glId, int size) {
		this.glId = glId;
		this.size = size;
	}

	public int typeId() {
		return glId;
	}

	public int size() {
		return size;
	}
}
