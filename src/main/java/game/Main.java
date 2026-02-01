
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
    private boolean menuOpen = false;
    private boolean prevMouseDown = false;

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

    private void drawInfoOverlay(int width, int height) {
        UIRenderer.begin2D(width, height);

        glColor3f(1, 1, 1);
        PixelTextRenderer.drawText("ZVETSENI RENDER DISTANCE - T", 10, height - 10, 1.0f);
        PixelTextRenderer.drawText("ZVETSENI GENERACE OBJEKTU DISTANCE - Z", 10, height - 30, 1.0f);
        PixelTextRenderer.drawText("ZMENSENI RENDER DISTANCE - SHIFT T", 10, height - 20, 1.0f);
        PixelTextRenderer.drawText("ZMENSENI GENERACE OBJEKTU DISTANCE -  SHIFT Z", 10, height - 40, 1.0f);
        PixelTextRenderer.drawText("TIME SPEED - R", 10, height - 50, 1.0f);

        PixelTextRenderer.drawText("Generace Terenu", 10, 40, 1.0f);
        PixelTextRenderer.drawText("Matous Prazak UHK PGRF2 2025 ", 10, 30, 1.0f);
        PixelTextRenderer.drawText("LAST UPDATED: 25.04 21:03", 10, 20, 1.0f);

        UIRenderer.end2D();
    }

    private void drawMenuOverlay(int width, int height, double mouseX, double mouseY) {
        UIRenderer.begin2D(width, height);
        float panelWidth = 520f;
        float panelHeight = 400f;
        float panelX = (width - panelWidth) * 0.5f;
        float panelY = (height - panelHeight) * 0.5f;

        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glColor4f(0f, 0f, 0f, 0.65f);
        drawRect(panelX, panelY, panelWidth, panelHeight);
        glDisable(GL_BLEND);
        glColor3f(1f, 1f, 1f);
        PixelTextRenderer.drawText("PAUSE MENU", panelX + 20, panelY + panelHeight - 30, 1.4f);

        float rowY = panelY + panelHeight - 80;
        drawMenuRow("Terrain render distance", terrain.getRenderDistance(), panelX + 20, rowY,
                mouseX, mouseY);
        rowY -= 40;
        drawMenuRow("Feature render distance", terrain.getFeatureRenderDistance(), panelX + 20, rowY,
                mouseX, mouseY);
        rowY -= 40;
        drawMenuRow("Low-res feature distance", terrain.getFeatureSimplifiedDistance(), panelX + 20, rowY,
                mouseX, mouseY);
        rowY -= 40;
        drawMenuRow("Impostor feature distance", terrain.getFeatureImpostorDistance(), panelX + 20, rowY,
                mouseX, mouseY);
        rowY -= 40;
        drawMenuRow("Grass detail distance", terrain.getGrassDetailDistance(), panelX + 20, rowY,
                mouseX, mouseY);
        rowY -= 40;
        drawMenuRow("Shadow render distance", terrain.getShadowRenderDistance(), panelX + 20, rowY,
                mouseX, mouseY);

        PixelTextRenderer.drawText("Time: " + String.format("%.2f", sky.getTimeOfDay()),
                panelX + 20, panelY + 30, 1.0f);
        PixelTextRenderer.drawText("Click +/- to adjust, ESC to close",
                panelX + 20, panelY + 12, 1.0f);
        UIRenderer.end2D();
    }

    private void drawRect(float x, float y, float w, float h) {
        glBegin(GL_QUADS);
        glVertex2f(x, y);
        glVertex2f(x + w, y);
        glVertex2f(x + w, y + h);
        glVertex2f(x, y + h);
        glEnd();
    }

    private void drawMenuRow(String label, int value, float x, float y, double mouseX, double mouseY) {
        PixelTextRenderer.drawText(label + ": " + value, x, y + 12, 1.0f);
        float buttonSize = 22f;
        float minusX = x + 300;
        float plusX = x + 340;
        float buttonY = y;
        drawButton(minusX, buttonY, buttonSize, buttonSize, "-", mouseX, mouseY);
        drawButton(plusX, buttonY, buttonSize, buttonSize, "+", mouseX, mouseY);
    }

    private void drawButton(float x, float y, float w, float h, String label,
                            double mouseX, double mouseY) {
        boolean hover = mouseX >= x && mouseX <= x + w && mouseY >= y && mouseY <= y + h;
        if (hover) {
            glColor3f(0.25f, 0.7f, 0.95f);
        } else {
            glColor3f(0.2f, 0.2f, 0.2f);
        }
        drawRect(x, y, w, h);
        glColor3f(1f, 1f, 1f);
        PixelTextRenderer.drawText(label, x + 6, y + 16, 1.0f);
    }

    private void handleMenuClick(double mouseX, double mouseY, int width, int height) {
        float panelWidth = 520f;
        float panelHeight = 400f;
        float panelX = (width - panelWidth) * 0.5f;
        float panelY = (height - panelHeight) * 0.5f;
        float rowY = panelY + panelHeight - 80;

        if (handleRowClick(mouseX, mouseY, panelX + 20, rowY, 0)) {
            return;
        }
        rowY -= 40;
        if (handleRowClick(mouseX, mouseY, panelX + 20, rowY, 1)) {
            return;
        }
        rowY -= 40;
        if (handleRowClick(mouseX, mouseY, panelX + 20, rowY, 2)) {
            return;
        }
        rowY -= 40;
        if (handleRowClick(mouseX, mouseY, panelX + 20, rowY, 3)) {
            return;
        }
        rowY -= 40;
        if (handleRowClick(mouseX, mouseY, panelX + 20, rowY, 4)) {
            return;
        }
        rowY -= 40;
        handleRowClick(mouseX, mouseY, panelX + 20, rowY, 5);
    }

    private boolean handleRowClick(double mouseX, double mouseY, float x, float y, int rowIndex) {
        float buttonSize = 22f;
        float minusX = x + 300;
        float plusX = x + 340;
        if (mouseX >= minusX && mouseX <= minusX + buttonSize && mouseY >= y && mouseY <= y + buttonSize) {
            adjustRowValue(rowIndex, -1);
            return true;
        }
        if (mouseX >= plusX && mouseX <= plusX + buttonSize && mouseY >= y && mouseY <= y + buttonSize) {
            adjustRowValue(rowIndex, 1);
            return true;
        }
        return false;
    }

    private void adjustRowValue(int rowIndex, int delta) {
        switch (rowIndex) {
            case 0:
                terrain.setRenderDistance(terrain.getRenderDistance() + delta);
                break;
            case 1:
                terrain.setFeatureRenderDistance(terrain.getFeatureRenderDistance() + delta);
                break;
            case 2:
                terrain.setFeatureSimplifiedDistance(terrain.getFeatureSimplifiedDistance() + delta);
                break;
            case 3:
                terrain.setFeatureImpostorDistance(terrain.getFeatureImpostorDistance() + delta);
                break;
            case 4:
                terrain.setGrassDetailDistance(terrain.getGrassDetailDistance() + delta);
                break;
            case 5:
                terrain.setShadowRenderDistance(terrain.getShadowRenderDistance() + delta);
                break;
            default:
                break;
        }
    }

    private void loop() {
        glEnable(GL_DEPTH_TEST);

        glEnable(GL_TEXTURE_2D);

        double lastTime = glfwGetTime();
        boolean prevT = false;
        boolean prevZ = false;
        boolean prevP = false;
        boolean prevEsc = false;
        boolean prev1 = false;
        boolean prev2 = false;
        boolean prev3 = false;
        boolean prev4 = false;
        boolean prev5 = false;
        boolean prev6 = false;

        while (!glfwWindowShouldClose(window)) {
            double now = glfwGetTime();
            float dt = (float) (now - lastTime);
            lastTime = now;

            glfwPollEvents();

            int fbWidth;
            int fbHeight;
            double mouseX;
            double mouseY;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer w = stack.mallocInt(1);
                IntBuffer h = stack.mallocInt(1);
                glfwGetFramebufferSize(window, w, h);
                fbWidth = w.get(0);
                fbHeight = h.get(0);
                double[] mx = new double[1];
                double[] my = new double[1];
                glfwGetCursorPos(window, mx, my);
                mouseX = mx[0];
                mouseY = fbHeight - my[0];
            }

            boolean mouseDown = glfwGetMouseButton(window, GLFW_MOUSE_BUTTON_LEFT) == GLFW_PRESS;
            if (menuOpen && mouseDown && !prevMouseDown) {
                handleMenuClick(mouseX, mouseY, fbWidth, fbHeight);
            }
            prevMouseDown = mouseDown;

            boolean currEsc = glfwGetKey(window, GLFW_KEY_ESCAPE) == GLFW_PRESS;
            if (currEsc && !prevEsc) {
                menuOpen = !menuOpen;
                glfwSetInputMode(window, GLFW_CURSOR,
                        menuOpen ? GLFW_CURSOR_NORMAL : GLFW_CURSOR_DISABLED);
            }
            prevEsc = currEsc;

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

            if (!menuOpen) {
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
            } else {
                boolean curr1 = glfwGetKey(window, GLFW_KEY_1) == GLFW_PRESS;
                boolean curr2 = glfwGetKey(window, GLFW_KEY_2) == GLFW_PRESS;
                boolean curr3 = glfwGetKey(window, GLFW_KEY_3) == GLFW_PRESS;
                boolean curr4 = glfwGetKey(window, GLFW_KEY_4) == GLFW_PRESS;
                boolean curr5 = glfwGetKey(window, GLFW_KEY_5) == GLFW_PRESS;
                boolean curr6 = glfwGetKey(window, GLFW_KEY_6) == GLFW_PRESS;

                if (curr1 && !prev1) {
                    terrain.setRenderDistance(terrain.getRenderDistance() + 1);
                }
                if (curr2 && !prev2) {
                    terrain.setRenderDistance(terrain.getRenderDistance() - 1);
                }
                if (curr3 && !prev3) {
                    terrain.setFeatureRenderDistance(terrain.getFeatureRenderDistance() + 1);
                }
                if (curr4 && !prev4) {
                    terrain.setFeatureRenderDistance(terrain.getFeatureRenderDistance() - 1);
                }
                if (curr5 && !prev5) {
                    terrain.setShadowRenderDistance(terrain.getShadowRenderDistance() + 1);
                }
                if (curr6 && !prev6) {
                    terrain.setShadowRenderDistance(terrain.getShadowRenderDistance() - 1);
                }

                prev1 = curr1;
                prev2 = curr2;
                prev3 = curr3;
                prev4 = curr4;
                prev5 = curr5;
                prev6 = curr6;
            }

            // Update previous key states
            prevT = currT;
            prevZ = currZ;

            if (!menuOpen) {
                player.update(window, dt);
            }
            Frustum frustum = Frustum.fromOpenGL();
            terrain.update(player.getX(), player.getZ(), frustum);
            sky.update(dt);

            float[] shadowDir = sky.getShadowDirection();
            float[] lightDir = sky.getLightDirection();
            float lightStrength = sky.getSkyBrightness();
            float[] lightColor = sky.getLightColor();
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
            shadowRenderer.beginScenePass(lightMatrix, lightDir, lightStrength, lightColor, viewMatrix, viewInverse,
                    fogStart, fogEnd, fogColor);
            terrain.drawTerrainAndFeatures(player.getX(), player.getZ());
            shadowRenderer.endScenePass();
            terrain.drawWater(player.getX(), player.getZ());

            sky.renderSunAndMoon(player.getX(), player.getY(), player.getZ());
            if (menuOpen) {
                drawMenuOverlay(fbWidth, fbHeight, mouseX, mouseY);
            } else {
                drawInfoOverlay(fbWidth, fbHeight);
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
