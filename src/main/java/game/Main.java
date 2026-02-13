
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

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
    private int windowedWidth = 1600;
    private int windowedHeight = 1200;
    private int framebufferWidth = windowedWidth;
    private int framebufferHeight = windowedHeight;
    private long primaryMonitor;

    private static final int[][] RESOLUTION_OPTIONS = new int[][] {
            { 640, 360 },
            { 1280, 720 },
            { 1600, 1200 },
            { 1920, 1080 },
            { 2560, 1440 },
            { 3840, 2160 }
    };
    private int resolutionIndex = 2;
    private boolean vsyncEnabled = false;
    private boolean menuOpen = false;
    private boolean debugMenuOpen = false;
    private boolean prevMouseDown = false;
    private String debugExportStatus = "";
    private static final DateTimeFormatter DEBUG_EXPORT_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

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
        GL.createCapabilities();
        applyVSync();

        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer w = stack.mallocInt(1), h = stack.mallocInt(1);
            glfwGetFramebufferSize(window, w, h);
            framebufferWidth = w.get(0);
            framebufferHeight = h.get(0);
            glViewport(0, 0, framebufferWidth, framebufferHeight);
        }

        glfwSetFramebufferSizeCallback(window, (win, w, h) -> {
            framebufferWidth = Math.max(1, w);
            framebufferHeight = Math.max(1, h);
            glViewport(0, 0, framebufferWidth, framebufferHeight);
        });

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
        float aspect = framebufferHeight > 0 ? (float) framebufferWidth / (float) framebufferHeight : 1f;
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

    private void applyVSync() {
        glfwSwapInterval(vsyncEnabled ? 1 : 0);
    }

    private void toggleFullscreen() {
        fullscreen = !fullscreen;
        GLFWVidMode vidmode = glfwGetVideoMode(primaryMonitor);
        if (vidmode == null) {
            return;
        }

        if (fullscreen) {
            int[] resolution = RESOLUTION_OPTIONS[resolutionIndex];
            glfwSetWindowMonitor(window, primaryMonitor,
                    0, 0,
                    resolution[0], resolution[1],
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

    private void changeResolution(int delta) {
        int next = Math.max(0, Math.min(RESOLUTION_OPTIONS.length - 1, resolutionIndex + delta));
        if (next == resolutionIndex) {
            return;
        }
        resolutionIndex = next;
        int[] resolution = RESOLUTION_OPTIONS[resolutionIndex];
        applyResolution(resolution[0], resolution[1]);
    }

    private void applyResolution(int width, int height) {
        width = Math.max(320, width);
        height = Math.max(240, height);
        windowedWidth = width;
        windowedHeight = height;

        GLFWVidMode vidmode = glfwGetVideoMode(primaryMonitor);
        int refreshRate = vidmode != null ? vidmode.refreshRate() : GLFW_DONT_CARE;
        if (fullscreen) {
            glfwSetWindowMonitor(window, primaryMonitor,
                    0, 0,
                    width, height,
                    refreshRate);
        } else {
            glfwSetWindowSize(window, width, height);
            if (vidmode != null) {
                int centerX = (vidmode.width() - width) / 2;
                int centerY = (vidmode.height() - height) / 2;
                glfwSetWindowPos(window, centerX, centerY);
            }
        }
    }

    private String getVsyncLabel() {
        return vsyncEnabled ? "ON" : "OFF";
    }

    private String getResolutionLabel() {
        int[] resolution = RESOLUTION_OPTIONS[resolutionIndex];
        int w = resolution[0];
        int h = resolution[1];
        String tier;
        if (w == 640 && h == 360) {
            tier = "360p";
        } else if (w == 1280 && h == 720) {
            tier = "HD";
        } else if (w == 1600 && h == 1200) {
            tier = "UXGA";
        } else if (w == 1920 && h == 1080) {
            tier = "Full HD";
        } else if (w == 2560 && h == 1440) {
            tier = "QHD";
        } else if (w == 3840 && h == 2160) {
            tier = "4K";
        } else {
            tier = "Custom";
        }
        return tier + " (" + w + "x" + h + ")";
    }

    private void drawInfoOverlay(int width, int height) {
        UIRenderer.begin2D(width, height);

        glColor3f(1, 1, 1);
        PixelTextRenderer.drawText("ZVETSENI RENDER DISTANCE - T", 10, height - 10, 1.0f);
        PixelTextRenderer.drawText("ZVETSENI GENERACE OBJEKTU DISTANCE - Z", 10, height - 30, 1.0f);
        PixelTextRenderer.drawText("ZMENSENI RENDER DISTANCE - SHIFT T", 10, height - 20, 1.0f);
        PixelTextRenderer.drawText("ZMENSENI GENERACE OBJEKTU DISTANCE -  SHIFT Z", 10, height - 40, 1.0f);
        PixelTextRenderer.drawText("TIME SPEED - R", 10, height - 50, 1.0f);
        PixelTextRenderer.drawText("PERF DEBUG MENU - K (EXPORT - O)", 10, height - 60, 1.0f);

        PixelTextRenderer.drawText("Generace Terenu", 10, 40, 1.0f);
        PixelTextRenderer.drawText("Matous Prazak UHK PGRF2 2025 ", 10, 30, 1.0f);
        PixelTextRenderer.drawText("LAST UPDATED: 25.04 21:03", 10, 20, 1.0f);

        UIRenderer.end2D();
    }

    private String formatMs(long nanos) {
        return String.format(Locale.US, "%.3f", nanos / 1_000_000.0);
    }

    private String formatMb(long bytes) {
        return String.format(Locale.US, "%.2f", bytes / (1024.0 * 1024.0));
    }

    private void exportDebugReport() {
        try {
            String report = terrain.buildPerformanceReport();
            String filename = "terrain_debug_report_" + LocalDateTime.now().format(DEBUG_EXPORT_FORMAT) + ".txt";
            Path output = Paths.get(filename);
            Files.writeString(output, report, StandardCharsets.UTF_8);
            debugExportStatus = "Exported: " + output.toAbsolutePath();
        } catch (Exception ex) {
            debugExportStatus = "Export failed: " + ex.getMessage();
        }
    }

    private void drawDebugOverlay(int width, int height) {
        TerrainManager.PerformanceSnapshot snapshot = terrain.getPerformanceSnapshot();
        UIRenderer.begin2D(width, height);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glColor4f(0f, 0f, 0f, 0.72f);
        float panelX = 14f;
        float panelY = 14f;
        float panelWidth = Math.min(width - 28f, 880f);
        float panelHeight = Math.min(height - 28f, 700f);
        drawRect(panelX, panelY, panelWidth, panelHeight);
        glDisable(GL_BLEND);

        glColor3f(1f, 1f, 1f);
        float y = panelY + panelHeight - 24f;
        PixelTextRenderer.drawText("PERFORMANCE DEBUG (K to toggle, O to export)", panelX + 12f, y, 1.1f);
        y -= 20f;
        PixelTextRenderer.drawText("Update total: " + formatMs(snapshot.totalUpdateNanos) + " ms", panelX + 12f, y, 1.0f);
        y -= 16f;
        PixelTextRenderer.drawText("Estimated frame: " + formatMs(snapshot.estimatedFrameNanos) + " ms", panelX + 12f, y, 1.0f);
        y -= 16f;
        PixelTextRenderer.drawText("Chunks loaded=" + snapshot.loadedChunks
                + " | pendingChunkGen=" + snapshot.pendingChunkGenerations
                + " | pendingFeatureGen=" + snapshot.pendingFeatureGenerations
                + " | pendingRenderBuild=" + snapshot.pendingRenderBuilds, panelX + 12f, y, 1.0f);
        y -= 16f;
        PixelTextRenderer.drawText("In-flight chunk=" + snapshot.inflightChunkGenerations
                + " | in-flight feature=" + snapshot.inflightFeatureGenerations, panelX + 12f, y, 1.0f);
        y -= 16f;
        PixelTextRenderer.drawText("Memory used=" + formatMb(snapshot.usedMemoryBytes)
                + "MB free=" + formatMb(snapshot.freeMemoryBytes)
                + "MB total=" + formatMb(snapshot.totalMemoryBytes)
                + "MB max=" + formatMb(snapshot.maxMemoryBytes) + "MB", panelX + 12f, y, 1.0f);
        y -= 20f;
        PixelTextRenderer.drawText("Non-heap=" + formatMb(snapshot.nonHeapUsedBytes)
                + "MB direct=" + formatMb(snapshot.directBufferBytes)
                + "MB", panelX + 12f, y, 1.0f);
        y -= 16f;
        PixelTextRenderer.drawText("Draw load chunks terrain/water/depth=" + snapshot.terrainChunksDrawn + "/"
                + snapshot.waterChunksDrawn + "/" + snapshot.depthChunksDrawn
                + " | visible features=" + snapshot.visibleFeatures, panelX + 12f, y, 1.0f);
        y -= 20f;

        long total = Math.max(1L, snapshot.estimatedFrameNanos);
        for (TerrainManager.PipelineStage stage : TerrainManager.PipelineStage.values()) {
            TerrainManager.StageStats stats = snapshot.stages.get(stage);
            long nanos = stats != null ? stats.nanos : 0L;
            int calls = stats != null ? stats.calls : 0;
            double pct = nanos * 100.0 / total;
            String line = stage.name() + " : " + formatMs(nanos) + " ms, calls=" + calls
                    + ", " + String.format(Locale.US, "%.2f", pct) + "%";
            PixelTextRenderer.drawText(line, panelX + 12f, y, 0.88f);
            y -= 14f;
            if (y < panelY + 24f) {
                break;
            }
        }

        if (!debugExportStatus.isEmpty()) {
            PixelTextRenderer.drawText(debugExportStatus, panelX + 12f, panelY + 10f, 0.9f);
        }
        UIRenderer.end2D();
    }

    private void drawMenuOverlay(int width, int height, double mouseX, double mouseY) {
        UIRenderer.begin2D(width, height);
        float panelWidth = 520f;
        float panelHeight = 620f;
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
        drawMenuRow("Impostor feature distance", terrain.getFeatureImpostorDistance(), panelX + 20, rowY,
                mouseX, mouseY);
        rowY -= 40;
        drawMenuRow("Impostor angles", terrain.getImpostorAngleCount(), panelX + 20, rowY,
                mouseX, mouseY);
        rowY -= 40;
        drawMenuRowText("Impostor quality", getImpostorQualityLabel(), panelX + 20, rowY,
                mouseX, mouseY);
        rowY -= 40;
        drawMenuRow("HQ impostor distance", terrain.getImpostorHighQualityDistance(), panelX + 20, rowY,
                mouseX, mouseY);
        rowY -= 40;
        drawMenuRow("Grass detail distance", terrain.getGrassDetailDistance(), panelX + 20, rowY,
                mouseX, mouseY);
        rowY -= 40;
        drawMenuRow("Shadow render distance", terrain.getShadowRenderDistance(), panelX + 20, rowY,
                mouseX, mouseY);
        rowY -= 40;
        drawMenuRowText("Fullscreen", fullscreen ? "ON" : "OFF", panelX + 20, rowY, mouseX, mouseY);
        rowY -= 40;
        drawMenuRowText("Resolution", getResolutionLabel(), panelX + 20, rowY, mouseX, mouseY);
        rowY -= 40;
        drawMenuRowText("VSync", getVsyncLabel(), panelX + 20, rowY, mouseX, mouseY);

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

    private void drawMenuRowText(String label, String value, float x, float y, double mouseX, double mouseY) {
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
        float panelHeight = 620f;
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
        if (handleRowClick(mouseX, mouseY, panelX + 20, rowY, 5)) {
            return;
        }
        rowY -= 40;
        if (handleRowClick(mouseX, mouseY, panelX + 20, rowY, 6)) {
            return;
        }
        rowY -= 40;
        if (handleRowClick(mouseX, mouseY, panelX + 20, rowY, 7)) {
            return;
        }
        rowY -= 40;
        if (handleRowClick(mouseX, mouseY, panelX + 20, rowY, 8)) {
            return;
        }
        rowY -= 40;
        if (handleRowClick(mouseX, mouseY, panelX + 20, rowY, 9)) {
            return;
        }
        rowY -= 40;
        handleRowClick(mouseX, mouseY, panelX + 20, rowY, 10);
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
                terrain.setFeatureImpostorDistance(terrain.getFeatureImpostorDistance() + delta);
                break;
            case 3:
                terrain.setImpostorAngleCount(adjustImpostorAngleCount(terrain.getImpostorAngleCount(), delta));
                break;
            case 4:
                terrain.setImpostorQualityPreset(adjustImpostorQualityPreset(terrain.getImpostorQualityPreset(), delta));
                break;
            case 5:
                terrain.setImpostorHighQualityDistance(terrain.getImpostorHighQualityDistance() + delta);
                break;
            case 6:
                terrain.setGrassDetailDistance(terrain.getGrassDetailDistance() + delta);
                break;
            case 7:
                terrain.setShadowRenderDistance(terrain.getShadowRenderDistance() + delta);
                break;
            case 8:
                toggleFullscreen();
                break;
            case 9:
                changeResolution(delta);
                break;
            case 10:
                vsyncEnabled = !vsyncEnabled;
                applyVSync();
                break;
            default:
                break;
        }
    }

    private int adjustImpostorAngleCount(int current, int delta) {
        int[] options = new int[] { 1, 4, 8 };
        int index = 0;
        for (int i = 0; i < options.length; i++) {
            if (options[i] == current) {
                index = i;
                break;
            }
        }
        int next = Math.max(0, Math.min(options.length - 1, index + delta));
        return options[next];
    }

    private int adjustImpostorQualityPreset(int current, int delta) {
        return Math.max(0, Math.min(2, current + delta));
    }

    private String getImpostorQualityLabel() {
        switch (terrain.getImpostorQualityPreset()) {
            case 2:
                return "High";
            case 1:
                return "Medium";
            default:
                return "Low";
        }
    }

    private void loop() {
        glEnable(GL_DEPTH_TEST);

        glEnable(GL_TEXTURE_2D);

        double lastTime = glfwGetTime();
        boolean prevT = false;
        boolean prevZ = false;
        boolean prevEsc = false;
        boolean prevF = false;
        boolean prev1 = false;
        boolean prev2 = false;
        boolean prev3 = false;
        boolean prev4 = false;
        boolean prev5 = false;
        boolean prev6 = false;
        boolean prevK = false;
        boolean prevO = false;

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

            boolean currF = glfwGetKey(window, GLFW_KEY_F) == GLFW_PRESS;
            if (currF && !prevF) {
                toggleFullscreen();
            }
            prevF = currF;

            boolean currK = glfwGetKey(window, GLFW_KEY_K) == GLFW_PRESS;
            if (currK && !prevK) {
                debugMenuOpen = !debugMenuOpen;
            }
            prevK = currK;

            boolean currO = glfwGetKey(window, GLFW_KEY_O) == GLFW_PRESS;
            if (currO && !prevO && debugMenuOpen) {
                exportDebugReport();
            }
            prevO = currO;
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

            setupProjection();
            glMatrixMode(GL_MODELVIEW);
            glLoadIdentity();
            player.applyView();
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
                    framebufferWidth,
                    framebufferHeight
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
            if (debugMenuOpen) {
                drawDebugOverlay(fbWidth, fbHeight);
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
