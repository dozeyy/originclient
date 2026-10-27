import com.origin.client.client.compat.ClassSignatures;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Proof that CompatGates' signature check (src/mods/shared/.../compat/ClassSignatures.java) can fail:
 * a wrong descriptor, a wrong return type, a renamed method, a missing class and an unreadable class
 * file must all read as MISSING, and the right signature (also through the intermediary mapping) as
 * present. Run by `python tools/compat-gates/check.py --selftest` (CI: build-check). Exit 1 on failure.
 *
 * <p>{@code --jars <mc>=<compat-gates.txt>=<jei.jar> ...} (check.py --jars, local only): every sig line
 * of each module against the JEI jar it pins.
 */
public class SignatureTests {
	static int failures = 0;

	static void check(boolean ok, String what) {
		if (!ok) {
			failures++;
		}
		System.out.println((ok ? "ok:   " : "FAIL: ") + what);
	}

	/** Stands in for a JEI handler class: the fixture the signatures are checked against. */
	@SuppressWarnings("unused")
	static class Handler {
		static final long BIG = 1L << 40; // Long + Double constants take two constant-pool slots
		static final double HALF = 0.5;

		boolean onGuiMouseScroll(double x, double y, double dx, double dy) { return dx > HALF; }

		void drawForScreen(List<?> screen, StringBuilder graphics, int mouseX, int mouseY) {
			Supplier<String> s = () -> "lambda"; // MethodHandle + InvokeDynamic constants
			s.get();
		}

		boolean renderCompactPotionIndicators() { return false; }

		void onLoad(List<?> screen) { } // an 'L' in the name must not swallow the first class token
	}

	static byte[] bytes(InputStream in) throws IOException {
		try (InputStream i = in) {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			byte[] buf = new byte[8192];
			int n;
			while ((n = i.read(buf)) > 0) {
				out.write(buf, 0, n);
			}
			return out.toByteArray();
		}
	}

	public static void main(String[] args) throws Exception {
		if (args.length > 0 && args[0].equals("--jars")) {
			jars(Arrays.copyOfRange(args, 1, args.length));
		} else {
			units();
		}
		System.out.println(failures == 0 ? "SignatureTests: all passed" : "SignatureTests: " + failures + " failed");
		System.exit(failures == 0 ? 0 : 1);
	}

	static void units() throws Exception {
		String owner = "SignatureTests$Handler";
		byte[] h = bytes(SignatureTests.class.getResourceAsStream("SignatureTests$Handler.class"));
		UnaryOperator<String> same = n -> n;

		check(ClassSignatures.missing(h, owner, "onGuiMouseScroll(DDDD)Z", same) == null,
				"exact signature is present");
		String wrongDesc = ClassSignatures.missing(h, owner, "onGuiMouseScroll(DDD)Z", same);
		check(wrongDesc != null && wrongDesc.contains("onGuiMouseScroll(DDD)Z"),
				"wrong descriptor (3 doubles, JEI 14) is missing: " + wrongDesc);
		check(ClassSignatures.missing(h, owner, "onGuiMouseScroll(DDDD)V", same) != null,
				"wrong return type is missing");
		check(ClassSignatures.missing(h, owner, "onGuiMouseScrolled(DDDD)Z", same) != null,
				"renamed method is missing");
		String noClass = ClassSignatures.missing(null, "mezz.jei.gui.input.MovedHandler", "onGuiMouseScroll(DDDD)Z", same);
		check(noClass != null && noClass.contains("mezz.jei.gui.input.MovedHandler") && noClass.contains("not found"),
				"moved/missing class is missing: " + noClass);
		check(ClassSignatures.missing(h, owner, "gone()V or onGuiMouseScroll(DDDD)Z", same) == null,
				"one matching alternative is enough");
		check(ClassSignatures.missing(h, owner, "gone()V or alsoGone()Z", same) != null,
				"no matching alternative is missing");

		// Intermediary names in the table are mapped to the running namespace before comparing.
		Map<String, String> dev = new HashMap<>();
		dev.put("net/minecraft/class_437", "java/util/List");
		dev.put("net/minecraft/class_332", "java/lang/StringBuilder");
		UnaryOperator<String> mapped = n -> dev.containsKey(n) ? dev.get(n) : n;
		String inter = "drawForScreen(Lnet/minecraft/class_437;Lnet/minecraft/class_332;II)V";
		check(ClassSignatures.missing(h, owner, inter, mapped) == null, "intermediary descriptor matches after mapping");
		check(ClassSignatures.missing(h, owner, inter, same) != null, "unmapped intermediary descriptor is missing");
		check(ClassSignatures.missing(h, owner, "onLoad(Lnet/minecraft/class_437;)V", mapped) == null,
				"a method name containing 'L' still maps its descriptor");
		check(ClassSignatures.missing(h, owner, inter, n -> { throw new IllegalStateException("no mappings"); }) != null,
				"a mapper that throws reads as missing, never crashes");

		check(ClassSignatures.missing("not a class".getBytes(StandardCharsets.US_ASCII), owner, "a()V", same) != null,
				"garbage bytes read as missing");
		check(ClassSignatures.missing(Arrays.copyOf(h, h.length / 2), owner, "renderCompactPotionIndicators()Z", same) != null,
				"truncated class file reads as missing");

		// A real JDK class file (big constant pool, Double constants, whatever its class-file version).
		InputStream math = ClassLoader.getSystemResourceAsStream("java/lang/Math.class");
		if (math != null) {
			byte[] m = bytes(math);
			check(ClassSignatures.missing(m, "java.lang.Math", "max(DD)D", same) == null, "java.lang.Math.max(DD)D present");
			check(ClassSignatures.missing(m, "java.lang.Math", "max(DD)I", same) != null, "java.lang.Math.max(DD)I missing");
		}
	}

	/** Each "mc=data=jar": every sig line in data must be declared by its owner inside jar. */
	static void jars(String[] specs) throws Exception {
		if (specs.length == 0) {
			check(false, "no module with a sig table had its JEI jar in the Gradle cache");
		}
		for (String spec : specs) {
			String[] p = spec.split("=", 3);
			int lines = 0;
			int misses = 0;
			try (ZipFile jar = new ZipFile(p[2])) {
				for (String line : Files.readAllLines(Paths.get(p[1]), StandardCharsets.UTF_8)) {
					line = line.trim();
					if (!line.startsWith("sig ")) {
						continue;
					}
					String[] f = line.split("\\|", -1);
					String owner = f[1].trim();
					ZipEntry e = jar.getEntry(owner.replace('.', '/') + ".class");
					byte[] b = e == null ? null : bytes(jar.getInputStream(e));
					String miss = ClassSignatures.missing(b, owner, f[2].trim(), n -> n);
					lines++;
					if (miss != null) {
						misses++;
						check(false, p[0] + ": " + miss);
					}
				}
			}
			if (misses == 0) {
				check(lines > 0, p[0] + ": " + lines + " sig lines match " + Paths.get(p[2]).getFileName());
			}
		}
	}
}
