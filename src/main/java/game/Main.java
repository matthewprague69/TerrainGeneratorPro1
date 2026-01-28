
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

    private boolean menuOpen = false;
    private int menuIndex = 0;
    private boolean prevEsc = false;
    private boolean prevUp = false;
    private boolean prevDown = false;
    private boolean prevLeft = false;
    private boolean prevRight = false;

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
        glfwSetCursorPosCallback(window, (win, xpos, ypos) -> {
            if (!menuOpen) {
                player.onMouseMove(xpos, ypos);
            }
        });

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
        PixelTextRenderer.drawText("OPEN SETTINGS - ESC", 10, 1190, 1.0f);
        PixelTextRenderer.drawText("TIME SPEED - R", 10, 1170, 1.0f);

        PixelTextRenderer.drawText("Generace Terenu", 10, 40, 1.0f);
        PixelTextRenderer.drawText("Matous Prazak UHK PGRF2 2025 ", 10, 30, 1.0f);
        PixelTextRenderer.drawText("LAST UPDATED: 25.04 21:03", 10, 20, 1.0f);

        UIRenderer.end2D();
    }

    private void drawSettingsMenu() {
        UIRenderer.begin2D(windowedWidth, windowedHeight);

        glColor3f(1, 1, 1);
        PixelTextRenderer.drawText("SETTINGS (ESC to close)", 40, 1050, 1.2f);
        PixelTextRenderer.drawText("Use UP/DOWN to select, LEFT/RIGHT to change", 40, 1020, 1.0f);

        int startY = 950;
        int lineStep = 30;
        drawMenuLine(0, "Terrain render distance", terrain.getRenderDistance(), startY);
        drawMenuLine(1, "Feature render distance", terrain.getFeatureRenderDistance(), startY - lineStep);
        drawMenuLine(2, "Feature detail distance", terrain.getFeatureDetailDistance(), startY - lineStep * 2);
        drawMenuLine(3, "Grass detail distance", terrain.getGrassDetailDistance(), startY - lineStep * 3);
        drawMenuLine(4, "Shadow render distance", terrain.getShadowRenderDistance(), startY - lineStep * 4);

        UIRenderer.end2D();
    }

    private void drawMenuLine(int index, String label, int value, int y) {
        String prefix = menuIndex == index ? "> " : "  ";
        PixelTextRenderer.drawText(prefix + label + ": " + value, 60, y, 1.0f);
    }

    private void adjustMenuSetting(int delta) {
        switch (menuIndex) {
            case 0 -> terrain.setRenderDistance(terrain.getRenderDistance() + delta);
            case 1 -> terrain.setFeatureRenderDistance(terrain.getFeatureRenderDistance() + delta);
            case 2 -> terrain.setFeatureDetailDistance(terrain.getFeatureDetailDistance() + delta);
            case 3 -> terrain.setGrassDetailDistance(terrain.getGrassDetailDistance() + delta);
            case 4 -> terrain.setShadowRenderDistance(terrain.getShadowRenderDistance() + delta);
            default -> {
            }
        }
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

            boolean currEsc = glfwGetKey(window, GLFW_KEY_ESCAPE) == GLFW_PRESS;
            if (currEsc && !prevEsc) {
                menuOpen = !menuOpen;
                if (menuOpen) {
                    glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_NORMAL);
                } else {
                    glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_DISABLED);
                    player.resetMouse();
                }
            }
            prevEsc = currEsc;
            // --- Key toggles ---
            boolean currT = glfwGetKey(window, GLFW_KEY_T) == GLFW_PRESS;
            boolean currZ = glfwGetKey(window, GLFW_KEY_Y) == GLFW_PRESS;
            boolean shiftHeld = glfwGetKey(window, GLFW_KEY_LEFT_SHIFT) == GLFW_PRESS
                    || glfwGetKey(window, GLFW_KEY_RIGHT_SHIFT) == GLFW_PRESS;

            // T: Adjust render distance
            if (!menuOpen && currT && !prevT) {
                int delta = shiftHeld ? -1 : 1;
                terrain.setRenderDistance(terrain.getRenderDistance() + delta);
            }

            // Z: Adjust feature render distance
            if (!menuOpen && currZ && !prevZ) {
                int delta = shiftHeld ? -1 : 1;
                terrain.setFeatureRenderDistance(terrain.getFeatureRenderDistance() + delta);
            }

            // Update previous key states
            prevT = currT;
            prevZ = currZ;

            if (menuOpen) {
                boolean currUp = glfwGetKey(window, GLFW_KEY_UP) == GLFW_PRESS;
                boolean currDown = glfwGetKey(window, GLFW_KEY_DOWN) == GLFW_PRESS;
                boolean currLeft = glfwGetKey(window, GLFW_KEY_LEFT) == GLFW_PRESS;
                boolean currRight = glfwGetKey(window, GLFW_KEY_RIGHT) == GLFW_PRESS;

                if (currUp && !prevUp) {
                    menuIndex = Math.max(0, menuIndex - 1);
                }
                if (currDown && !prevDown) {
                    menuIndex = Math.min(4, menuIndex + 1);
                }
                if (currLeft && !prevLeft) {
                    adjustMenuSetting(-1);
                }
                if (currRight && !prevRight) {
                    adjustMenuSetting(1);
                }

                prevUp = currUp;
                prevDown = currDown;
                prevLeft = currLeft;
                prevRight = currRight;
            } else {
                prevUp = false;
                prevDown = false;
                prevLeft = false;
                prevRight = false;
                player.update(window, dt);
            }
            Frustum frustum = Frustum.fromOpenGL();
            terrain.update(player.getX(), player.getZ(), frustum);
            sky.update(dt);

            float[] shadowDir = sky.getShadowDirection();
            float[] lightDir = sky.getLightDirection();
            float lightStrength = sky.getSkyBrightness();
            float[] lightMatrix = shadowRenderer.renderShadowMap(
                    terrain,
                    shadowDir,
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

            float[] fogSettings = terrain.getFogSettings();
            float fogStart = fogSettings[0];
            float fogEnd = fogSettings[1];
            float[] fogColor = new float[] { fogSettings[2], fogSettings[3], fogSettings[4] };
            shadowRenderer.beginScenePass(lightMatrix, lightDir, lightStrength, viewMatrix, viewInverse,
                    fogStart, fogEnd, fogColor);
            terrain.drawTerrainAndFeatures(player.getX(), player.getZ());
            shadowRenderer.endScenePass();
            terrain.drawWater();

            sky.renderSunAndMoon(player.getX(), player.getY(), player.getZ());
            if (menuOpen) {
                drawSettingsMenu();
            } else {
                drawInfoOverlay();
            }

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
