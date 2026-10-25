package com.origin.client.client.minimap;

import net.minecraft.block.Block;
import net.minecraft.block.BlockLeaves;
import net.minecraft.block.material.MapColor;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MathHelper;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.biome.BiomeColorHelper;
import net.minecraft.world.chunk.Chunk;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The minimap's terrain: one 512x512 texture that is a RING BUFFER over the world.
 * Block column (x, z) always lives at texel (x & 511, z & 511), so the texture never
 * shifts as the player moves -- the map just samples it at the player's position with
 * GL_REPEAT wrapping, and only chunks that come into view are written. The texture is
 * cut into 32x32 chunk SLOTS; each slot remembers which chunk it currently holds.
 *
 * Work is incremental and capped, all on the render thread (chunk data is only safe to
 * read there): every frame {@link #update} clears slots that changed owner, then scans
 * a few chunks nearest-first -- new chunks, then chunks whose look-affecting settings
 * changed, then periodic refreshes (to pick up block changes / day-night lighting) --
 * and stops at {@link #BUDGET_NS} or {@link #MAX_SCANS} per frame, whichever comes
 * first. Each scanned chunk uploads only its own 16x16 region (glTexSubImage2D), so a
 * frame never re-uploads the whole texture.
 *
 * Colours follow vanilla's map item (block MapColor, water depth, slope shading against
 * the neighbouring column), optionally biome-tinted grass/foliage/water like Xaero's.
 *
 * 1.8.9 port: the texture is a DynamicTexture(512, 512) whose int[] data is ARGB --
 * exactly what TextureUtil uploads (GL_BGRA + UNSIGNED_INT_8_8_8_8_REV), so no ABGR
 * swizzle; its first full upload also leaves it GL_NEAREST + GL_REPEAT (TextureUtil's
 * blur=false / clamp=false), which the ring buffer relies on. A 16x16 region goes up
 * with the same glTexSubImage2D call TextureUtil's private uploadTextureSub makes.
 * Columns: a loaded chunk is any non-empty Chunk from WorldClient.getChunkFromChunkCoords
 * (missing ones come back as the client's EmptyChunk); the surface starts at the
 * chunk heightmap (Chunk.getHeightValue -- the first block that stops light, as
 * vanilla's ItemMap uses) and walks down past blocks with no map colour; a column's
 * colour is Block.getMapColor(state).colorValue. 1.8.9 has no fluid states: water and
 * lava are blocks whose Material is a liquid. The world floor is 0 and the top 255.
 */
public final class MinimapData {
	static final int SIZE = 512;                 // texels (= blocks) per side
	private static final int SLOTS = SIZE >> 4;  // chunk slots per side
	/** Chunks scanned around the player's chunk -- (2R+1) must stay < SLOTS so two
	 *  chunks in the window never share a slot. 14 -> 29x29 chunks, 464 blocks. */
	public static final int MAX_RADIUS = 14;
	public static final ResourceLocation TEXTURE = new ResourceLocation("originclient", "minimap");

	// Per-frame caps: the scan budget is checked before each chunk, so a frame spends at
	// most BUDGET_NS plus one chunk's scan (~0.1-0.4 ms) on terrain, whatever happens.
	private static final long BUDGET_NS = 1_000_000L;
	private static final int MAX_SCANS = 6;
	private static final int MAX_CLEARS = 160;
	// Periodic refresh: the 3x3 chunks around the player every second (block edits you
	// make show almost at once), everything else every 6 s.
	private static final long REFRESH_NEAR_MS = 1000, REFRESH_FAR_MS = 6000;
	private static final int CAVE_SCAN_DEPTH = 72;      // max blocks looked down per column in cave mode
	private static final int CAVE_ROCK = 0xFF161616;    // solid rock in cave mode (no cave found below)

	/** What a scan bakes in. Changing any of these re-queues every slot for a rescan
	 *  (old pixels stay visible until each is redrawn, so nothing flickers). */
	// Java 8 has no records: a final value class with the same canonical
	// constructor, accessors and equals/hashCode the record had.
	public static final class Look {
		private final boolean cave;
		private final int caveTopY;
		private final boolean lighting;
		private final boolean shading;
		private final boolean biomeColors;
		private final boolean grid;
		private final int gridColor;

		public Look(boolean cave, int caveTopY, boolean lighting, boolean shading, boolean biomeColors, boolean grid, int gridColor) {
			this.cave = cave;
			this.caveTopY = caveTopY;
			this.lighting = lighting;
			this.shading = shading;
			this.biomeColors = biomeColors;
			this.grid = grid;
			this.gridColor = gridColor;
		}

		public boolean cave() {
			return cave;
		}

		public int caveTopY() {
			return caveTopY;
		}

		public boolean lighting() {
			return lighting;
		}

		public boolean shading() {
			return shading;
		}

		public boolean biomeColors() {
			return biomeColors;
		}

		public boolean grid() {
			return grid;
		}

		public int gridColor() {
			return gridColor;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj) {
				return true;
			}
			if (!(obj instanceof Look)) {
				return false;
			}
			Look other = (Look) obj;
			return this.cave == other.cave
					&& this.caveTopY == other.caveTopY
					&& this.lighting == other.lighting
					&& this.shading == other.shading
					&& this.biomeColors == other.biomeColors
					&& this.grid == other.grid
					&& this.gridColor == other.gridColor;
		}

		@Override
		public int hashCode() {
			return java.util.Objects.hash(cave, caveTopY, lighting, shading, biomeColors, grid, gridColor);
		}

		int signature() {
			return java.util.Objects.hash(cave, cave ? caveTopY >> 3 : 0, lighting, shading, biomeColors, grid, gridColor);
		}
	}

	private static DynamicTexture texture;
	private static int[] pixels;                 // the texture's own ARGB data (DynamicTexture.getTextureData)
	private static boolean failed = false;
	private static final IntBuffer REGION = BufferUtils.createIntBuffer(16 * 16);

	private static final long NO_CHUNK = Long.MIN_VALUE;
	private static final long[] slotKey = new long[SLOTS * SLOTS];
	private static final boolean[] slotHasData = new boolean[SLOTS * SLOTS];
	private static final long[] slotScannedAt = new long[SLOTS * SLOTS];
	private static final int[] slotSig = new int[SLOTS * SLOTS];
	private static WorldClient lastLevel = null;

	// (dx, dz) chunk offsets ordered by square ring then distance: the first
	// (2R+1)^2 entries are exactly the window of radius R, nearest first.
	private static final int[] SPIRAL = buildSpiral();

	// scan scratch (render thread only)
	private static final int[] colHeight = new int[256];
	private static final int[] colArgb = new int[256];
	private static final int[] colDepth = new int[256];
	private static final boolean[] colWater = new boolean[256];

	static {
		Arrays.fill(slotKey, NO_CHUNK);
	}

	private MinimapData() {
	}

	/** Whether the terrain texture exists (false = draw the map without terrain). */
	public static boolean ready() {
		return texture != null && !failed;
	}

	/** Binds the terrain texture (render thread). */
	static void bind() {
		if (texture != null) {
			GlStateManager.bindTexture(texture.getGlTextureId());
		}
	}

	/** Out of a world: drop the reference to the old level (so it can be collected);
	 *  the next world then starts from a cleared map. */
	public static void leftWorld() {
		lastLevel = null;
	}

	/** Forget everything (new world / dimension). The texture is kept and cleared. */
	private static void reset() {
		Arrays.fill(slotKey, NO_CHUNK);
		Arrays.fill(slotHasData, false);
		Arrays.fill(slotScannedAt, 0L);
		if (pixels != null) {
			Arrays.fill(pixels, 0);
			texture.updateDynamicTexture();
		}
	}

	private static boolean ensureTexture(Minecraft mc) {
		if (failed) {
			return false;
		}
		if (texture != null) {
			return true;
		}
		try {
			texture = new DynamicTexture(SIZE, SIZE);        // zeroed = fully transparent
			pixels = texture.getTextureData();
			// The first full upload also sets GL_NEAREST (crisp block pixels) and
			// GL_REPEAT (the ring buffer's wrapping UVs) -- TextureUtil's defaults.
			texture.updateDynamicTexture();
			GlStateManager.bindTexture(texture.getGlTextureId());
			GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
			GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);
			mc.getTextureManager().loadTexture(TEXTURE, texture);
			return true;
		} catch (Throwable t) {
			failed = true;
			texture = null;
			pixels = null;
			com.origin.client.OriginClient.LOGGER.warn("Origin: minimap texture could not be created; the map draws without terrain", t);
			return false;
		}
	}

	/**
	 * Advance the terrain scan by one frame's worth of work. `radiusBlocks` is how far
	 * from the player the map can currently show (its half-diagonal in blocks).
	 */
	public static void update(Minecraft mc, double radiusBlocks, Look look) {
		WorldClient level = mc.theWorld;
		if (level == null || mc.thePlayer == null || !ensureTexture(mc)) {
			return;
		}
		if (level != lastLevel) {
			lastLevel = level;
			reset();
		}
		int r = Math.max(1, Math.min(MAX_RADIUS, (int) Math.ceil(radiusBlocks / 16.0) + 1));
		int n = (2 * r + 1) * (2 * r + 1);
		int pcx = MathHelper.floor_double(mc.thePlayer.posX) >> 4, pcz = MathHelper.floor_double(mc.thePlayer.posZ) >> 4;
		long now = System.currentTimeMillis();
		long deadline = System.nanoTime() + BUDGET_NS;
		int sig = look.signature();
		int scans = 0, clears = 0;

		// Pass 1: ownership. A slot whose chunk left the window gets wiped before it can
		// show the wrong place; a loaded chunk with no pixels yet is scanned first.
		for (int i = 0; i < n; i++) {
			int cx = pcx + SPIRAL[2 * i], cz = pcz + SPIRAL[2 * i + 1];
			int slot = slot(cx, cz);
			long key = chunkKey(cx, cz);
			if (slotKey[slot] != key) {
				if (slotHasData[slot]) {
					if (clears >= MAX_CLEARS) {
						continue;
					}
					clearSlot(cx, cz);
					clears++;
				}
				slotKey[slot] = key;
				slotHasData[slot] = false;
			}
			if (!slotHasData[slot] && scans < MAX_SCANS && System.nanoTime() < deadline) {
				Chunk chunk = loadedChunk(level, cx, cz);
				if (chunk != null) {
					scan(mc, level, chunk, cx, cz, look, sig, now);
					scans++;
				}
			}
		}
		// Pass 2: settings changed (cave mode flipped, grid toggled...) -- nearest first.
		for (int i = 0; i < n && scans < MAX_SCANS && System.nanoTime() < deadline; i++) {
			int cx = pcx + SPIRAL[2 * i], cz = pcz + SPIRAL[2 * i + 1];
			int slot = slot(cx, cz);
			if (slotHasData[slot] && slotSig[slot] != sig) {
				Chunk chunk = loadedChunk(level, cx, cz);
				if (chunk != null) {
					scan(mc, level, chunk, cx, cz, look, sig, now);
					scans++;
				} else {
					slotSig[slot] = sig;   // unloaded: keep the remembered pixels as they are
				}
			}
		}
		// Pass 3: periodic refresh (block edits, lighting) for chunks still loaded.
		for (int i = 0; i < n && scans < MAX_SCANS && System.nanoTime() < deadline; i++) {
			int dx = SPIRAL[2 * i], dz = SPIRAL[2 * i + 1];
			int cx = pcx + dx, cz = pcz + dz;
			int slot = slot(cx, cz);
			long maxAge = Math.max(Math.abs(dx), Math.abs(dz)) <= 1 ? REFRESH_NEAR_MS : REFRESH_FAR_MS;
			if (slotHasData[slot] && now - slotScannedAt[slot] > maxAge) {
				Chunk chunk = loadedChunk(level, cx, cz);
				if (chunk != null) {
					scan(mc, level, chunk, cx, cz, look, sig, now);
					scans++;
				} else {
					slotScannedAt[slot] = now;
				}
			}
		}
	}

	/** ChunkPos.asLong (1.8.9's ChunkCoordIntPair.chunkXZ2Int is an int hash; a long key is collision-free). */
	private static long chunkKey(int cx, int cz) {
		return ((long) cx & 0xFFFFFFFFL) | (((long) cz & 0xFFFFFFFFL) << 32);
	}

	/** The client's chunk, or null when it isn't loaded (the client hands out an EmptyChunk then). */
	private static Chunk loadedChunk(WorldClient level, int cx, int cz) {
		try {
			Chunk c = level.getChunkFromChunkCoords(cx, cz);
			return c == null || c.isEmpty() ? null : c;   // 1.8.9 NAME UNVERIFIED: Chunk.isEmpty
		} catch (Throwable t) {
			return null;
		}
	}

	private static int slot(int cx, int cz) {
		return (cz & (SLOTS - 1)) * SLOTS + (cx & (SLOTS - 1));
	}

	private static void clearSlot(int cx, int cz) {
		int ox = (cx & (SLOTS - 1)) << 4, oz = (cz & (SLOTS - 1)) << 4;
		for (int z = 0; z < 16; z++) {
			Arrays.fill(pixels, (oz + z) * SIZE + ox, (oz + z) * SIZE + ox + 16, 0);
		}
		uploadRegion(ox, oz);
	}

	/** One 16x16 region of the ARGB data -> the GL texture (TextureUtil's own sub-upload call). */
	private static void uploadRegion(int ox, int oz) {
		REGION.clear();
		for (int z = 0; z < 16; z++) {
			REGION.put(pixels, (oz + z) * SIZE + ox, 16);
		}
		REGION.flip();
		GlStateManager.bindTexture(texture.getGlTextureId());
		GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
		GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, ox, oz, 16, 16, GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, REGION);
	}

	// ---- one chunk -> 16x16 pixels ----

	private static void scan(Minecraft mc, WorldClient level, Chunk chunk, int cx, int cz,
							 Look look, int sig, long now) {
		if (com.origin.client.client.BenchProf.ON) {
			com.origin.client.client.BenchProf.begin(com.origin.client.client.BenchProf.S_MM_SCAN);
		}
		try {
			scanChunk(level, chunk, cx, cz, look, sig, now);
		} finally {
			if (com.origin.client.client.BenchProf.ON) {
				com.origin.client.client.BenchProf.end(com.origin.client.client.BenchProf.S_MM_SCAN);
			}
		}
	}

	private static void scanChunk(WorldClient level, Chunk chunk, int cx, int cz, Look look, int sig, long now) {
		int minY = 0;     // 1.8.9 world floor
		int maxY = 255;
		int bx = cx << 4, bz = cz << 4;
		for (int lz = 0; lz < 16; lz++) {
			for (int lx = 0; lx < 16; lx++) {
				int i = lz * 16 + lx;
				if (look.cave()) {
					caveColumn(level, chunk, bx + lx, bz + lz, Math.min(look.caveTopY(), maxY), minY, look, i);
				} else {
					surfaceColumn(level, chunk, bx + lx, bz + lz, minY, maxY, look, i);
				}
			}
		}
		// Shading needs the neighbouring columns' heights: inside the chunk from the
		// scratch arrays, across the north/west chunk edge from that chunk's heightmap.
		Chunk north = look.cave() ? null : loadedChunk(level, cx, cz - 1);
		Chunk west = look.cave() ? null : loadedChunk(level, cx - 1, cz);
		float ambient = 1f;
		if (look.lighting()) {
			if (look.cave() || level.provider.getHasNoSky()) {   // 1.8.9 NAME UNVERIFIED: WorldProvider.getHasNoSky
				ambient = 0.5f;
			} else {
				// 0.2 (midnight) .. 1.0 (noon) -> 0.35 .. 1.0, so night is dark but readable
				float sky = level.getSunBrightness(1f);
				ambient = 0.35f + 0.65f * Math.max(0f, (sky - 0.2f) / 0.8f);
			}
		}
		int ox = (cx & (SLOTS - 1)) << 4, oz = (cz & (SLOTS - 1)) << 4;
		for (int lz = 0; lz < 16; lz++) {
			for (int lx = 0; lx < 16; lx++) {
				int i = lz * 16 + lx;
				int argb = colArgb[i];
				if ((argb >>> 24) != 0 && argb != CAVE_ROCK) {
					float f;
					if (colWater[i]) {
						f = 1.0f - Math.min(colDepth[i], 12) * 0.03f;
					} else if (look.shading()) {
						int h = colHeight[i];
						int hn = lz > 0 ? colHeight[i - 16] : neighbourHeight(north, lx, 15, h);
						int hw = lx > 0 ? colHeight[i - 1] : neighbourHeight(west, 15, lz, h);
						int d = Math.max(-6, Math.min(6, (h - hn) + (h - hw)));
						f = 0.88f + d * 0.04f;
					} else {
						f = 0.9f;
					}
					if (look.cave()) {
						// deeper below the player = dimmer, so the cave you're in stands out
						f *= Math.max(0.55f, 1f - (look.caveTopY() - colHeight[i]) * 0.012f);
					}
					if (look.lighting()) {
						// clamped: Chunk.getLightFor indexes its 16 sections by y >> 4 (256 = out of range)
						int ly = Math.min(255, colHeight[i] + 1);
						int block = chunk.getLightFor(EnumSkyBlock.BLOCK, new BlockPos(bx + lx, ly, bz + lz));   // 1.8.9 NAME UNVERIFIED
						f *= Math.max(ambient, block / 15f);
					}
					argb = scaleRgb(argb, f);
				}
				if (look.grid() && (lx == 0 || lz == 0)) {
					argb = blend(argb, look.gridColor());
				}
				pixels[(oz + lz) * SIZE + ox + lx] = argb;
			}
		}
		uploadRegion(ox, oz);
		int slot = slot(cx, cz);
		slotKey[slot] = chunkKey(cx, cz);
		slotHasData[slot] = true;
		slotScannedAt[slot] = now;
		slotSig[slot] = sig;
	}

	/** A neighbouring chunk's surface height at LOCAL (lx, lz). */
	private static int neighbourHeight(Chunk c, int lx, int lz, int fallback) {
		return c == null ? fallback : c.getHeightValue(lx, lz) - 1;
	}

	private static MapColor mapColor(IBlockState state) {
		return state.getBlock().getMapColor(state);
	}

	private static boolean liquid(Block block) {
		return block.getMaterial().isLiquid();
	}

	/** Vanilla map rules: the topmost block that has a map colour; fluids count depth. */
	private static void surfaceColumn(WorldClient level, Chunk chunk, int x, int z, int minY, int maxY, Look look, int i) {
		// The heightmap is the first block ABOVE the topmost light-stopping one.
		int y = Math.min(maxY, chunk.getHeightValue(x & 15, z & 15));   // 1.8.9 NAME UNVERIFIED: Chunk.getHeightValue / getBlockState
		colWater[i] = false;
		colDepth[i] = 0;
		BlockPos pos = new BlockPos(x, y, z);
		IBlockState state = chunk.getBlockState(pos);
		MapColor color = mapColor(state);
		while (color == MapColor.airColor && y > minY) {
			y--;
			pos = new BlockPos(x, y, z);
			state = chunk.getBlockState(pos);
			color = mapColor(state);
		}
		colHeight[i] = y;
		if (color == MapColor.airColor) {
			colArgb[i] = 0;    // void (the End's empty columns): leave see-through
			return;
		}
		if (liquid(state.getBlock())) {
			int depth = 0;
			for (int yy = y - 1; yy > minY && depth < 16; yy--) {
				if (!liquid(chunk.getBlockState(new BlockPos(x, yy, z)).getBlock())) {
					break;
				}
				depth++;
			}
			colDepth[i] = depth;
			colWater[i] = color == MapColor.waterColor;
		}
		colArgb[i] = 0xFF000000 | baseRgb(level, state, pos, color, look);
	}

	/**
	 * Cave mode: look down from just above the player for the first floor -- a block
	 * with a map colour that has air above it. Starting inside the ceiling is fine: the
	 * solid run is skipped until the first air. No floor within reach = solid rock.
	 */
	private static void caveColumn(WorldClient level, Chunk chunk, int x, int z, int top, int minY, Look look, int i) {
		colWater[i] = false;
		colDepth[i] = 0;
		boolean sawAir = false;
		int stop = Math.max(minY, top - CAVE_SCAN_DEPTH);
		for (int y = top; y > stop; y--) {
			BlockPos pos = new BlockPos(x, y, z);
			IBlockState state = chunk.getBlockState(pos);
			if (state.getBlock().getMaterial() == Material.air) {
				sawAir = true;
				continue;
			}
			if (!sawAir) {
				continue;    // still inside the ceiling
			}
			MapColor color = mapColor(state);
			if (color == MapColor.airColor) {
				continue;
			}
			colHeight[i] = y;
			colWater[i] = color == MapColor.waterColor;
			colArgb[i] = 0xFF000000 | baseRgb(level, state, pos, color, look);
			return;
		}
		colHeight[i] = top;
		colArgb[i] = CAVE_ROCK;
	}

	/** The column's colour before shading: the block's map colour, or -- with Biome
	 *  Colors on -- the biome's own grass / foliage / water tint for those blocks. */
	private static int baseRgb(WorldClient level, IBlockState state, BlockPos pos, MapColor color, Look look) {
		if (look.biomeColors()) {
			try {
				if (color == MapColor.waterColor) {
					return BiomeColorHelper.getWaterColorAtPos(level, pos) & 0xFFFFFF;   // 1.8.9 NAME UNVERIFIED
				}
				if (color == MapColor.grassColor) {
					return scaleRgb(BiomeColorHelper.getGrassColorAtPos(level, pos), 0.9f) & 0xFFFFFF;   // 1.8.9 NAME UNVERIFIED
				}
				if (color == MapColor.foliageColor) {
					// 1.8.9: a block's tint is Block.colorMultiplier(world, pos, pass)
					int tint = state.getBlock().colorMultiplier(level, pos, 0);
					if (tint != 0xFFFFFF && tint != -1) {
						return scaleRgb(tint, state.getBlock() instanceof BlockLeaves ? 0.72f : 0.85f) & 0xFFFFFF;
					}
				}
			} catch (Throwable t) {
				// a modded block's tint provider misbehaving just falls back to its map colour
			}
		}
		return color.colorValue & 0xFFFFFF;
	}

	// ---- colour maths (ARGB in, ARGB out) ----

	static int scaleRgb(int argb, float f) {
		int r = Math.min(255, (int) (((argb >> 16) & 0xFF) * f));
		int g = Math.min(255, (int) (((argb >> 8) & 0xFF) * f));
		int b = Math.min(255, (int) ((argb & 0xFF) * f));
		return (argb & 0xFF000000) | (r << 16) | (g << 8) | b;
	}

	private static int blend(int argb, int over) {
		float a = ((over >>> 24) & 0xFF) / 255f;
		if (a <= 0f) {
			return argb;
		}
		int baseA = (argb >>> 24) & 0xFF;
		int r = (int) (((argb >> 16) & 0xFF) * (1 - a) + ((over >> 16) & 0xFF) * a);
		int g = (int) (((argb >> 8) & 0xFF) * (1 - a) + ((over >> 8) & 0xFF) * a);
		int b = (int) ((argb & 0xFF) * (1 - a) + (over & 0xFF) * a);
		int outA = Math.max(baseA, (int) (a * 255));
		return (outA << 24) | (r << 16) | (g << 8) | b;
	}

	private static int[] buildSpiral() {
		List<int[]> l = new ArrayList<>();
		for (int dz = -MAX_RADIUS; dz <= MAX_RADIUS; dz++) {
			for (int dx = -MAX_RADIUS; dx <= MAX_RADIUS; dx++) {
				l.add(new int[]{dx, dz});
			}
		}
		l.sort((a, b) -> {
			int ra = Math.max(Math.abs(a[0]), Math.abs(a[1])), rb = Math.max(Math.abs(b[0]), Math.abs(b[1]));
			if (ra != rb) {
				return Integer.compare(ra, rb);
			}
			return Integer.compare(a[0] * a[0] + a[1] * a[1], b[0] * b[0] + b[1] * b[1]);
		});
		int[] out = new int[l.size() * 2];
		for (int i = 0; i < l.size(); i++) {
			out[2 * i] = l.get(i)[0];
			out[2 * i + 1] = l.get(i)[1];
		}
		return out;
	}
}
