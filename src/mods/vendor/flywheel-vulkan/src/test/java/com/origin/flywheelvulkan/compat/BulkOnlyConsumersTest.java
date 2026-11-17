package com.origin.flywheelvulkan.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;

import org.junit.jupiter.api.Test;

import net.createmod.catnip.render.ShadedBlockSbbBuilder;

class BulkOnlyConsumersTest {
	@Test
	void otherConsumersPassThrough() {
		Object consumer = new Object();
		assertSame(consumer, BulkOnlyConsumers.forPerVertexWrites(consumer, true));
		assertNull(BulkOnlyConsumers.forPerVertexWrites(null, false));
	}

	@Test
	void bulkOnlyBuilderIsUnwrappedPerQuadWithItsShade() {
		var sbb = new ShadedBlockSbbBuilder();
		assertSame(sbb.raw, BulkOnlyConsumers.forPerVertexWrites(sbb, true));
		assertSame(sbb.raw, BulkOnlyConsumers.forPerVertexWrites(sbb, false));
		assertSame(sbb.raw, BulkOnlyConsumers.forPerVertexWrites(sbb, false));
		assertSame(sbb.raw, BulkOnlyConsumers.forPerVertexWrites(sbb, true));
		// One unwrap per quad, each with that quad's own diffuse flag (what putBulkData records).
		assertEquals(List.of(true, false, false, true), sbb.shades);
	}

	@Test
	void subclassIsUnwrappedToo() {
		var sbb = new ShadedBlockSbbBuilder.Ponder();
		assertSame(sbb.raw, BulkOnlyConsumers.forPerVertexWrites(sbb, true));
		assertEquals(List.of(true), sbb.shades);
	}

	@Test
	void failingUnwrapLeavesTheConsumerAlone() {
		var sbb = new ShadedBlockSbbBuilder();
		sbb.failUnwrap = true;
		assertSame(sbb, BulkOnlyConsumers.forPerVertexWrites(sbb, true));
	}
}
