package com.origin.client.client.mixin.core;

import com.google.common.util.concurrent.UncheckedExecutionException;
import net.minecraft.server.integrated.IntegratedServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Save and Quit can freeze the game forever on 1.8.9 (a vanilla race, proven with a thread dump in CI,
 * 2026-10-28). Target: IntegratedServer.initiateShutdown, its
 * {@code Futures.getUnchecked(addScheduledTask(<log every player out>))}.
 *
 * Save and Quit (GuiIngameMenu button 1, and Origin's own Social join flow) first closes the client's
 * connection (sendQuittingDisconnectingPacket), then calls Minecraft.loadWorld(null), which calls
 * initiateShutdown on the client thread. That queues a task for the server thread and waits for it with
 * no timeout. But the server notices the closed connection on its own (NetworkSystem.networkTick ->
 * "Stopping singleplayer server as player logged out") and stops. The server drains its task queue at
 * the START of a tick and checks the network at the END: if the client queues its task between those
 * two points, the server leaves its loop without ever running it, and the client thread waits forever
 * on a server thread that no longer exists (dump: "Client thread" parked in IntegratedServer
 * .initiateShutdown -> Futures.getUnchecked, no "Server thread" left; log ends at "Holder lookups
 * applied"). The world is already saved by then ("Saving worlds"), but the window is frozen.
 *
 * Fix: wait for the task while the server is still alive; once the server has stopped, stop waiting.
 * Nothing is lost: the task only logs players out, and the server's own stop already did that
 * (stopServer: saveAllPlayerData + removeAllPlayers). A normal quit (task runs) behaves exactly as
 * vanilla. If this redirect fails to apply (defaultRequire 0, e.g. a mod rewrote the method), the game
 * keeps vanilla's unbounded wait -- the old behaviour, never a crash.
 */
@Mixin(IntegratedServer.class)
public abstract class IntegratedServerShutdownMixin {

	@Redirect(method = "initiateShutdown", at = @At(value = "INVOKE",
			target = "Lcom/google/common/util/concurrent/Futures;getUnchecked(Ljava/util/concurrent/Future;)Ljava/lang/Object;",
			remap = false))
	private Object originclient$waitWhileServerLives(Future<?> task) {
		IntegratedServer server = (IntegratedServer) (Object) this;
		boolean interrupted = false;
		try {
			while (true) {
				try {
					return task.get(50L, TimeUnit.MILLISECONDS);
				} catch (TimeoutException e) {
					if (server.isServerStopped() && !task.isDone()) {
						task.cancel(false);
						com.origin.client.OriginClient.LOGGER.warn("[Origin] Save and Quit: the integrated server stopped"
								+ " before running its log-out task (vanilla 1.8.9 race); not waiting for it."
								+ " The world was saved by the server's own stop.");
						return null;
					}
				} catch (InterruptedException e) {
					interrupted = true;   // Futures.getUnchecked is uninterruptible too
				} catch (ExecutionException e) {
					throw new UncheckedExecutionException(e.getCause());   // same as Futures.getUnchecked
				} catch (java.util.concurrent.CancellationException e) {
					return null;
				}
			}
		} finally {
			if (interrupted) {
				Thread.currentThread().interrupt();
			}
		}
	}
}
