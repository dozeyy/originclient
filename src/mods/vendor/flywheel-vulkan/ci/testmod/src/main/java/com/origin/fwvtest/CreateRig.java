package com.origin.fwvtest;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * Local runs only (CI never has Create: its assets are All Rights Reserved): when Create (or
 * Trainworks, which carries Create's kinetics under its own namespace) is loaded, place a creative
 * motor driving shafts and cogwheels in front of the player, so Create's own Flywheel visuals
 * (rotating shafts and cogs) draw through origin:vulkan. Blocks are looked up by id, so this mod
 * has no compile dependency on Create.
 */
final class CreateRig {
	private CreateRig() {
	}

	/** "create", "trainworks", or null when neither is loaded. */
	static String namespace() {
		if (FabricLoader.getInstance().isModLoaded("create")) {
			return "create";
		}
		return FabricLoader.getInstance().isModLoaded("trainworks") ? "trainworks" : null;
	}

	/** Server thread. Returns how many blocks were placed. */
	static int place(ServerLevel level, String ns, BlockPos feet, Direction facing) {
		Direction right = facing.getClockWise();
		Direction.Axis axis = right.getAxis();
		BlockPos start = feet.relative(facing, 5).above().relative(right, -4);
		String[] line = {"creative_motor", "shaft", "cogwheel", "shaft", "cogwheel", "shaft", "large_cogwheel", "shaft", "cogwheel"};
		int placed = 0;
		for (int i = 0; i < line.length; i++) {
			if (put(level, ns, line[i], start.relative(right, i), right, axis)) {
				placed++;
			}
		}
		// A support row under the line, so nothing reads as floating.
		for (int i = 0; i < line.length; i++) {
			level.setBlock(start.relative(right, i).below(), Blocks.SMOOTH_STONE.defaultBlockState(), Block.UPDATE_ALL);
		}
		return placed;
	}

	/**
	 * Server thread. {@code cogs} real cogwheels for a like-for-like frame-rate comparison between
	 * backends (FWV_COG_FIELD): rows of 20 along the player's right, each row driven along its own
	 * axis by one creative motor. Rows sit two blocks apart (depth and height) so no two rows mesh
	 * and fight over speed. Returns how many cogwheels were placed.
	 */
	static int placeField(ServerLevel level, String ns, BlockPos feet, Direction facing, int cogs) {
		Direction right = facing.getClockWise();
		Direction.Axis axis = right.getAxis();
		final int perRow = 20;
		final int depthRows = 8;
		int rows = (cogs + perRow - 1) / perRow;
		int placed = 0;
		for (int r = 0; r < rows; r++) {
			int depth = 5 + 2 * (r % depthRows);
			int height = 1 + 2 * (r / depthRows);
			BlockPos rowStart = feet.relative(facing, depth).above(height).relative(right, -perRow / 2 - 1);
			put(level, ns, "creative_motor", rowStart, right, axis);
			int n = Math.min(perRow, cogs - r * perRow);
			for (int i = 1; i <= n; i++) {
				if (put(level, ns, "cogwheel", rowStart.relative(right, i), right, axis)) {
					placed++;
				}
			}
		}
		// Two plain stone pillars as tall as the field, one each side of the first row: the pack shadows
		// them through its own terrain path, a ruler for where the cogs' shadows should fall.
		int top = 1 + 2 * ((rows - 1) / depthRows);
		for (int side : new int[] {-perRow / 2 - 3, perRow / 2 + 2}) {
			for (int y = 0; y <= top; y++) {
				level.setBlock(feet.relative(facing, 5).above(y).relative(right, side), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
			}
		}
		// A terrain reference in the same frame: waxed copper (orange, like the cogs) left of centre and
		// andesite (grey, like the shafts) right of it, one block in front of the first row and between
		// the two row heights. Their faces point the same ways as the cogs' and shafts', so the pack's
		// terrain shading is the yardstick for how it shades Flywheel's instances.
		// Same sun for every backend's shots: noon, clock stopped.
		level.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DAYLIGHT).set(false, level.getServer());
		level.setDayTime(6000);
		BlockPos ref = feet.relative(facing, 4).above(2);
		for (int i = 1; i <= 4; i++) {
			level.setBlock(ref.relative(right, -i), Blocks.WAXED_COPPER_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
			level.setBlock(ref.relative(right, i), Blocks.ANDESITE.defaultBlockState(), Block.UPDATE_ALL);
		}
		return placed;
	}

	/** Where placeField puts its first cogwheel. */
	static BlockPos firstFieldCog(BlockPos feet, Direction facing) {
		return feet.relative(facing, 5).above(1).relative(facing.getClockWise(), -20 / 2);
	}

	private static boolean put(ServerLevel level, String ns, String id, BlockPos pos, Direction right, Direction.Axis axis) {
		Block block = BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath(ns, id));
		if (block == Blocks.AIR) {
			FwvTestMod.LOGGER.warn("[fwv-test] {}:{} not found", ns, id);
			return false;
		}
		BlockState state = block.defaultBlockState();
		state = with(state, "facing", right.getSerializedName());
		state = with(state, "axis", axis.getSerializedName());
		return level.setBlock(pos, state, Block.UPDATE_ALL);
	}

	private static BlockState with(BlockState state, String name, String value) {
		Property<?> p = state.getBlock().getStateDefinition().getProperty(name);
		return p == null ? state : set(state, p, value);
	}

	private static <T extends Comparable<T>> BlockState set(BlockState state, Property<T> p, String value) {
		return p.getValue(value).map(v -> state.setValue(p, v)).orElse(state);
	}
}
