package com.vulkairis.origin;

import com.vulkairis.origin.composite.SamplerNames;
import com.vulkairis.origin.composite.SamplerNames.Kind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SamplerNamesTest {
	private static void is(String name, Kind kind, int index) {
		var t = SamplerNames.resolve(name);
		assertEquals(kind, t.kind(), name);
		assertEquals(index, t.index(), name);
	}

	@Test
	void colortexAndAliases() {
		is("colortex0", Kind.COLORTEX, 0);
		is("colortex15", Kind.COLORTEX, 15);
		is("gcolor", Kind.COLORTEX, 0);
		is("composite", Kind.COLORTEX, 3);
		is("gaux3", Kind.COLORTEX, 6);
	}

	@Test
	void depth() {
		is("depthtex0", Kind.DEPTH, 0);
		is("depthtex2", Kind.DEPTH, 2);
		is("gdepthtex", Kind.DEPTH, 0);
	}

	@Test
	void everythingElseIsOther() {
		for (String n : new String[]{"colortex16", "colortex", "colortexA", "depthtex3", "noisetex",
				"shadowtex0", "shadowcolor0", null}) {
			is(n, Kind.OTHER, -1);
		}
	}
}
