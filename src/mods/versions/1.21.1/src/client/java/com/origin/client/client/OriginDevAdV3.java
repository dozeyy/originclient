package com.origin.client.client;

import com.origin.client.client.hud.HudElements;
import com.origin.client.client.hud.HudPos;
import com.origin.client.client.mods.Mods;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import static com.origin.client.client.OriginDevAd.*;

/**
 * Film v3 ("A first evening with Origin", 2026-11-12): the in-game clips V3_*. Dev-only (ORIGIN_SHOTS_SET=ad), driven
 * by tools/ad-capture/capture.py like R2..R12. Every clip shows a real Origin surface doing its real job: the pointer
 * and keys go through the real input path (synthetic events, the OS pointer is never touched), nothing is drawn fake.
 * Each take writes a V3_*.json sidecar: every pointer position and input event in take seconds and output px.
 */
final class OriginDevAdV3 {
	private OriginDevAdV3() {
	}

	static OriginDevAd.Clip of(String id) {
		return switch (id) {
			case "V3_LOAD" -> new Load();
			case "V3_TITLE" -> new Title();
			case "V3_HUD" -> new Hud();
			case "V3_ITEMSIZE" -> new ItemSize();
			case "V3_WAYPOINT" -> new Waypoint();
			case "V3_SNAPLOOK" -> new Snap();
			case "V3_FRIENDS" -> new Friends();
			case "V3_SCREENSHOT" -> new ShotChat();
			default -> null;
		};
	}

	/** The hero vantage (R10's river into a forest), the default world view for the V3 clips. */
	static final String HERO = "1292,1268,1340,1268,66.2,4";

	static double[] heroAt(ServerLevel level, double u) {
		double[] r = coords("ORIGIN_AD_RUN_HERO");
		if (r == null) {
			String[] p = HERO.split(",");
			r = new double[p.length];
			for (int i = 0; i < p.length; i++) {
				r[i] = Double.parseDouble(p[i]);
			}
		}
		load(level, (int) r[0], (int) r[1], 64);
		return straight(level, "hero", r[0], r[1], r[2], r[3], r[4], r[5], 11, 0, r[4]).path().at(u);
	}

	/** Only these HUD modules draw (every other HUD element's mod off). */
	static void hudOnly(String... keep) {
		java.util.Set<String> on = java.util.Set.of(keep);
		java.util.Set<String> all = new java.util.HashSet<>(java.util.List.of(OriginDevAd.ClipCoach.OFF));
		all.add("fps");
		for (HudElements.Element e : HudElements.ALL) {
			all.add(e.modId());
		}
		for (String id : all) {
			Mods.setOn(id, on.contains(id));
		}
		for (String id : keep) {
			Mods.setOn(id, true);
		}
	}

	// ================================================================ base: a timed script of glides and actions

	/**
	 * A V3 clip: a held view (or a camera path), plus a script whose actions are queued at take time. Actions added
	 * while the take runs are relative to "now", so a stall when a screen opens never makes a glide jump: each move
	 * starts from wherever the pointer really is.
	 */
	abstract static class V3 extends OriginDevAd.UiClip {
		record Act(double at, Consumer<Minecraft> run) {
		}

		/** A smooth pointer glide in take seconds; unlike Glide it always lands, however late its frames come. */
		static final class G {
			final double t0, t1, x0, y0, x1, y1;
			boolean done;

			G(double t0, double t1, double x0, double y0, double x1, double y1) {
				this.t0 = t0;
				this.t1 = t1;
				this.x0 = x0;
				this.y0 = y0;
				this.x1 = x1;
				this.y1 = y1;
			}

			void apply(Minecraft mc, double t) {
				if (done || t < t0) {
					return;
				}
				double u = Math.min(1, (t - t0) / (t1 - t0));
				done = u >= 1;
				u = u * u * (3 - 2 * u);
				mouseTo(mc, lerp(x0, x1, u), lerp(y0, y1, u));
			}
		}

		final List<G> glides = new ArrayList<>();
		final List<Act> acts = new ArrayList<>();
		double[] startPointer;   // window px, set at the take's first frame (the sidecar starts there)
		boolean begun;
		/** How far a stall pushed the script back: an act that runs late shifts every later act by as much. */
		double slip;

		/** Script time: take seconds minus the stalls so far (what when()/length are measured in). */
		double now() {
			return Math.max(0, t()) - slip;
		}

		@Override
		boolean tick(Minecraft mc, long since) {
			super.tick(mc, since);
			return takeFrom >= 0 && now() >= length;
		}

		/** Runs fn at take time t (absolute; for the opening beats). */
		void when(double t, Consumer<Minecraft> fn) {
			acts.add(new Act(t, fn));
			acts.sort(Comparator.comparingDouble(Act::at));
		}

		/** Runs fn dt seconds from now (take time). */
		void later(double dt, Consumer<Minecraft> fn) {
			when(now() + dt, fn);
		}

		/** A smooth glide from wherever the pointer is at take time t0 to (x, y) window px, over dur seconds. */
		void glide(double t0, double dur, double x, double y) {
			when(t0, mc -> glides.add(new G(t(), t() + dur, mx(mc), my(mc), x, y)));
		}

		void press(double t) {
			when(t, mc -> button(mc, GLFW.GLFW_MOUSE_BUTTON_LEFT, true));
		}

		void release(double t) {
			when(t, mc -> button(mc, GLFW.GLFW_MOUSE_BUTTON_LEFT, false));
		}

		void click(double t) {
			press(t);
			release(t + 0.07);
		}

		/** Glide onto (x, y), settle, press, drag to (x2, y2), release. Returns the time the drag ends. */
		double drag(double t0, double x, double y, double x2, double y2) {
			glide(t0, 0.55, x, y);
			press(t0 + 0.7);
			when(t0 + 0.78, mc -> glides.add(new G(t(), t() + 0.9, x, y, x2, y2)));
			release(t0 + 1.82);
			return t0 + 1.82;
		}

		void tapKey(double t, int key) {
			when(t, mc -> key(mc, key, true));
			when(t + 0.08, mc -> key(mc, key, false));
		}

		@Override
		void perFrame(Minecraft mc, double t) {
			if (!begun) {
				begun = true;
				if (startPointer != null) {
					mouseTo(mc, startPointer[0], startPointer[1]);
				}
			}
			for (G g : glides) {
				g.apply(mc, t);
			}
			// one action per frame, in order (a click lands a frame after the glide that brought the pointer there)
			if (!acts.isEmpty() && acts.get(0).at() + slip <= t) {
				slip += Math.max(0, t - (acts.get(0).at() + slip) - 0.034);
				acts.remove(0).run().accept(mc);
			}
		}

		@Override
		void finish(Minecraft mc) {
			if (takeName != null) {
				sidecar(takeName, trackJson(mc, extra()));
			}
		}

		String extra() {
			return "";
		}
	}

	// ================================================================ V3_LOAD: Origin's own loading screens, join to world

	/**
	 * Two runs. ORIGIN_AD_LOAD_STAGE=1 stands the player (spectator) at the hero vantage and quits, so the world saves
	 * there. The take run starts recording the moment the world open is requested and ends a few seconds after the
	 * world is drawn: Origin's real loading screens are the clip, and they land on that view.
	 */
	static final class Load extends V3 {
		static final boolean STAGE = "1".equals(System.getenv("ORIGIN_AD_LOAD_STAGE"));
		private double worldAt = -1;
		private long stagedAt;

		@Override
		void options(Minecraft mc) {
			length = 1e9;
			mc.options.hideGui = true;
			hudOnly();
		}

		@Override
		void openingWorld() {
			if (STAGE) {
				return;
			}
			try {
				startTake("V3_LOAD");
			} catch (java.io.IOException e) {
				throw new RuntimeException(e);
			}
		}

		@Override
		void server(MinecraftServer server, ServerLevel level, ServerPlayer p) {
			if (STAGE) {
				cmd(server, "gamemode spectator @a");
				cmd(server, "clear @a");
				double[] v = heroAt(level, 0.35);
				place(p, v);
				view(v);
			}
		}

		@Override
		boolean holdCamera() {
			return STAGE;
		}

		@Override
		boolean tick(Minecraft mc, long since) {
			mc.getToasts().clear();
			if (STAGE) {
				if (landed(mc)) {
					if (stagedAt == 0) {
						stagedAt = System.currentTimeMillis();
						log("stage: landed at the hero view");
					}
					return System.currentTimeMillis() - stagedAt > 4000;
				}
				return since > 120_000;
			}
			if (worldAt < 0 && mc.screen == null && mc.level != null) {
				worldAt = t();
				log(String.format(Locale.ROOT, "world visible at %.2f s", worldAt));
			}
			return worldAt >= 0 && (t() > worldAt + 8 || landed(mc) && t() > worldAt + 3);
		}

		@Override
		String extra() {
			return String.format(Locale.ROOT, ",\n  \"worldVisibleAt\": %.3f", worldAt);
		}
	}

	// ================================================================ V3_TITLE: the title screen, the pointer clicks "Origin"

	static final class Title extends V3 {
		private long readyAt;
		private double menuAt = -1;

		@Override
		boolean titleOnly() {
			return true;
		}

		@Override
		void options(Minecraft mc) {
			length = 1e9;
		}

		@Override
		void server(MinecraftServer server, ServerLevel level, ServerPlayer p) {
		}

		@Override
		boolean tick(Minecraft mc, long since) {
			if (takeFrom < 0) {
				if (readyAt == 0) {
					readyAt = System.currentTimeMillis();
				}
				if (System.currentTimeMillis() - readyAt < 3000 || mc.getOverlay() != null) {
					return false;
				}
				AbstractWidget b = exact(mc.screen, "Origin");
				if (b == null) {
					logWidgets(mc.screen);
					throw new IllegalStateException("no 'Origin' button on " + mc.screen);
				}
				double s = mc.getWindow().getGuiScale();
				double bx = (b.getX() + b.getWidth() / 2.0) * s, by = (b.getY() + b.getHeight() / 2.0) * s;
				log(String.format(Locale.ROOT, "Origin button at %.0f,%.0f window px", bx, by));
				startPointer = new double[]{WIN_W * 0.66, WIN_H * 0.80};
				glide(1.6, 1.1, bx, by);
				click(3.0);
				try {
					startTake("V3_TITLE");
				} catch (java.io.IOException e) {
					throw new RuntimeException(e);
				}
				return false;
			}
			if (menuAt < 0 && mc.screen != null && mc.screen.getClass().getSimpleName().equals("OriginModMenuScreen")) {
				menuAt = t();
				log(String.format(Locale.ROOT, "Origin menu open at %.2f s", menuAt));
			}
			if (t() > 15) {
				throw new IllegalStateException("the Origin menu never opened");
			}
			return menuAt >= 0 && t() > menuAt + 2.5;
		}

		@Override
		String extra() {
			return String.format(Locale.ROOT, ",\n  \"menuOpenAt\": %.3f", menuAt);
		}
	}

	static AbstractWidget exact(Screen s, String label) {
		if (s == null) {
			return null;
		}
		for (var ch : s.children()) {
			if (ch instanceof AbstractWidget w && w.visible && w.getMessage().getString().equals(label)) {
				return w;
			}
		}
		return null;
	}

	// ================================================================ V3_HUD: the HUD editor, four modules dragged into a stack

	static final class Hud extends V3 {
		static final String[] MODS = {"fps", "cps", "coords", "armorhud"};
		private final StringBuilder drops = new StringBuilder();

		@Override
		void options(Minecraft mc) {
			length = 1e9;
			mc.options.hideGui = true;   // F1-clean world; the editor draws the modules itself
			hudOnly(MODS);
		}

		@Override
		void server(MinecraftServer server, ServerLevel level, ServerPlayer p) {
			cmd(server, "gamemode spectator @a");
			cmd(server, "clear @a");
			String ench = "[minecraft:enchantments={levels:{\"minecraft:protection\":4,\"minecraft:unbreaking\":3}}]";
			cmd(server, "item replace entity @a armor.head with minecraft:diamond_helmet" + ench);
			cmd(server, "item replace entity @a armor.chest with minecraft:diamond_chestplate" + ench);
			cmd(server, "item replace entity @a armor.legs with minecraft:diamond_leggings" + ench);
			cmd(server, "item replace entity @a armor.feet with minecraft:diamond_boots" + ench);
			double[] v = heroAt(level, 0.35);
			place(p, v);
			view(v);
			drift = 0.4;
			startPointer = new double[]{WIN_W * 0.62, WIN_H * 0.72};
			at(0.5, mc -> mc.setScreen(new com.origin.client.client.hud.HudEditorScreen(false, null)));
			at(1.6, this::plan);
		}

		/** Measures the four boxes as the editor draws them and queues the drags (gui px -> window px). */
		private void plan(Minecraft mc) {
			int W = mc.getWindow().getGuiScaledWidth(), H = mc.getWindow().getGuiScaledHeight();
			double s = mc.getWindow().getGuiScale();
			double[][] box = new double[MODS.length][];
			for (int i = 0; i < MODS.length; i++) {
				for (HudElements.Element e : HudElements.ALL) {
					if (!e.modId().equals(MODS[i])) {
						continue;
					}
					int[] size = e.measure().apply(mc);
					if (size[0] <= 0 || size[1] <= 0) {
						continue;
					}
					HudPos pos = e.pos();
					double w = size[0] * pos.scale, h = size[1] * pos.scale;
					box[i] = new double[]{pos.x(W, w), pos.y(H, h), w, h};
					break;
				}
				if (box[i] == null) {
					throw new IllegalStateException("HUD module " + MODS[i] + " has no box in the editor");
				}
			}
			double gap = 8, total = -gap;
			for (double[] b : box) {
				total += b[3] + gap;
			}
			double top = Math.round((H - total) / 2.0);
			// the editor snaps a box whose centre comes within 6 px of the screen's middle line onto it: put the one
			// nearest the middle exactly on it, so the snap never shifts the stack
			double y = top, best = 1e9, shift = 0;
			for (double[] b : box) {
				double c = y + b[3] / 2 - H / 2.0;
				if (Math.abs(c) < Math.abs(best)) {
					best = c;
				}
				y += b[3] + gap;
			}
			if (Math.abs(best) <= 7) {
				shift = -best;
			}
			top += shift;
			double t0 = now() + 0.3;
			y = top;
			for (int i = 0; i < MODS.length; i++) {
				double[] b = box[i];
				double gx = b[0] + b[2] / 2, gy = b[1] + b[3] / 2;          // grab the box's centre
				double dx = 4 + b[2] / 2, dy = y + b[3] / 2;                  // drop: flush left (the editor's 4 px edge)
				drops.append(drops.length() == 0 ? "" : ", ").append(String.format(Locale.ROOT,
						"{\"mod\": \"%s\", \"from\": [%.1f, %.1f], \"to\": [%.1f, %.1f]}", MODS[i], gx * s, gy * s, dx * s, dy * s));
				t0 = drag(t0, gx * s, gy * s, dx * s, dy * s) + 0.35;
				y += b[3] + gap;
			}
			double end = t0;
			glideRest(end, mc);
			length = end + 2.2;
			log(String.format(Locale.ROOT, "HUD stack planned: top %.0f gui px, ends at %.1f s", top, length));
		}

		private void glideRest(double t, Minecraft mc) {
			glide(t, 0.7, WIN_W * 0.40, WIN_H * 0.62);
		}

		@Override
		String extra() {
			return ",\n  \"drags\": [" + drops + "]";
		}
	}

	// ================================================================ V3_ITEMSIZE: one item bigger, then dropped items at that size

	static final class ItemSize extends V3 {
		static final ResourceLocation DIAMOND = ResourceLocation.withDefaultNamespace("diamond");
		private double worldAt = -1;

		@Override
		void options(Minecraft mc) {
			length = 1e9;
			mc.options.hideGui = true;
			hudOnly();
			Mods.setOn("itemsize", true);
			com.origin.client.client.mods.ItemSizes.reset(DIAMOND);   // every take starts from 1.00x
			com.origin.client.client.mods.ItemSizes.flush();
		}

		@Override
		void server(MinecraftServer server, ServerLevel level, ServerPlayer p) {
			cmd(server, "gamemode spectator @a");
			cmd(server, "clear @a");
			cmd(server, "kill @e[type=minecraft:item]");
			double[] at = coords("ORIGIN_AD_ITEM_AT");
			BlockPos spot = flat(level, at == null ? 1296 : (int) at[0], at == null ? 1236 : (int) at[1]);
			double cx = spot.getX() + 0.5, cy = spot.getY(), cz = spot.getZ() + 0.5;
			// the world's own animals wander in front of the items: clear the spot (nothing is added, only removed);
			// discard, not /kill: a killed cow drops beef on the spot
			for (var e : level.getEntities((net.minecraft.world.entity.Entity) null,
					new net.minecraft.world.phys.AABB(cx - 48, cy - 24, cz - 48, cx + 48, cy + 24, cz + 48),
					e -> !(e instanceof ServerPlayer) && !(e instanceof ItemEntity))) {
				e.discard();
			}
			log("items at " + spot.toShortString() + " (" + biomeAt(level, spot.getX(), spot.getY(), spot.getZ()) + ")");
			drop(level, Items.DIAMOND, cx - 0.55, cy, cz - 0.15);
			drop(level, Items.DIAMOND, cx + 0.45, cy, cz + 0.35);
			drop(level, Items.DIAMOND, cx + 0.05, cy, cz - 0.85);
			drop(level, Items.EMERALD, cx - 1.25, cy, cz + 0.45);
			drop(level, Items.GOLD_INGOT, cx + 1.3, cy, cz - 0.55);
			double[] v = {cx, cy + 1.75, cz + 3.4, 180, 25};
			place(p, v);
			view(v);
			startPointer = new double[]{WIN_W * 0.64, WIN_H * 0.78};
			at(1.0, mc -> mc.setScreen(new com.origin.client.client.gui.OriginItemSizeScreen(null)));
			at(2.6, this::search);
		}

		private static void drop(ServerLevel level, net.minecraft.world.item.Item item, double x, double y, double z) {
			ItemEntity e = new ItemEntity(level, x, y + 0.05, z, new ItemStack(item));
			e.setDeltaMovement(0, 0, 0);
			e.setNeverPickUp();
			e.setUnlimitedLifetime();
			level.addFreshEntity(e);
		}

		/** A flat grass patch with open sky (no leaves, no plants on the drop square), nearest (x, z). */
		static BlockPos flat(ServerLevel level, int x0, int z0) {
			BlockPos fallback = null;
			for (int r = 0; r <= 80; r += 2) {
				for (int dx = -r; dx <= r; dx += 2) {
					for (int dz = -r; dz <= r; dz += 2) {
						if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
							continue;
						}
						int x = x0 + dx, z = z0 + dz;
						int g = ground(level, x, z);
						boolean ok = true, bare = true;
						for (int ax = -2; ax <= 2 && ok; ax++) {
							for (int az = -2; az <= 5 && ok; az++) {   // the drop square and the ground under the camera
								int gx = x + ax, gz = z + az;
								ok = ground(level, gx, gz) == g && top(level, gx, gz) == g
										&& level.getBlockState(new BlockPos(gx, g - 1, gz)).is(Blocks.GRASS_BLOCK);
								if (ok && Math.abs(ax) <= 1 && az >= -1 && az <= 1) {
									bare &= level.getHeight(Heightmap.Types.WORLD_SURFACE, gx, gz) == g;
								}
							}
						}
						if (ok && bare) {
							return new BlockPos(x, g, z);
						}
						if (ok && fallback == null) {
							fallback = new BlockPos(x, g, z);
						}
					}
				}
			}
			if (fallback == null) {
				throw new IllegalStateException("no flat grass near " + x0 + "," + z0);
			}
			return fallback;
		}

		/** Every click target the Origin window recorded last frame: {x0, y0, x1, y1} in design px, then ox, oy, scale. */
		static List<float[]> hits(Screen s) {
			List<float[]> out = new ArrayList<>();
			try {
				java.lang.reflect.Field hf = null;
				for (Class<?> k = s.getClass(); k != null && hf == null; k = k.getSuperclass()) {
					for (java.lang.reflect.Field f : k.getDeclaredFields()) {
						if (f.getName().equals("hits")) {
							hf = f;
						}
					}
				}
				hf.setAccessible(true);
				float ox = fieldF(s, "lastOx"), oy = fieldF(s, "lastOy"), sc = fieldF(s, "lastS");
				for (Object h : new ArrayList<>((List<?>) hf.get(s))) {
					var r = (com.origin.client.client.gui.OriginDeck.Hit) h;
					out.add(new float[]{r.x0(), r.y0(), r.x1(), r.y1(), ox, oy, sc});
				}
			} catch (Throwable t) {
				throw new IllegalStateException("no hits on " + s, t);
			}
			return out;
		}

		/** Design px -> window px for a hit from hits(). */
		static double wx(float[] h, double d) {
			return h[4] + d * h[6];
		}

		static double wy(float[] h, double d) {
			return h[5] + d * h[6];
		}

		/** The search box: the right-most 32-tall target on the toolbar row (design y = BODY_Y). */
		private void search(Minecraft mc) {
			logHits(mc.screen);
			float[] best = null;
			for (float[] h : hits(mc.screen)) {
				if (Math.abs(h[1] - com.origin.client.client.gui.OriginWindow.BODY_Y) < 0.5f && Math.abs(h[3] - h[1] - 32) < 0.5f
						&& (best == null || h[0] > best[0])) {
					best = h;
				}
			}
			if (best == null) {
				throw new IllegalStateException("no search box");
			}
			double sx = wx(best, best[0] + (best[2] - best[0]) * 0.3), sy = wy(best, (best[1] + best[3]) / 2);
			double t0 = now();
			glide(t0, 0.8, sx, sy);
			click(t0 + 0.95);
			String q = "diamond";
			for (int i = 0; i < q.length(); i++) {
				char c = q.charAt(i);
				when(t0 + 1.35 + i * 0.09, m -> {
					m.screen.charTyped(c, 0);
					trackEvent("char", String.valueOf(c), mx(m), my(m));
				});
			}
			when(t0 + 2.3, this::pick);
		}

		/** The Diamond cell (its index in the filtered grid = its index among the square targets), then the slider. */
		private void pick(Minecraft mc) {
			int idx = -1;
			try {
				java.lang.reflect.Field f = com.origin.client.client.gui.OriginItemGridScreen.class.getDeclaredField("filtered");
				f.setAccessible(true);
				List<?> list = (List<?>) f.get(mc.screen);
				for (int i = 0; i < list.size(); i++) {
					java.lang.reflect.Method id = list.get(i).getClass().getDeclaredMethod("id");
					id.setAccessible(true);
					if (DIAMOND.equals(id.invoke(list.get(i)))) {
						idx = i;
						break;
					}
				}
			} catch (Throwable t) {
				throw new IllegalStateException("can't read the item grid", t);
			}
			List<float[]> cells = new ArrayList<>();
			for (float[] h : hits(mc.screen)) {
				float w = h[2] - h[0], hg = h[3] - h[1];
				if (Math.abs(w - hg) < 0.5f && w > 50 && w < 60 && h[1] > com.origin.client.client.gui.OriginWindow.BODY_Y + 40) {
					cells.add(h);
				}
			}
			if (idx < 0 || idx >= cells.size()) {
				throw new IllegalStateException("Diamond is cell " + idx + " of " + cells.size());
			}
			float[] c = cells.get(idx);
			double t0 = now();
			glide(t0, 0.75, wx(c, (c[0] + c[2]) / 2), wy(c, (c[1] + c[3]) / 2));
			click(t0 + 0.9);
			when(t0 + 1.6, this::slide);
		}

		private double target = 2.5;

		/** Drags the size slider's thumb from 1.00x to 2.50x. */
		private void slide(Minecraft mc) {
			float[] bar = null;
			for (float[] h : hits(mc.screen)) {
				if (Math.abs(h[3] - h[1] - 40) < 0.5f && (bar == null || h[2] - h[0] > bar[2] - bar[0])) {
					bar = h;
				}
			}
			if (bar == null) {
				throw new IllegalStateException("no size slider");
			}
			float sc = bar[6];
			double tx = wx(bar, bar[0] + 8), tw = (bar[2] - bar[0] - 16) * sc, y = wy(bar, (bar[1] + bar[3]) / 2);
			double min = com.origin.client.client.mods.ItemSizes.MIN, max = com.origin.client.client.mods.ItemSizes.MAX;
			double x1 = tx + tw * (1.0 - min) / (max - min), x2 = tx + tw * (target - min) / (max - min);
			double t0 = now();
			double end = drag(t0, x1, y, x2, y);
			glide(end + 0.3, 0.6, x2 + 40 * sc, y + 70 * sc);
			tapKey(end + 1.6, GLFW.GLFW_KEY_ESCAPE);   // clears the search
			tapKey(end + 2.1, GLFW.GLFW_KEY_ESCAPE);   // closes the window: the world, the items at their new size
			when(end + 2.3, m -> worldAt = t());
			length = end + 2.3 + 4.8;
		}

		@Override
		String extra() {
			return String.format(Locale.ROOT, ",\n  \"worldAt\": %.3f,\n  \"diamondSize\": %.2f", worldAt,
					com.origin.client.client.mods.ItemSizes.get(DIAMOND));
		}
	}

	// ================================================================ V3_WAYPOINT: a beam, label and distance, minimap on

	static final class Waypoint extends V3 {
		static final String NAME = "Home";
		private volatile int wx, wy, wz;
		private boolean made;

		@Override
		void options(Minecraft mc) {
			length = 12.5;
			mc.options.hideGui = false;   // the waypoint label and the minimap draw in the HUD pass
			hudOnly("minimap", "waypoints");
		}

		@Override
		void server(MinecraftServer server, ServerLevel level, ServerPlayer p) {
			cmd(server, "gamemode spectator @a");
			cmd(server, "clear @a");
			double[] at = coords("ORIGIN_AD_WP_AT");
			wx = at == null ? 1420 : (int) at[0];
			wz = at == null ? 1268 : (int) at[1];
			load(level, wx, wz, 32);
			wy = level.getHeight(Heightmap.Types.WORLD_SURFACE, wx, wz);
			Shot run = pinned(level, "WAYPOINT");
			if (run == null) {
				run = straight(level, "waypoint", 1292, 1268, 1340, 1268, 67.5, 3, 12, 0, 67.5);
			}
			pathSecs = run.secs();
			path = run.path();
			double[] v = path.at(0);
			place(p, v);
			view(v);
			log("waypoint " + NAME + " at " + wx + "," + wy + "," + wz);
		}

		@Override
		boolean tick(Minecraft mc, long since) {
			if (!made && mc.level != null && wy != 0) {
				made = true;
				for (var w : new ArrayList<>(com.origin.client.client.waypoints.Waypoints.all())) {
					if (NAME.equals(w.name)) {
						com.origin.client.client.waypoints.Waypoints.remove(w);
					}
				}
				com.origin.client.client.waypoints.Waypoints.create(NAME, wx, wy, wz,
						com.origin.client.client.waypoints.Waypoints.currentDim(), Mods.color("waypoints", "editColor"));
			}
			return super.tick(mc, since);
		}

		@Override
		String extra() {
			return String.format(Locale.ROOT, ",\n  \"waypoint\": [%d, %d, %d]", wx, wy, wz);
		}
	}

	// ================================================================ V3_SNAPLOOK: hold the key, the view looks behind, release

	static final class Snap extends V3 {
		private int keyCode;
		private double downAt = -1, upAt = -1;

		@Override
		void options(Minecraft mc) {
			length = 11;
			mc.options.hideGui = true;
			hudOnly();
			Mods.setOn("snaplook", true);
			Mods.set("snaplook", "toggleMode", false);
			keyCode = Mods.keyCode("snaplook", "key");
		}

		@Override
		void server(MinecraftServer server, ServerLevel level, ServerPlayer p) {
			// creative, not spectator: Snaplook's front view shows the player, and a spectator is only a floating head
			cmd(server, "gamemode creative @a");
			cmd(server, "clear @a");
			p.getAbilities().flying = true;
			p.onUpdateAbilities();
			Shot run = pinned(level, "SNAP");
			if (run == null) {
				run = straight(level, "snap", 1292, 1268, 1340, 1268, 66.2, 4, 11, 0, 66.2);
			}
			pathSecs = run.secs();
			path = run.path();
			double[] v = path.at(0);
			place(p, v);
			view(v);
			when(4.2, mc -> {
				OriginDevAd.heldKeys.add(keyCode);
				key(mc, keyCode, true);
				downAt = t();
			});
			when(6.9, mc -> {
				OriginDevAd.heldKeys.remove(keyCode);
				key(mc, keyCode, false);
				upAt = t();
			});
		}

		@Override
		void frame(Minecraft mc) {
			if (mc.player != null) {
				mc.player.getAbilities().flying = true;
				mc.player.yBodyRot = mc.player.getYRot();
				mc.player.yBodyRotO = mc.player.getYRot();
			}
			super.frame(mc);
		}

		@Override
		void finish(Minecraft mc) {
			OriginDevAd.heldKeys.clear();
			super.finish(mc);
		}

		@Override
		String extra() {
			return String.format(Locale.ROOT, ",\n  \"key\": \"%s\",\n  \"heldFrom\": %.3f,\n  \"heldTo\": %.3f", keyName(keyCode), downAt, upAt);
		}
	}

	// ================================================================ V3_FRIENDS: a friend request and a party invite over the world

	static final class Friends extends V3 {
		@Override
		void options(Minecraft mc) {
			length = 9;
			// HUD on: 1.21.1's ToastComponent.render returns at once under F1 (bytecode-checked). Every Origin module off
			// and spectator (no hand, hotbar, bars or crosshair) leave the frame as clean as F1.
			mc.options.hideGui = false;
			hudOnly();
		}

		@Override
		void server(MinecraftServer server, ServerLevel level, ServerPlayer p) {
			cmd(server, "gamemode spectator @a");
			cmd(server, "clear @a");
			double[] v = heroAt(level, 0.35);
			place(p, v);
			view(v);
			drift = 0.6;
			when(1.5, mc -> com.origin.client.client.social.SocialToast.request(mc, OriginDevAd.ClipSocial.FRIEND_ID,
					OriginDevAd.ClipSocial.FRIEND));
			when(2.7, mc -> com.origin.client.client.social.SocialToast.invite(mc, OriginDevAd.ClipSocial.HOST_ID,
					OriginDevAd.ClipSocial.HOST));
		}
	}

	// ================================================================ V3_SCREENSHOT: F2, the chat line's buttons, Copy

	static final class ShotChat extends V3 {
		private String savedClipboard;
		private double copyX = -1, copyY = -1;

		@Override
		void options(Minecraft mc) {
			length = 1e9;
			mc.options.hideGui = false;   // chat draws in the HUD pass
			mc.options.chatVisibility().set(net.minecraft.world.entity.player.ChatVisiblity.FULL);
			hudOnly();
			Mods.setOn("screenshot", true);
		}

		@Override
		void server(MinecraftServer server, ServerLevel level, ServerPlayer p) {
			cmd(server, "gamemode spectator @a");
			cmd(server, "clear @a");
			double[] v = heroAt(level, 0.35);
			place(p, v);
			view(v);
			drift = 0.4;
			startPointer = new double[]{WIN_W * 0.55, WIN_H * 0.55};
			when(0.0, mc -> mc.gui.getChat().clearMessages(false));
			tapKey(1.0, GLFW.GLFW_KEY_F2);
			tapKey(2.6, GLFW.GLFW_KEY_T);
			when(3.6, this::findCopy);
		}

		/** Scans the chat (gui px) for the Copy Image button's span, then glides there and clicks it. */
		private void findCopy(Minecraft mc) {
			if (!(mc.screen instanceof net.minecraft.client.gui.screens.ChatScreen)) {
				throw new IllegalStateException("chat did not open: " + mc.screen);
			}
			var chat = mc.gui.getChat();
			int W = mc.getWindow().getGuiScaledWidth(), H = mc.getWindow().getGuiScaledHeight();
			double x0 = 1e9, x1 = -1, ySum = 0;
			int n = 0;
			for (int gy = H - 60; gy < H - 14; gy++) {
				for (int gx = 0; gx < W; gx++) {
					var st = chat.getClickedComponentStyleAt(gx + 0.5, gy + 0.5);
					var link = st == null ? null : com.origin.client.client.mods.ScreenshotLinks.resolve(st.getInsertion());
					if (link != null && "image".equals(link.action)) {
						x0 = Math.min(x0, gx);
						x1 = Math.max(x1, gx);
						ySum += gy;
						n++;
					}
				}
			}
			if (n == 0) {
				throw new IllegalStateException("no Copy Image button in chat");
			}
			double s = mc.getWindow().getGuiScale();
			copyX = (x0 + x1 + 1) / 2 * s;
			copyY = (ySum / n + 0.5) * s;
			log(String.format(Locale.ROOT, "Copy Image at %.0f,%.0f window px", copyX, copyY));
			savedClipboard = mc.keyboardHandler.getClipboard();   // the click copies for real: Will's text comes back at the end
			double t0 = now();
			glide(t0 + 0.2, 0.9, copyX, copyY);
			click(t0 + 1.55);
			// off the button again, so its hover tooltip doesn't sit over the "Screenshot copied" line
			glide(t0 + 1.85, 0.7, copyX + 260 * s / 3, copyY - 420 * s / 3);
			clickAt = t0 + 1.55;
			length = 1e9;   // tick() ends the take once the copy's own feedback line has shown (PowerShell takes seconds)
		}

		private double clickAt = -1, feedbackAt = -1;
		private String feedback;

		@Override
		boolean tick(Minecraft mc, long since) {
			if (clickAt >= 0 && feedbackAt < 0 && now() > clickAt) {
				String msg = overlay(mc);
				if (msg != null && msg.toLowerCase(Locale.ROOT).contains("screenshot")) {
					feedback = msg;
					feedbackAt = t();
					length = now() + 2.5;
					log("feedback \"" + msg + "\" at " + String.format(Locale.ROOT, "%.2f", feedbackAt));
				} else if (now() > clickAt + 10) {
					log("FAILED: no copy feedback 10 s after the click");
					length = now();
				}
			}
			return super.tick(mc, since);
		}

		/** The action-bar line (Gui.overlayMessageString, read by its dev name), or null. */
		private static String overlay(Minecraft mc) {
			try {
				java.lang.reflect.Field f = net.minecraft.client.gui.Gui.class.getDeclaredField("overlayMessageString");
				f.setAccessible(true);
				Object c = f.get(mc.gui);
				return c == null ? null : ((net.minecraft.network.chat.Component) c).getString();
			} catch (ReflectiveOperationException e) {
				return null;
			}
		}

		@Override
		void finish(Minecraft mc) {
			if (savedClipboard != null && !savedClipboard.isEmpty()) {
				mc.keyboardHandler.setClipboard(savedClipboard);
				log("clipboard text restored");
			}
			super.finish(mc);
		}

		@Override
		String extra() {
			return String.format(Locale.ROOT, ",\n  \"copyButton\": [%.1f, %.1f],\n  \"feedbackAt\": %.4f,\n  \"feedback\": \"%s\"",
					copyX, copyY, feedbackAt, feedback == null ? "" : feedback.replace("\"", "'"));
		}
	}
}
