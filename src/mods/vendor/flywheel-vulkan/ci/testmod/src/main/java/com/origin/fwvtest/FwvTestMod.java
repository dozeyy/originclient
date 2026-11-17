package com.origin.fwvtest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.engine_room.flywheel.api.visualization.VisualizationManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
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
		if (FabricLoader.getInstance().isModLoaded("create") && server != null) {
			BlockPos feet = mc.player.blockPosition();
			var dim = level.dimension();
			server.execute(() -> {
				ServerLevel sl = server.getLevel(dim);
				if (sl != null) {
					LOGGER.info("[fwv-test] placed Create kinetic rig: {} blocks", CreateRig.place(sl, feet, facing));
				}
			});
		}
		done = level;
		countAt = waited + 100;
	}

	private int countAt = -1;

	/** Five seconds after queueing: how many visuals Flywheel holds (Create's block entities included). */
	private void count(Minecraft mc) {
		if (mc.level == null || mc.level != done || countAt < 0 || ++waited < countAt) {
			return;
		}
		countAt = -1;
		if (VisualizationManager.supportsVisualization(mc.level)) {
			var vm = VisualizationManager.get(mc.level);
			LOGGER.info("[fwv-test] visuals: effects={} blockEntities={} entities={}",
					vm.effects().visualCount(), vm.blockEntities().visualCount(), vm.entities().visualCount());
		}
	}
}
