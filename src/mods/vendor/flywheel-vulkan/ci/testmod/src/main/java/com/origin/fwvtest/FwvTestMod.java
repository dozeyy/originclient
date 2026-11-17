package com.origin.fwvtest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.engine_room.flywheel.api.visualization.VisualizationManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/**
 * Queues one {@link CogEffect} per level, in front of the player, once Flywheel can visualize
 * that level. Log lines are what CI's analyzer and local runs grep for.
 */
public final class FwvTestMod implements ClientModInitializer {
	static final Logger LOGGER = LoggerFactory.getLogger("fwv-test");
	/** Ticks to wait for Flywheel to pick a backend before reporting it can't visualize. */
	private static final int PATIENCE_TICKS = 200;
	/**
	 * Local frame-rate comparison only (env FWV_COG_FIELD = a cog count; CI never sets it): place that
	 * many real Create cogwheels instead of the Effect, whatever the backend, so origin:vulkan and
	 * flywheel:off (Create's own block-entity renderer) draw the same scene.
	 */
	private static final int COG_FIELD = parseField(System.getenv("FWV_COG_FIELD"));

	private final ProofShots proofShots = new ProofShots();
	private Level done;
	private Level waiting;
	private int waited;

	@Override
	public void onInitializeClient() {
		ClientTickEvents.END_CLIENT_TICK.register(this::tick);
	}

	private void tick(Minecraft mc) {
		Level level = mc.level;
		if (level == null || mc.player == null) {
			return;
		}
		if (level == done) {
			count(mc);
			proofShots.tick(mc);
			return;
		}
		if (level != waiting) {
			waiting = level;
			waited = 0;
		}
		// A few ticks in: the player is placed and chunks around them are meshed.
		if (++waited < 20) {
			return;
		}
		if (COG_FIELD > 0) {
			placeField(mc, level);
			return;
		}
		if (!VisualizationManager.supportsVisualization(level)) {
			if (waited == PATIENCE_TICKS) {
				LOGGER.warn("[fwv-test] Flywheel cannot visualize this level (backend off?); no cogs");
				done = level;
			}
			return;
		}
		Direction facing = mc.player.getDirection();
		VisualizationManager.get(level).effects()
				.queueAdd(new CogEffect(level, mc.player.getEyePosition(), facing));
		LOGGER.info("[fwv-test] queued cog effect ({} instances) facing {}", CogEffect.TOTAL, facing);
		IntegratedServer server = mc.getSingleplayerServer();
		String ns = CreateRig.namespace();
		if (ns != null && server != null) {
			BlockPos feet = mc.player.blockPosition();
			var dim = level.dimension();
			server.execute(() -> {
				ServerLevel sl = server.getLevel(dim);
				if (sl != null) {
					LOGGER.info("[fwv-test] placed Create kinetic rig ({}): {} blocks", ns, CreateRig.place(sl, ns, feet, facing));
				}
			});
		}
		done = level;
		countAt = waited + 100;
	}

	private int countAt = -1;
	private BlockPos firstCog;

	private void placeField(Minecraft mc, Level level) {
		done = level;
		countAt = waited + 100;
		String ns = CreateRig.namespace();
		IntegratedServer server = mc.getSingleplayerServer();
		if (ns == null || server == null) {
			LOGGER.warn("[fwv-test] FWV_COG_FIELD set but no Create/Trainworks or no integrated server; nothing placed");
			return;
		}
		BlockPos feet = mc.player.blockPosition();
		Direction facing = mc.player.getDirection();
		var dim = level.dimension();
		firstCog = CreateRig.firstFieldCog(feet, facing);
		server.execute(() -> {
			ServerLevel sl = server.getLevel(dim);
			if (sl != null) {
				LOGGER.info("[fwv-test] placed cog field ({}): {} cogwheels facing {}", ns,
						CreateRig.placeField(sl, ns, feet, facing, COG_FIELD), facing);
			}
		});
	}

	private static int parseField(String v) {
		try {
			return v == null || v.isBlank() ? 0 : Math.max(0, Integer.parseInt(v.trim()));
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	/** Five seconds after queueing: how many visuals Flywheel holds (Create's block entities included). */
	private void count(Minecraft mc) {
		if (mc.level == null || mc.level != done || countAt < 0 || ++waited < countAt) {
			return;
		}
		countAt = -1;
		if (!VisualizationManager.supportsVisualization(mc.level)) {
			LOGGER.info("[fwv-test] visuals: none (Flywheel off; Create draws with its block-entity renderer)");
		} else {
			var vm = VisualizationManager.get(mc.level);
			LOGGER.info("[fwv-test] visuals: effects={} blockEntities={} entities={}",
					vm.effects().visualCount(), vm.blockEntities().visualCount(), vm.entities().visualCount());
			if (firstCog != null) {
				var be = mc.level.getBlockEntity(firstCog);
				LOGGER.info("[fwv-test] first cog block entity: {} visualizer={}", be == null ? "none" : be.getType(),
						be == null ? null : dev.engine_room.flywheel.api.visualization.VisualizerRegistry.getVisualizer(be.getType()));
			}
		}
	}
}
