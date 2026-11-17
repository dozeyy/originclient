package com.vulkairis.origin;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the boot fix without a GPU or a game: every Sodium class, field, method and constructor that Iris
 * 1.8.14-beta.1's non-mixin code links against exists in Origin's sodium-compat stand-in, and the stand-in
 * contains none of the Sodium classes Iris targets with mixins (those must stay absent so Iris's Sodium
 * mixins find no target instead of failing to apply).
 *
 * <p>Upstream VulkaIris crashed at boot with NoClassDefFoundError VertexSerializer because its nested
 * Sodium jar had no fabric.mod.json, so Fabric Loader never put it on the classpath.</p>
 */
class SodiumCompatCoverageTest {
	private static final String SODIUM = "net/caffeinemc/mods/sodium/";

	/**
	 * Iris classes that never load without Sodium: IrisConfig is only reached through Sodium's
	 * "sodium:config_api_user" entrypoint, which nothing calls when Sodium is absent.
	 */
	private static final Set<String> NEVER_LOADED_IRIS = Set.of(
			"net/irisshaders/iris/compat/sodium/config/IrisConfig");

	/**
	 * Sodium types Iris names that stay absent on purpose. Vertex is the parameter type of the chunk
	 * encoder's write method (only Sodium's chunk mesher calls it) and also an Iris mixin target.
	 */
	private static final Set<String> ABSENT_ON_PURPOSE = Set.of(
			SODIUM + "client/render/chunk/vertex/format/ChunkVertexEncoder$Vertex");

	private record Ref(String kind, String owner, String name, String desc, String from) { }

	@Test
	void everySodiumMemberIrisLinksAgainstExists() throws Exception {
		List<Ref> refs = new ArrayList<>();
		forEachIrisClass((name, reader) -> {
			// Mixins that target Sodium classes never apply (VulkairisPreLaunch drops them); every other
			// Iris mixin (e.g. MixinSpriteContents -> SpriteUtil) is merged into vanilla and must link.
			if (name.contains("/compat/sodium/mixin/") || name.endsWith("/MixinFluidRendererImpl")
					|| NEVER_LOADED_IRIS.contains(name)) {
				return;
			}
			collectRefs(name, reader, refs);
		});
		assertFalse(refs.isEmpty(), "found no Sodium references in Iris; is the Iris jar on the test classpath?");

		Set<String> missing = new TreeSet<>();
		for (Ref ref : refs) {
			if (ABSENT_ON_PURPOSE.contains(ref.owner())) {
				continue;
			}
			String problem = resolve(ref);
			if (problem != null) {
				missing.add(problem + "  (used by " + ref.from() + ")");
			}
		}
		assertTrue(missing.isEmpty(), "sodium-compat lacks:\n" + String.join("\n", missing));
	}

	@Test
	void noIrisMixinTargetExistsInTheStandIn() throws Exception {
		Set<String> targets = new TreeSet<>();
		forEachIrisClass((name, reader) -> {
			if (name.contains("/mixin/")) {
				targets.addAll(sodiumMixinTargets(reader));
			}
		});
		// javap of Iris 1.8.14-beta.1: 19 compat mixins + MixinFluidRendererImpl; two pairs share a target.
		assertTrue(targets.size() >= 15, "expected Iris's Sodium mixin targets, found " + targets);
		ClassLoader loader = getClass().getClassLoader();
		Set<String> present = new TreeSet<>();
		for (String target : targets) {
			if (loader.getResource(target + ".class") != null) {
				present.add(target);
			}
		}
		assertTrue(present.isEmpty(), "stand-in must not contain Iris mixin targets: " + present);
		assertTrue(ABSENT_ON_PURPOSE.stream().allMatch(c -> loader.getResource(c + ".class") == null));
	}

	@Test
	void serializerRegistryRemembersRegistrations() {
		var registry = net.caffeinemc.mods.sodium.api.vertex.serializer.VertexSerializerRegistry.instance();
		assertNotNull(registry);
		net.caffeinemc.mods.sodium.api.vertex.serializer.VertexSerializer s = (src, dst, n) -> { };
		var a = com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION;
		var b = com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_COLOR;
		registry.registerSerializer(a, b, s);
		assertEquals(s, registry.get(a, b));
		assertEquals(null, registry.get(b, a));
	}

	@Test
	void normalsUnpackAsSignedBytes() {
		// x = +127, y = -127 (0x81), z = 0
		int packed = 0x7F | (0x81 << 8);
		assertEquals(1.0f, net.caffeinemc.mods.sodium.api.util.NormI8.unpackX(packed), 1e-6);
		assertEquals(-1.0f, net.caffeinemc.mods.sodium.api.util.NormI8.unpackY(packed), 1e-6);
		assertEquals(0.0f, net.caffeinemc.mods.sodium.api.util.NormI8.unpackZ(packed), 1e-6);
	}

	// --- helpers ---

	private interface ClassAction {
		void accept(String internalName, ClassReader reader) throws IOException;
	}

	private static void forEachIrisClass(ClassAction action) throws IOException, URISyntaxException,
			ClassNotFoundException {
		Class<?> iris = Class.forName("net.irisshaders.iris.Iris", false,
				SodiumCompatCoverageTest.class.getClassLoader());
		Path jar = Path.of(iris.getProtectionDomain().getCodeSource().getLocation().toURI());
		try (JarFile file = new JarFile(jar.toFile())) {
			Enumeration<JarEntry> entries = file.entries();
			while (entries.hasMoreElements()) {
				JarEntry entry = entries.nextElement();
				String name = entry.getName();
				if (!name.endsWith(".class") || !name.startsWith("net/irisshaders/")) {
					continue;
				}
				try (InputStream in = file.getInputStream(entry)) {
					action.accept(name.substring(0, name.length() - 6), new ClassReader(in));
				}
			}
		}
	}

	private static void collectRefs(String from, ClassReader reader, List<Ref> out) {
		if (reader.getSuperName() != null && reader.getSuperName().startsWith(SODIUM)) {
			out.add(new Ref("class", reader.getSuperName(), null, null, from));
		}
		for (String itf : reader.getInterfaces()) {
			if (itf.startsWith(SODIUM)) {
				out.add(new Ref("class", itf, null, null, from));
			}
		}
		reader.accept(new ClassVisitor(Opcodes.ASM9) {
			@Override
			public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
				return new MethodVisitor(Opcodes.ASM9) {
					@Override
					public void visitFieldInsn(int opcode, String owner, String fname, String fdesc) {
						if (owner.startsWith(SODIUM)) {
							out.add(new Ref("field", owner, fname, fdesc, from));
						}
					}

					@Override
					public void visitMethodInsn(int opcode, String owner, String mname, String mdesc, boolean itf) {
						if (owner.startsWith(SODIUM)) {
							out.add(new Ref("method", owner, mname, mdesc, from));
						}
					}

					@Override
					public void visitTypeInsn(int opcode, String type) {
						if (type.startsWith(SODIUM)) {
							out.add(new Ref("class", type, null, null, from));
						}
					}
				};
			}
		}, ClassReader.SKIP_FRAMES);
	}

	private static Set<String> sodiumMixinTargets(ClassReader reader) {
		Set<String> targets = new HashSet<>();
		reader.accept(new ClassVisitor(Opcodes.ASM9) {
			@Override
			public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
				if (!desc.equals("Lorg/spongepowered/asm/mixin/Mixin;")) {
					return null;
				}
				return new AnnotationVisitor(Opcodes.ASM9) {
					@Override
					public AnnotationVisitor visitArray(String name) {
						return new AnnotationVisitor(Opcodes.ASM9) {
							@Override
							public void visit(String n, Object value) {
								String internal = value instanceof Type t ? t.getInternalName()
										: String.valueOf(value).replace('.', '/');
								if (internal.startsWith(SODIUM)) {
									targets.add(internal);
								}
							}
						};
					}
				};
			}

			@Override
			public FieldVisitor visitField(int a, String n, String d, String s, Object v) {
				return null;
			}
		}, ClassReader.SKIP_CODE);
		return targets;
	}

	/** Null when the reference resolves against the stand-in, else a description of what is missing. */
	private static String resolve(Ref ref) {
		Class<?> owner;
		try {
			owner = Class.forName(ref.owner().replace('/', '.'), false, SodiumCompatCoverageTest.class.getClassLoader());
		} catch (ClassNotFoundException | LinkageError e) {
			return "class " + ref.owner();
		}
		switch (ref.kind()) {
			case "class":
				return null;
			case "field":
				for (Class<?> c : hierarchy(owner)) {
					for (Field f : c.getDeclaredFields()) {
						if (f.getName().equals(ref.name()) && Type.getDescriptor(f.getType()).equals(ref.desc())) {
							return null;
						}
					}
				}
				return "field " + ref.owner() + "." + ref.name() + " " + ref.desc();
			default:
				if (ref.name().equals("<init>")) {
					for (Constructor<?> c : owner.getDeclaredConstructors()) {
						if (Type.getConstructorDescriptor(c).equals(ref.desc())) {
							return null;
						}
					}
				} else {
					for (Class<?> c : hierarchy(owner)) {
						for (Method m : c.getDeclaredMethods()) {
							if (m.getName().equals(ref.name()) && Type.getMethodDescriptor(m).equals(ref.desc())) {
								return null;
							}
						}
					}
				}
				return "method " + ref.owner() + "." + ref.name() + ref.desc();
		}
	}

	/** The class, its superclasses and every superinterface (JVM field/method resolution order). */
	private static List<Class<?>> hierarchy(Class<?> start) {
		List<Class<?>> out = new ArrayList<>();
		Deque<Class<?>> queue = new ArrayDeque<>();
		queue.add(start);
		while (!queue.isEmpty()) {
			Class<?> c = queue.poll();
			if (out.contains(c)) {
				continue;
			}
			out.add(c);
			// ArrayDeque rejects null: interfaces and Object have no superclass.
			if (c.getSuperclass() != null) {
				queue.add(c.getSuperclass());
			}
			queue.addAll(List.of(c.getInterfaces()));
		}
		return out;
	}
}
