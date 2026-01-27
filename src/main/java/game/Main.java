
package game;
import org.lwjgl.*;
import org.lwjgl.glfw.*;
import org.lwjgl.opengl.*;
import org.lwjgl.system.*;
import renderers.PixelTextRenderer;
import renderers.ShadowRenderer;
import renderers.SkyRenderer;
import renderers.UIRenderer;
import util.TextureLoader;

import java.nio.*;

import static org.lwjgl.glfw.Callbacks.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.system.MemoryUtil.*;

public class Main {
    private long window;
    private TerrainManager terrain;
    private Player player;
    private SkyRenderer sky;
    private ShadowRenderer shadowRenderer;

    private boolean fullscreen = false;
    private final int windowedWidth = 1600;
    private final int windowedHeight = 1200;
    private long primaryMonitor;

    private void init() {
        GLFWErrorCallback.createPrint(System.err).set();
        if (!glfwInit())
            throw new IllegalStateException("Unable to initialize GLFW");

        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);
        window = glfwCreateWindow(windowedWidth, windowedHeight, "Chunked Terrain", NULL, NULL);
        if (window == NULL)
            throw new RuntimeException("Failed to create window");

        glfwMakeContextCurrent(window);
        glfwSwapInterval(1);
        GL.createCapabilities();

        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer w = stack.mallocInt(1), h = stack.mallocInt(1);
            glfwGetFramebufferSize(window, w, h);
            glViewport(0, 0, w.get(0), h.get(0));
        }

        glfwSetFramebufferSizeCallback(window, (win, w, h) -> glViewport(0, 0, w, h));

        sky = new SkyRenderer(); // create sky first
        terrain = new TerrainManager(1234L, 1f, 8, 4, sky); // pass sky into terrain manager
        terrain.setShadowRenderDistance(10);

        float half = Chunk.SIZE * terrain.getScale() * 0.5f;
        player = new Player(half, half, terrain);


        glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_DISABLED);
        glfwSetCursorPos(window, 400, 300);
        glfwSetCursorPosCallback(window, (win, xpos, ypos) -> player.onMouseMove(xpos, ypos));

        primaryMonitor = glfwGetPrimaryMonitor();
        glfwShowWindow(window);

        shadowRenderer = new ShadowRenderer(2048);
    }

    private void setupProjection() {
        glMatrixMode(GL_PROJECTION);
        glLoadIdentity();
        float aspect = 800f / 600f;
        float fov = 45f;
        float near = 0.1f;
        float far = 2000f; // Updated far plane

        float y_scale = (float) (1f / Math.tan(Math.toRadians(fov / 2)));
        float x_scale = y_scale / aspect;

        FloatBuffer proj = BufferUtils.createFloatBuffer(16).put(new float[] {
                x_scale, 0, 0, 0,
                0, y_scale, 0, 0,
                0, 0, -(far + near) / (far - near), -1,
                0, 0, -(2 * near * far) / (far - near), 0
        }).flip();
        glLoadMatrixf(proj);
    }

    private void toggleFullscreen() {
        fullscreen = !fullscreen;
        GLFWVidMode vidmode = glfwGetVideoMode(primaryMonitor);

        if (fullscreen) {
            glfwSetWindowMonitor(window, primaryMonitor,
                    0, 0,
                    vidmode.width(), vidmode.height(),
                    vidmode.refreshRate());
        } else {
            int centerX = (vidmode.width() - windowedWidth) / 2;
            int centerY = (vidmode.height() - windowedHeight) / 2;

            glfwSetWindowMonitor(window, NULL,
                    centerX, centerY,
                    windowedWidth, windowedHeight,
                    vidmode.refreshRate());
        }
    }

    private void drawInfoOverlay() {
        UIRenderer.begin2D(windowedWidth, windowedHeight);

        glColor3f(1, 1, 1);
        PixelTextRenderer.drawText("ZVETSENI RENDER DISTANCE - T", 10, 1190, 1.0f);
        PixelTextRenderer.drawText("ZVETSENI GENERACE OBJEKTU DISTANCE - Z", 10, 1170, 1.0f);
        PixelTextRenderer.drawText("ZMENSENI RENDER DISTANCE - SHIFT T", 10, 1180, 1.0f);
        PixelTextRenderer.drawText("ZMENSENI GENERACE OBJEKTU DISTANCE -  SHIFT Z", 10, 1160, 1.0f);
        PixelTextRenderer.drawText("TIME SPEED - R", 10, 1150, 1.0f);

        PixelTextRenderer.drawText("Generace Terenu", 10, 40, 1.0f);
        PixelTextRenderer.drawText("Matous Prazak UHK PGRF2 2025 ", 10, 30, 1.0f);
        PixelTextRenderer.drawText("LAST UPDATED: 25.04 21:03", 10, 20, 1.0f);

        UIRenderer.end2D();
    }

    private void loop() {
        glEnable(GL_DEPTH_TEST);

        glEnable(GL_TEXTURE_2D);

        double lastTime = glfwGetTime();
        boolean prevT = false;
        boolean prevZ = false;
        boolean prevP = false;

        while (!glfwWindowShouldClose(window)) {
            double now = glfwGetTime();
            float dt = (float) (now - lastTime);
            lastTime = now;

            glfwPollEvents();

            if (glfwGetKey(window, GLFW_KEY_F) == GLFW_PRESS) {
                toggleFullscreen();
                try {
                    Thread.sleep(200);
                } catch (InterruptedException ignored) {
                }
            }
            // --- Key toggles ---
            boolean currT = glfwGetKey(window, GLFW_KEY_T) == GLFW_PRESS;
            boolean currZ = glfwGetKey(window, GLFW_KEY_Y) == GLFW_PRESS;
            boolean shiftHeld = glfwGetKey(window, GLFW_KEY_LEFT_SHIFT) == GLFW_PRESS
                    || glfwGetKey(window, GLFW_KEY_RIGHT_SHIFT) == GLFW_PRESS;

            // T: Adjust render distance
            if (currT && !prevT) {
                int delta = shiftHeld ? -1 : 1;
                terrain.setRenderDistance(terrain.getRenderDistance() + delta);
            }

            // Z: Adjust feature render distance
            if (currZ && !prevZ) {
                int delta = shiftHeld ? -1 : 1;
                terrain.setFeatureRenderDistance(terrain.getFeatureRenderDistance() + delta);
            }

            // Update previous key states
            prevT = currT;
            prevZ = currZ;

            player.update(window, dt);
            Frustum frustum = Frustum.fromOpenGL();
            terrain.update(player.getX(), player.getZ(), frustum);
            sky.update(dt);

            float[] lightDir = sky.getShadowDirection();
            float lightStrength = sky.getSkyBrightness();
            float[] lightMatrix = shadowRenderer.renderShadowMap(
                    terrain,
                    lightDir,
                    player.getX(),
                    player.getY(),
                    player.getZ(),
                    windowedWidth,
                    windowedHeight
            );

            setupProjection();

            sky.renderSkybox();
            glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

            glMatrixMode(GL_MODELVIEW);
            glLoadIdentity();

            // Set light direction in fixed world space
            player.applyView(); // First apply camera

            sky.setLightDirectionFixed(); // Then set light in world space

            float[] viewMatrix = readModelViewMatrix();
            float[] viewInverse = util.MatrixUtils.invert(viewMatrix);

            shadowRenderer.beginScenePass(lightMatrix, lightDir, lightStrength, viewMatrix, viewInverse);
            terrain.drawTerrainAndFeatures(player.getX(), player.getZ());
            shadowRenderer.endScenePass();
            terrain.drawWater();

            sky.renderSunAndMoon(player.getX(), player.getY(), player.getZ());
            drawInfoOverlay();

            glfwSwapBuffers(window);

        }
    }

    public static void main(String[] args) {
        new Main().run();
    }

    public void run() {
        init();
        loop();
        TextureLoader.disposeAll();
        glfwFreeCallbacks(window);
        glfwDestroyWindow(window);
        glfwTerminate();
        glfwSetErrorCallback(null).free();
    }

    private float[] readModelViewMatrix() {
        FloatBuffer buffer = BufferUtils.createFloatBuffer(16);
        glGetFloatv(GL_MODELVIEW_MATRIX, buffer);
        float[] matrix = new float[16];
        buffer.get(matrix);
        return matrix;
    }
}
