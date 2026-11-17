package com.vulkairis.origin;

import com.vulkairis.origin.composite.FragOutputVariant;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FragOutputVariantTest {
	private static final String SRC = """
			#version 450
			layout(location = 0) out vec4 iris_FragData0;
			layout (location=1)  out vec4 iris_FragData1;
			layout(location = 2) out vec4 iris_FragData2;
			void main() { iris_FragData0 = vec4(1); iris_FragData1 = vec4(2); iris_FragData2 = vec4(3); }
			""";

	@Test
	void countsOutputs() {
		assertEquals(3, FragOutputVariant.countOutputs(SRC));
	}

	@Test
	void keepsOneOutputAtLocationZero() {
		String v = FragOutputVariant.keepOnly(SRC, 2);
		assertEquals(1, FragOutputVariant.countOutputs(v));
		assertTrue(v.contains("layout(location = 0) out vec4 iris_FragData2;"));
		assertTrue(v.contains("\nvec4 iris_FragData0;"));
		assertTrue(v.contains("\nvec4 iris_FragData1;"));
		assertTrue(v.contains("iris_FragData1 = vec4(2)"), "writes stay, as dead stores");
	}

	@Test
	void missingLocationIsAnError() {
		assertThrows(IllegalArgumentException.class, () -> FragOutputVariant.keepOnly(SRC, 5));
	}
}
