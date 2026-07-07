package com.origin.client.client.font;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

// Loads one baked Inter weight (tools/font-atlas/generate_atlas.py's output,
// see DESIGN_SYSTEM.md and the M2/M3 plan) as a standalone GL texture with
// forced linear filtering, plus its exact glyph metrics. Deliberately not
// registered through Minecraft's own bitmap/ttf font provider — this is the
// M3 experiment: a real anti-aliased raster + GL_LINEAR instead of
// Minecraft's typical nearest-neighbor UI sampling, drawn through
// GuiGraphics's existing textured-quad path, no custom shader.
public final class OriginFontAtlas {
	private static final Gson GSON = new Gson();
	private static final Map<Integer, OriginFontAtlas> CACHE = new HashMap<>();

	public final ResourceLocation textureId;
	public final int emSize;
	public final int atlasWidth;
	public final int atlasHeight;
	private final Map<Character, Glyph> glyphs = new HashMap<>();

	public record Glyph(int x, int y, int width, int height, float bearingX, float bearingY, float advance) {
	}

	private OriginFontAtlas(ResourceLocation textureId, int emSize, int atlasWidth, int atlasHeight) {
		this.textureId = textureId;
		this.emSize = emSize;
		this.atlasWidth = atlasWidth;
		this.atlasHeight = atlasHeight;
	}

	public static synchronized OriginFontAtlas get(int weight) {
		return CACHE.computeIfAbsent(weight, OriginFontAtlas::load);
	}

	public Glyph glyph(char c) {
		return glyphs.get(c);
	}

	private static OriginFontAtlas load(int weight) {
		Minecraft client = Minecraft.getInstance();
		ResourceLocation jsonId = ResourceLocation.fromNamespaceAndPath("originclient", "textures/font/inter-" + weight + ".json");
		ResourceLocation pngId = ResourceLocation.fromNamespaceAndPath("originclient", "textures/font/inter-" + weight + ".png");

		JsonObject root;
		try (InputStream in = openOrThrow(client, jsonId);
			 InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
			root = GSON.fromJson(reader, JsonObject.class);
		} catch (IOException e) {
			throw new RuntimeException("Failed to load Origin font metrics for weight " + weight, e);
		}

		NativeImage image;
		try (InputStream in = openOrThrow(client, pngId)) {
			image = NativeImage.read(in);
		} catch (IOException e) {
			throw new RuntimeException("Failed to load Origin font atlas texture for weight " + weight, e);
		}

		ResourceLocation textureId = ResourceLocation.fromNamespaceAndPath("originclient", "font_atlas_" + weight);
		DynamicTexture texture = new DynamicTexture(image);
		texture.setFilter(true, false); // blur=true -> GL_LINEAR, no mipmap
		client.getTextureManager().register(textureId, texture);

		int emSize = root.get("emSize").getAsInt();
		int atlasWidth = root.get("atlasWidth").getAsInt();
		int atlasHeight = root.get("atlasHeight").getAsInt();
		OriginFontAtlas atlas = new OriginFontAtlas(textureId, emSize, atlasWidth, atlasHeight);

		JsonObject glyphsJson = root.getAsJsonObject("glyphs");
		for (String key : glyphsJson.keySet()) {
			JsonObject g = glyphsJson.getAsJsonObject(key);
			atlas.glyphs.put(key.charAt(0), new Glyph(
					g.get("x").getAsInt(), g.get("y").getAsInt(),
					g.get("width").getAsInt(), g.get("height").getAsInt(),
					g.get("bearingX").getAsFloat(), g.get("bearingY").getAsFloat(),
					g.get("advance").getAsFloat()));
		}
		return atlas;
	}

	private static InputStream openOrThrow(Minecraft client, ResourceLocation id) throws IOException {
		Optional<Resource> resource = client.getResourceManager().getResource(id);
		if (resource.isEmpty()) {
			throw new IOException("Missing Origin font resource: " + id);
		}
		return resource.get().open();
	}
}
