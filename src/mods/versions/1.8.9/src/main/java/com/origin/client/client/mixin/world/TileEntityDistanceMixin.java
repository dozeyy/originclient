package com.origin.client.client.mixin.world;

import com.origin.client.client.mods.Mods;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// SETTINGS > Performance > Tile Entity Distance. Same percentage-of-render-
// distance model as Entity Distance, but for tile entities (chests, signs,
// skulls, banners...). 1.8.9: renderTileEntity(te, partialTicks, destroyStage) is
// the world pass's per-tile-entity entry (it already culls at the tile entity's
// own max distance from staticPlayerX/Y/Z, the camera); skipping it here skips
// the draw entirely. renderTileEntityAt -- also used for chests drawn as items --
// is left alone.
@Mixin(TileEntityRendererDispatcher.class)
public abstract class TileEntityDistanceMixin {
	@Inject(method = "renderTileEntity", at = @At("HEAD"), cancellable = true)
	private void originclient$tileEntityCull(TileEntity tileEntity, float partialTicks, int destroyStage,
											 CallbackInfo ci) {
		try {
			double pct = Mods.num(Mods.PERFORMANCE_ID, "tileEntityDistance");
			if (pct >= 100 || tileEntity == null) {
				return;
			}
			BlockPos pos = tileEntity.getPos();
			double max = Minecraft.getMinecraft().gameSettings.renderDistanceChunks * 16.0 * (pct / 100.0);
			double dx = pos.getX() + 0.5 - TileEntityRendererDispatcher.staticPlayerX;
			double dy = pos.getY() + 0.5 - TileEntityRendererDispatcher.staticPlayerY;
			double dz = pos.getZ() + 0.5 - TileEntityRendererDispatcher.staticPlayerZ;
			if (dx * dx + dy * dy + dz * dz > max * max) {
				ci.cancel();
			}
		} catch (Throwable ignored) {
			// drawn as vanilla
		}
	}
}
