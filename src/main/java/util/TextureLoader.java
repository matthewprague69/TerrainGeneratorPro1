package util;

import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.HashMap;
import java.util.Map;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL30.*; // glGenerateMipmap
import static org.lwjgl.system.MemoryUtil.*;

public class TextureLoader {

    private static final Map<String, Integer> textureCache = new HashMap<>();

    // === SETTINGS ===
    private static final float LOD_BIAS = 1f; // Higher = blurrier but cheaper. Set to 0 for full quality
    private static final int DOWNSCALE_FACTOR = 1; // 1 = normal, 2 = half size, 4 = quarter size (etc.)

    /**
     * Loads or retrieves a texture from the cache.
     */
    public static int getOrLoad(String path) {
        return textureCache.computeIfAbsent("textures/" + path, TextureLoader::load);
    }

    /**
     * Loads a texture from disk using STB and configures OpenGL.
     */
    private static int load(String path) {
        int texID;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer width = stack.mallocInt(1);
            IntBuffer height = stack.mallocInt(1);
            IntBuffer comp = stack.mallocInt(1);

            try (var stream = TextureLoader.class.getClassLoader().getResourceAsStream(path)) {
                if (stream == null) {
                    throw new RuntimeException("Texture not found in resources: " + path);
                }

                byte[] bytes = stream.readAllBytes();
                ByteBuffer buffer = memAlloc(bytes.length);
                buffer.put(bytes).flip();

                STBImage.stbi_set_flip_vertically_on_load(true);
                ByteBuffer image = STBImage.stbi_load_from_memory(buffer, width, height, comp, 4);

                memFree(buffer);

                if (image == null) {
                    throw new RuntimeException(
                            "Failed to load texture: " + path + "\nReason: " + STBImage.stbi_failure_reason());
                }

                int originalWidth = width.get(0);
                int originalHeight = height.get(0);

                // Optional downscale
                int scaledWidth = originalWidth / DOWNSCALE_FACTOR;
                int scaledHeight = originalHeight / DOWNSCALE_FACTOR;
                ByteBuffer finalImage = image;

                if (DOWNSCALE_FACTOR > 1 && scaledWidth > 0 && scaledHeight > 0) {
                    finalImage = downscaleImage(image, originalWidth, originalHeight, scaledWidth, scaledHeight);
                    STBImage.stbi_image_free(image); // free original
                    width.put(0, scaledWidth);
                    height.put(0, scaledHeight);
                }

                texID = glGenTextures();
                glBindTexture(GL_TEXTURE_2D, texID);

                glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8,
                        width.get(0), height.get(0), 0,
                        GL_RGBA, GL_UNSIGNED_BYTE, finalImage);

                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
                glGenerateMipmap(GL_TEXTURE_2D);

                glTexParameterf(GL_TEXTURE_2D, GL_TEXTURE_LOD_BIAS, LOD_BIAS);

                float maxAniso = glGetFloat(0x84FF); // GL_MAX_TEXTURE_MAX_ANISOTROPY_EXT
                glTexParameterf(GL_TEXTURE_2D, 0x84FE, maxAniso); // GL_TEXTURE_MAX_ANISOTROPY_EXT

                STBImage.stbi_image_free(finalImage);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to load texture: " + path, e);
        }

        return texID;
    }

    /**
     * Downscales a loaded image to a new width/height.
     * Simple box filtering (averages 2x2 pixels).
     */
    private static ByteBuffer downscaleImage(ByteBuffer original, int origW, int origH, int newW, int newH) {
        int channels = 4; // RGBA
        ByteBuffer scaled = memAlloc(newW * newH * channels);

        for (int y = 0; y < newH; y++) {
            for (int x = 0; x < newW; x++) {
                int rSum = 0, gSum = 0, bSum = 0, aSum = 0;
                for (int dy = 0; dy < DOWNSCALE_FACTOR; dy++) {
                    for (int dx = 0; dx < DOWNSCALE_FACTOR; dx++) {
                        int srcX = x * DOWNSCALE_FACTOR + dx;
                        int srcY = y * DOWNSCALE_FACTOR + dy;
                        int srcIndex = (srcY * origW + srcX) * channels;

                        rSum += original.get(srcIndex) & 0xFF;
                        gSum += original.get(srcIndex + 1) & 0xFF;
                        bSum += original.get(srcIndex + 2) & 0xFF;
                        aSum += original.get(srcIndex + 3) & 0xFF;
                    }
                }

                int samples = DOWNSCALE_FACTOR * DOWNSCALE_FACTOR;
                scaled.put((byte) (rSum / samples));
                scaled.put((byte) (gSum / samples));
                scaled.put((byte) (bSum / samples));
                scaled.put((byte) (aSum / samples));
            }
        }
        scaled.flip();
        return scaled;
    }

    /**
     * Frees all loaded textures from OpenGL memory.
     * Call this on shutdown.
     */
    public static void disposeAll() {
        for (int tex : textureCache.values()) {
            glDeleteTextures(tex);
        }
        textureCache.clear();
    }
}
