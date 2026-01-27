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

    // Movement speed
    private final float moveSpeed = 50f;

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
        float speed = moveSpeed * dt;

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

        // Jump / Jetpack
        if (glfwGetKey(window, GLFW_KEY_SPACE) == GLFW_PRESS) {
            velocityY = 5f;
        }

        // Gravity
        velocityY -= gravity * dt;
        y += velocityY * dt;

        // Terrain collision ONLY (no water blocking)
        float terrainY = tm.getHeight(x, z) + eyeHeight;
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
}
