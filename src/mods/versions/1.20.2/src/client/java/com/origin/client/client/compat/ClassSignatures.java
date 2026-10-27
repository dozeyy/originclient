package com.origin.client.client.compat;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The signature half of CompatGates: does another mod's class still declare the exact methods Origin's
 * mixins inject into? Reads the raw class file (constant pool + method table) instead of loading the
 * class, because CompatGates runs during Mixin's config load: loading a JEI class there would load it
 * before Origin's own mixins could apply to it.
 *
 * <p>A method-name check misses two real breaks: a moved or renamed class (the mixin's target is gone)
 * and a changed descriptor (same name, new parameters: the @Inject handler no longer fits and Mixin
 * fails at apply time). Checking owner + name + full descriptor catches both before Mixin sees them.
 *
 * <p>Pure JDK and Java 8 (1.16.5 compiles this file), no ASM, no Fabric, no game class, so
 * tools/compat-gates/SignatureTests.java can run it with plain javac.
 */
public final class ClassSignatures {
	private ClassSignatures() { }

	private static final Pattern CLASS_TOKEN = Pattern.compile("L([^;]+);");

	/**
	 * Every method the class file declares, as name + descriptor ("onGuiMouseScroll(DDDD)Z").
	 * Throws IOException on anything that is not a readable class file.
	 */
	public static Set<String> methods(byte[] classFile) throws IOException {
		DataInputStream in = new DataInputStream(new ByteArrayInputStream(classFile));
		if (in.readInt() != 0xCAFEBABE) {
			throw new IOException("not a class file");
		}
		in.readUnsignedShort(); // minor
		in.readUnsignedShort(); // major: no upper bound, unlike ASM's ClassReader
		int count = in.readUnsignedShort();
		String[] utf8 = new String[count];
		for (int i = 1; i < count; i++) {
			int tag = in.readUnsignedByte();
			switch (tag) {
				case 1: utf8[i] = in.readUTF(); break; // Utf8 (class files use modified UTF-8, as readUTF does)
				case 3: case 4: skip(in, 4); break; // Integer, Float
				case 5: case 6: skip(in, 8); i++; break; // Long, Double take two slots
				case 7: case 8: case 16: case 19: case 20: skip(in, 2); break; // Class, String, MethodType, Module, Package
				case 9: case 10: case 11: case 12: case 17: case 18: skip(in, 4); break; // refs, NameAndType, (Invoke)Dynamic
				case 15: skip(in, 3); break; // MethodHandle
				default: throw new IOException("unknown constant pool tag " + tag);
			}
		}
		skip(in, 6); // access, this, super
		skip(in, 2 * in.readUnsignedShort()); // interfaces
		int fields = in.readUnsignedShort();
		for (int i = 0; i < fields; i++) {
			skip(in, 6);
			skipAttributes(in);
		}
		int methods = in.readUnsignedShort();
		Set<String> out = new HashSet<>();
		for (int i = 0; i < methods; i++) {
			in.readUnsignedShort(); // access
			String name = utf8[in.readUnsignedShort()];
			String desc = utf8[in.readUnsignedShort()];
			out.add(name + desc);
			skipAttributes(in);
		}
		return out;
	}

	/**
	 * Null when the class {@code owner} (bytes {@code ownerBytes}, null = not on the classpath) declares
	 * one of {@code alternatives} ("name(desc)ret", several joined by " or "); otherwise a short reason
	 * naming what is missing. {@code mapClass} turns each class named in a wanted descriptor (internal
	 * form, "net/minecraft/class_437") into the runtime name; the class file is already in the runtime
	 * namespace. Never throws.
	 */
	public static String missing(byte[] ownerBytes, String owner, String alternatives, UnaryOperator<String> mapClass) {
		if (ownerBytes == null) {
			return "class " + owner + " not found";
		}
		Set<String> have;
		try {
			have = methods(ownerBytes);
		} catch (Throwable t) {
			return "class " + owner + " unreadable (" + t.getMessage() + ")";
		}
		for (String alt : alternatives.trim().split("\\s+or\\s+")) {
			if (have.contains(mapDescriptor(alt.trim(), mapClass))) {
				return null;
			}
		}
		return owner + "." + alternatives.trim() + " not found";
	}

	/**
	 * Maps every class named in a signature ("name(Lnet/minecraft/class_437;I)V") through {@code mapClass}.
	 * Only the descriptor (from the first '(') is scanned: a method name with an 'L' in it ("onLoad") would
	 * otherwise swallow the first class token and leave it unmapped.
	 */
	public static String mapDescriptor(String signature, UnaryOperator<String> mapClass) {
		int open = signature.indexOf('(');
		if (open < 0) {
			return signature;
		}
		Matcher m = CLASS_TOKEN.matcher(signature);
		m.region(open, signature.length());
		StringBuffer sb = new StringBuffer();
		while (m.find()) {
			String mapped;
			try {
				mapped = mapClass.apply(m.group(1));
			} catch (Throwable t) {
				mapped = m.group(1);
			}
			m.appendReplacement(sb, Matcher.quoteReplacement("L" + (mapped == null ? m.group(1) : mapped) + ";"));
		}
		m.appendTail(sb);
		return sb.toString();
	}

	private static void skip(DataInputStream in, int n) throws IOException {
		int left = n;
		while (left > 0) {
			int s = (int) in.skip(left);
			if (s <= 0) {
				throw new IOException("truncated class file");
			}
			left -= s;
		}
	}

	private static void skipAttributes(DataInputStream in) throws IOException {
		int attrs = in.readUnsignedShort();
		for (int i = 0; i < attrs; i++) {
			in.readUnsignedShort();
			int len = in.readInt();
			if (len < 0) {
				throw new IOException("bad attribute length");
			}
			skip(in, len);
		}
	}
}
