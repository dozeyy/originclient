package com.origin.fwvtest;

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
 * Local runs only (CI never has Create: its assets are All Rights Reserved): when Create is
 * loaded, place a creative motor driving shafts and cogwheels in front of the player, so
 * Create's own Flywheel visuals (rotating shafts and cogs) draw through origin:vulkan. Blocks
 * are looked up by id, so this mod has no compile dependency on Create.
 */
final class CreateRig {
	private CreateRig() {
	}

	/** Server thread. Returns how many blocks were placed. */
	static int place(ServerLevel level, BlockPos feet, Direction facing) {
		Direction right = facing.getClockWise();
		Direction.Axis axis = right.getAxis();
		BlockPos start = feet.relative(facing, 5).above().relative(right, -4);
		String[] line = {"creative_motor", "shaft", "cogwheel", "shaft", "cogwheel", "shaft", "large_cogwheel", "shaft", "cogwheel"};
		int placed = 0;
		for (int i = 0; i < line.length; i++) {
			Block block = BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("create", line[i]));
			if (block == Blocks.AIR) {
				FwvTestMod.LOGGER.warn("[fwv-test] create:{} not found", line[i]);
				continue;
			}
			BlockState state = block.defaultBlockState();
			state = with(state, "facing", right.getSerializedName());
			state = with(state, "axis", axis.getSerializedName());
			if (level.setBlock(start.relative(right, i), state, Block.UPDATE_ALL)) {
				placed++;
			}
		}
		// A support row under the line, so nothing reads as floating.
		for (int i = 0; i < line.length; i++) {
			level.setBlock(start.relative(right, i).below(), Blocks.SMOOTH_STONE.defaultBlockState(), Block.UPDATE_ALL);
		}
		return placed;
	}

	private static BlockState with(BlockState state, String name, String value) {
		Property<?> p = state.getBlock().getStateDefinition().getProperty(name);
		return p == null ? state : set(state, p, value);
	}

	private static <T extends Comparable<T>> BlockState set(BlockState state, Property<T> p, String value) {
		return p.getValue(value).map(v -> state.setValue(p, v)).orElse(state);
	}
}
