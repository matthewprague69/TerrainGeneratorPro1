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
    private static final float SWIM_DOWN_ACCEL = 10f;
    private static final float WATER_GRAVITY_SCALE = 0.25f;
    private static final float WATER_DRAG = 2.8f;
    private static final float WATER_BUOYANCY = 6.2f;
    private static final float WATER_SURFACE_FLOAT_OFFSET = 0.2f;

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

        float nextX = x;
        float nextZ = z;

        if (glfwGetKey(window, GLFW_KEY_W) == GLFW_PRESS) {
            nextX += forwardX * speed;
            nextZ += forwardZ * speed;
        }
        if (glfwGetKey(window, GLFW_KEY_S) == GLFW_PRESS) {
            nextX -= forwardX * speed;
            nextZ -= forwardZ * speed;
        }
        if (glfwGetKey(window, GLFW_KEY_A) == GLFW_PRESS) {
            nextX -= rightX * speed;
            nextZ -= rightZ * speed;
        }
        if (glfwGetKey(window, GLFW_KEY_D) == GLFW_PRESS) {
            nextX += rightX * speed;
            nextZ += rightZ * speed;
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

        boolean spacePressed = glfwGetKey(window, GLFW_KEY_SPACE) == GLFW_PRESS;
        boolean shiftPressed = glfwGetKey(window, GLFW_KEY_LEFT_SHIFT) == GLFW_PRESS
                || glfwGetKey(window, GLFW_KEY_RIGHT_SHIFT) == GLFW_PRESS;

        if (inWater) {
            velocityY -= gravity * WATER_GRAVITY_SCALE * dt;
            velocityY -= velocityY * Math.min(1f, WATER_DRAG * dt);

            float targetSurfaceY = waterSurfaceY + eyeHeight - WATER_SURFACE_FLOAT_OFFSET;
            velocityY += (targetSurfaceY - y) * WATER_BUOYANCY * dt;

            if (spacePressed) {
                velocityY += SWIM_UP_ACCEL * dt;
            }
            if (shiftPressed) {
                velocityY -= SWIM_DOWN_ACCEL * dt;
            }

            onGround = false;
        } else {
            if (spacePressed && !prevSpacePressed && onGround) {
                velocityY = 5f;
                onGround = false;
            }
            velocityY -= gravity * dt;
        }
        prevSpacePressed = spacePressed;

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
