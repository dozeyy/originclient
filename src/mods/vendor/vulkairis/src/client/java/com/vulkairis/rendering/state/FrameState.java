package com.vulkairis.rendering.state;

import org.joml.Vector3f;

/**
 * Per-frame values that shader packs expect: timing, frame counters, camera position (current and
 * previous), sun/moon direction, screen dimensions, near/far planes, and fog/environment inputs.
 *
 * <p>A shader can compile correctly and still look wrong if any of these are missing or stale.
 * Keeping them in one object gives the uniform-packing layer a single source to read before each
 * pass.</p>
 */
public final class FrameState {

    private long frameIndex;
    private float frameTimeSeconds;
    private float deltaTimeSeconds;
    private float worldTimeSeconds;

    private final Vector3f cameraPosition = new Vector3f();
    private final Vector3f previousCameraPosition = new Vector3f();
    private final Vector3f sunDirection = new Vector3f(0.0f, -1.0f, 0.0f);
    private final Vector3f moonDirection = new Vector3f(0.0f, 1.0f, 0.0f);

    private int screenWidth = 1920;
    private int screenHeight = 1080;

    private float nearPlane = 0.05f;
    private float farPlane = 1000.0f;

    private final Vector3f fogColor = new Vector3f(0.0f, 0.0f, 0.0f);
    private float fogDensity;

    /**
     * Starts a new frame: advances counters and shifts the current camera position into the previous
     * slot so reprojection/previous-frame uniforms are correct.
     *
     * @param index          monotonically increasing frame index
     * @param frameTime      absolute frame time in seconds
     * @param deltaTime      seconds since the previous frame
     */
    public void beginFrame(long index, float frameTime, float deltaTime) {
        this.frameIndex = index;
        this.frameTimeSeconds = frameTime;
        this.deltaTimeSeconds = deltaTime;
        this.previousCameraPosition.set(cameraPosition);
    }

    public long getFrameIndex() {
        return frameIndex;
    }

    public float getFrameTimeSeconds() {
        return frameTimeSeconds;
    }

    public float getDeltaTimeSeconds() {
        return deltaTimeSeconds;
    }

    public float getWorldTimeSeconds() {
        return worldTimeSeconds;
    }

    public void setWorldTimeSeconds(float worldTimeSeconds) {
        this.worldTimeSeconds = worldTimeSeconds;
    }

    public Vector3f getCameraPosition() {
        return cameraPosition;
    }

    public void setCameraPosition(float x, float y, float z) {
        this.cameraPosition.set(x, y, z);
    }

    public Vector3f getPreviousCameraPosition() {
        return previousCameraPosition;
    }

    public Vector3f getSunDirection() {
        return sunDirection;
    }

    public void setSunDirection(float x, float y, float z) {
        this.sunDirection.set(x, y, z);
    }

    public Vector3f getMoonDirection() {
        return moonDirection;
    }

    public void setMoonDirection(float x, float y, float z) {
        this.moonDirection.set(x, y, z);
    }

    public int getScreenWidth() {
        return screenWidth;
    }

    public int getScreenHeight() {
        return screenHeight;
    }

    public void setScreenSize(int width, int height) {
        this.screenWidth = width;
        this.screenHeight = height;
    }

    public float getNearPlane() {
        return nearPlane;
    }

    public float getFarPlane() {
        return farPlane;
    }

    public void setClipPlanes(float near, float far) {
        this.nearPlane = near;
        this.farPlane = far;
    }

    public Vector3f getFogColor() {
        return fogColor;
    }

    public float getFogDensity() {
        return fogDensity;
    }

    public void setFog(float r, float g, float b, float density) {
        this.fogColor.set(r, g, b);
        this.fogDensity = density;
    }
}
