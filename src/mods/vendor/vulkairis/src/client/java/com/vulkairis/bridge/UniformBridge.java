package com.vulkairis.bridge;

import com.vulkairis.core.RenderMatrices;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Manages standard Iris / Minecraft uniforms: camera matrices, projection,
 * frame timing, and resolution.
 */
public class UniformBridge {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkairis/UniformBridge");

    private static final int STANDARD_UNIFORM_BYTES = 128;

    private final RenderMatrices matrices = new RenderMatrices();
    private float frameTime = 0.016f;
    private int screenWidth = 1920;
    private int screenHeight = 1080;
    private long worldTime = 0L;
    private final Vector3f cameraPosition = new Vector3f();
    private long frameCounter = 0L;
    private float rainStrength = 0.0f;
    private float wetness = 0.0f;
    private float fogDensity = 0.0f;
    private final Vector3f fogColor = new Vector3f(0.5f, 0.5f, 0.5f);
    private float eyeBrightness = 1.0f;

    private static final UniformBridge INSTANCE = new UniformBridge();

    public static UniformBridge getInstance() {
        return INSTANCE;
    }

    public void updateMatrices(Matrix4f modelView, Matrix4f projection) {
        if (modelView != null) matrices.setModel(modelView);
        if (projection != null) {
            matrices.setProjection(new Matrix4f(RenderMatrices.CLIP_SPACE_CORRECTION).mul(projection));
        }
    }

    public void updateResolution(int width, int height) {
        this.screenWidth = width;
        this.screenHeight = height;
    }

    public void updateTime(float frameTimeDelta, long time) {
        this.frameTime = frameTimeDelta;
        this.worldTime = time;
    }

    public void updateCamera(float x, float y, float z, long frame) {
        this.cameraPosition.set(x, y, z);
        this.frameCounter = frame;
    }

    public void updateWorldState(float rain, float wet, float fogDens, Vector3f fogCol, float eyeBright) {
        this.rainStrength = rain;
        this.wetness = wet;
        this.fogDensity = fogDens;
        this.fogColor.set(fogCol);
        this.eyeBrightness = eyeBright;
    }

    public void writeStandardUniforms(ByteBuffer target) {
        if (target == null || target.remaining() < STANDARD_UNIFORM_BYTES) return;
        target.order(ByteOrder.LITTLE_ENDIAN);
        int base = target.position();
        matrices.getModelView().get(base, target);
        matrices.getProjection().get(base + 64, target);
        target.position(base + STANDARD_UNIFORM_BYTES);
    }

    public void writeOfGlobals(ByteBuffer target) {
        if (target == null) return;
        target.order(ByteOrder.LITTLE_ENDIAN);

        // std140 layout for OfGlobals
        // mat4 modelView
        matrices.getModelView().get(target);
        // mat4 projection
        matrices.getProjection().get(target);
        // vec3 cameraPosition
        target.putFloat(cameraPosition.x).putFloat(cameraPosition.y).putFloat(cameraPosition.z).putFloat(0.0f);
        // vec3 prevCameraPosition (simplified to current for now)
        target.putFloat(cameraPosition.x).putFloat(cameraPosition.y).putFloat(cameraPosition.z).putFloat(0.0f);
        // float worldTime
        target.putFloat((float)worldTime);
        // float sunAngle
        target.putFloat((float)((worldTime % 24000L) / 24000.0 * Math.PI * 2));
        // float rainStrength
        target.putFloat(rainStrength);
        // float wetness
        target.putFloat(wetness);
        // float screenWidth
        target.putFloat((float)screenWidth);
        // float screenHeight
        target.putFloat((float)screenHeight);
        // float aspectRatio
        target.putFloat((float)screenWidth / (float)screenHeight);
        // float eyeBrightness
        target.putFloat(eyeBrightness);
        // float fogDensity
        target.putFloat(fogDensity);
        // vec3 fogColor
        target.putFloat(fogColor.x).putFloat(fogColor.y).putFloat(fogColor.z).putFloat(0.0f);
    }

    public Matrix4f getModelViewMatrix() { return matrices.getModelView(); }
    public Matrix4f getProjectionMatrix() { return matrices.getProjection(); }
    public Matrix4f getModelViewProjection() { return matrices.getModelViewProjection(); }
    public float getFrameTime() { return frameTime; }
    public int getScreenWidth() { return screenWidth; }
    public int getScreenHeight() { return screenHeight; }
    public Vector3f getCameraPosition() { return cameraPosition; }
    public long getFrameCounter() { return frameCounter; }
}
