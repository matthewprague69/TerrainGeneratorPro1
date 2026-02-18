package game;

import objects.Feature;
import objects.Tree;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;

import java.util.List;

public class Player {
    // Position and orientation
    private float x, y, z;
    private float yaw = 0f, pitch = 0f;

    // Mouse handling
    private double lastMouseX, lastMouseY;
    private boolean firstMouse = true;

    // Jump and gravity
    private float velocityY = 0f;
    private final float gravity = 9.8f;
    private boolean onGround = false;
    private final float eyeHeight = 1.8f;
    private boolean prevSpacePressed = false;

    // Movement tuning
    private final float moveSpeed = 50f;
    private static final float SWIM_SPEED_MULTIPLIER = 0.45f;
    private static final float SWIM_UP_ACCEL = 14f;
    private static final float SWIM_DOWN_ACCEL = 18f;
    private static final float WATER_GRAVITY_SCALE = 0.25f;
    private static final float WATER_DRAG = 2.8f;
    private static final float WATER_BUOYANCY = 9.6f;
    private static final float WATER_SURFACE_FLOAT_OFFSET = 0.12f;
    private static final float WATER_IDLE_BOB_AMPLITUDE = 0.08f;
    private static final float WATER_IDLE_BOB_SPEED = 1.8f;
    private static final float WATER_FAST_MOVE_SINK_MAX = 0.22f;
    private static final float WATER_ENTRY_SINK_MAX = 0.38f;
    private static final float WATER_WAVE_FOLLOW_FORCE = 11.5f;
    private static final float WATER_IDLE_STICKINESS = 7.0f;
    private static final float WATER_SWIM_ACCEL_RESPONSE = 4.8f;
    private static final float WATER_SWIM_LATERAL_DRAG = 1.45f;
    private static final float WATER_WAVE_SLOPE_PUSH = 3.0f;
    private static final float WATER_WAVE_VERTICAL_ACCEL_COUPLING = 0.65f;
    private static final float WATER_DIVE_BUOYANCY_REDUCTION = 0.58f;

    private boolean wasInWater = false;
    private float previousWaterSurfaceY = Chunk.WATER_LEVEL;
    private float previousWaterSurfaceVelocity = 0f;
    private float entrySinkOffset = 0f;
    private float waterVelocityX = 0f;
    private float waterVelocityZ = 0f;

    // Terrain for collision
    private final TerrainManager tm;

    public Player(float startX, float startZ, TerrainManager tm) {
        this.tm = tm;
        this.x = startX;
        this.z = startZ;
        // Place player on terrain at start
        this.y = tm.getHeight(x, z) + eyeHeight;
    }

    /**
     * Update movement each frame: WASD relative to camera, jump, gravity, and
     * collision
     */
    public void update(long window, float dt) {
        float terrainY = tm.getHeight(x, z) + eyeHeight;
        float waterDepth = tm.getWaterDepth(x, z);
        float waterSurfaceY = tm.getWaterSurfaceHeight(x, z);
        float footY = y - eyeHeight;
        boolean inWater = waterDepth > 0.05f && footY < waterSurfaceY;

        float speed = moveSpeed * dt * (inWater ? SWIM_SPEED_MULTIPLIER : 1f);

        // Calculate forward and right vectors
        float yawRad = (float) Math.toRadians(yaw);
        float forwardX = -(float) Math.sin(yawRad);
        float forwardZ = -(float) Math.cos(yawRad);
        float rightX = (float) Math.cos(yawRad);
        float rightZ = -(float) Math.sin(yawRad);

        float prevX = x;
        float prevZ = z;
        float nextX = x;
        float nextZ = z;

        float inputX = 0f;
        float inputZ = 0f;
        if (glfwGetKey(window, GLFW_KEY_W) == GLFW_PRESS) {
            inputX += forwardX;
            inputZ += forwardZ;
        }
        if (glfwGetKey(window, GLFW_KEY_S) == GLFW_PRESS) {
            inputX -= forwardX;
            inputZ -= forwardZ;
        }
        if (glfwGetKey(window, GLFW_KEY_A) == GLFW_PRESS) {
            inputX -= rightX;
            inputZ -= rightZ;
        }
        if (glfwGetKey(window, GLFW_KEY_D) == GLFW_PRESS) {
            inputX += rightX;
            inputZ += rightZ;
        }

        float inputLen = (float) Math.sqrt(inputX * inputX + inputZ * inputZ);
        if (inputLen > 0.0001f) {
            inputX /= inputLen;
            inputZ /= inputLen;
        }

        if (inWater) {
            float sampleOffset = 0.75f;
            float surfL = tm.getWaterSurfaceHeight(x - sampleOffset, z);
            float surfR = tm.getWaterSurfaceHeight(x + sampleOffset, z);
            float surfB = tm.getWaterSurfaceHeight(x, z - sampleOffset);
            float surfF = tm.getWaterSurfaceHeight(x, z + sampleOffset);
            float waveSlopeX = (surfR - surfL) / (2f * sampleOffset);
            float waveSlopeZ = (surfF - surfB) / (2f * sampleOffset);

            float swimSpeed = moveSpeed * SWIM_SPEED_MULTIPLIER;
            float targetVelX = inputX * swimSpeed;
            float targetVelZ = inputZ * swimSpeed;

            float accelBlend = Math.min(1f, dt * WATER_SWIM_ACCEL_RESPONSE);
            waterVelocityX += (targetVelX - waterVelocityX) * accelBlend;
            waterVelocityZ += (targetVelZ - waterVelocityZ) * accelBlend;

            waterVelocityX += (-waveSlopeX) * WATER_WAVE_SLOPE_PUSH * dt;
            waterVelocityZ += (-waveSlopeZ) * WATER_WAVE_SLOPE_PUSH * dt;

            float lateralDamp = Math.max(0f, 1f - WATER_SWIM_LATERAL_DRAG * dt);
            waterVelocityX *= lateralDamp;
            waterVelocityZ *= lateralDamp;

            nextX += waterVelocityX * dt;
            nextZ += waterVelocityZ * dt;
        } else {
            nextX += inputX * speed;
            nextZ += inputZ * speed;
            waterVelocityX *= Math.max(0f, 1f - dt * 6.0f);
            waterVelocityZ *= Math.max(0f, 1f - dt * 6.0f);
        }

        // --- Terrain Feature Collision Check ---
        boolean blocked = false;
        List<Feature> nearby = tm.getNearbyFeatures(x, z, 2);

        for (Feature f : nearby) {
            if (f instanceof Tree tree) {
                if (tree.collidesWith(nextX, y, nextZ)) {
                    blocked = true;
                    break;
                }
            }
        }

        if (!blocked) {
            x = nextX;
            z = nextZ;
        }

        float movedDist = (float) Math.sqrt((x - prevX) * (x - prevX) + (z - prevZ) * (z - prevZ));
        float horizontalSpeed = dt > 0.00001f ? movedDist / dt : 0f;

        boolean spacePressed = glfwGetKey(window, GLFW_KEY_SPACE) == GLFW_PRESS;
        boolean shiftPressed = glfwGetKey(window, GLFW_KEY_LEFT_SHIFT) == GLFW_PRESS
                || glfwGetKey(window, GLFW_KEY_RIGHT_SHIFT) == GLFW_PRESS;

        if (inWater) {
            if (!wasInWater) {
                float entryImpact = Math.max(0f, -velocityY);
                entrySinkOffset = Math.min(WATER_ENTRY_SINK_MAX, entryImpact * 0.06f);
                previousWaterSurfaceY = waterSurfaceY;
                previousWaterSurfaceVelocity = 0f;
            }

            float surfaceVelocity = dt > 0.00001f ? (waterSurfaceY - previousWaterSurfaceY) / dt : 0f;
            float surfaceAcceleration = dt > 0.00001f
                    ? (surfaceVelocity - previousWaterSurfaceVelocity) / dt : 0f;
            float waveLiftVelocity = (waterSurfaceY - previousWaterSurfaceY) * WATER_WAVE_FOLLOW_FORCE;
            velocityY += waveLiftVelocity;
            velocityY += surfaceAcceleration * WATER_WAVE_VERTICAL_ACCEL_COUPLING * dt;
            previousWaterSurfaceY = waterSurfaceY;
            previousWaterSurfaceVelocity = surfaceVelocity;

            velocityY -= gravity * WATER_GRAVITY_SCALE * dt;
            velocityY -= velocityY * Math.min(1f, WATER_DRAG * dt);

            float fastMoveSink = Math.min(WATER_FAST_MOVE_SINK_MAX,
                    (horizontalSpeed / (moveSpeed * SWIM_SPEED_MULTIPLIER)) * WATER_FAST_MOVE_SINK_MAX);
            entrySinkOffset += (fastMoveSink - entrySinkOffset) * Math.min(1f, dt * 5.0f);

            float time = (float) (System.nanoTime() * 1.0e-9);
            float idleBob = (float) Math.sin(time * WATER_IDLE_BOB_SPEED) * WATER_IDLE_BOB_AMPLITUDE;
            float diveOffset = shiftPressed ? 0.42f : 0f;
            float targetSurfaceY = waterSurfaceY + eyeHeight - WATER_SURFACE_FLOAT_OFFSET + idleBob - entrySinkOffset - diveOffset;
            float buoyancy = shiftPressed ? WATER_BUOYANCY * WATER_DIVE_BUOYANCY_REDUCTION : WATER_BUOYANCY;
            velocityY += (targetSurfaceY - y) * buoyancy * dt;

            // When floating mostly still, directly follow wave trajectory to avoid looking anchored.
            float movement01 = Math.min(1f, (horizontalSpeed + (float)Math.sqrt(waterVelocityX * waterVelocityX + waterVelocityZ * waterVelocityZ)) / 3.6f);
            float input01 = (spacePressed || shiftPressed) ? 1f : 0f;
            float active01 = Math.max(movement01, input01);
            float idleFollow = (1f - active01) * Math.min(1f, dt * WATER_IDLE_STICKINESS);
            y += (targetSurfaceY - y) * idleFollow;

            if (spacePressed) {
                velocityY += SWIM_UP_ACCEL * dt;
            }
            if (shiftPressed) {
                velocityY -= SWIM_DOWN_ACCEL * dt;
            }

            onGround = false;
        } else {
            entrySinkOffset *= Math.max(0f, 1f - dt * 3.5f);
            if (spacePressed && !prevSpacePressed && onGround) {
                velocityY = 5f;
                onGround = false;
            }
            velocityY -= gravity * dt;
        }
        prevSpacePressed = spacePressed;
        wasInWater = inWater;
        if (!inWater) {
            previousWaterSurfaceVelocity *= Math.max(0f, 1f - dt * 6.0f);
        }

        y += velocityY * dt;

        // Terrain collision keeps player above ground
        terrainY = tm.getHeight(x, z) + eyeHeight;
        if (y <= terrainY) {
            y = terrainY;
            velocityY = 0f;
            onGround = true;
        }
    }

    /**
     * Apply camera transform before rendering the scene
     */
    public void applyView() {
        glRotatef(-pitch, 1, 0, 0);
        glRotatef(-yaw, 0, 1, 0);
        glTranslatef(-x, -y, -z);
    }

    public void onMouseMove(double xpos, double ypos) {
        if (firstMouse) {
            lastMouseX = xpos;
            lastMouseY = ypos;
            firstMouse = false;
        }

        double dx = xpos - lastMouseX;
        double dy = lastMouseY - ypos;

        lastMouseX = xpos;
        lastMouseY = ypos;

        float sensitivity = 0.1f;

        yaw -= dx * sensitivity;
        pitch += dy * sensitivity;

        if (pitch > 89f)
            pitch = 89f;
        if (pitch < -89f)
            pitch = -89f;
    }

    // Getters for player position
    public float getX() {
        return x;
    }

    public float getZ() {
        return z;
    }

    public float getY() {
        return y;
    }

    public float getEyeHeight() {
        return eyeHeight;
    }

    public float getFootY() {
        return y - eyeHeight;
    }
}
