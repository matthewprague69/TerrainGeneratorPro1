package objects;

import util.FeatureUtil;
import util.TextureLoader;
import org.lwjgl.BufferUtils;
import util.VertexBatchBuilder;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;

import java.nio.FloatBuffer;
import java.util.Random;

public class Grass extends Feature implements BatchableFeature {
    private static final int BLADE_COUNT = 32;

    private static final float BLADE_HALF_BASE = 0.04f;
    private static final float BLADE_HALF_TOP = 0.015f;
    private static final float BLADE_BASE_HEIGHT = 1.2f;
    private static final float BLADE_HEIGHT_VARIATION = 0.6f;
    private static final float SPREAD = 1f; // Controls how wide blades spread out

    private final int textureId; // <--- INSTANCE, not static!
    private final Random rand;
    private final int vboId;
    private final boolean textured;
    private final float r, g, b;
    private final float[] verticesData;

    private static final FloatBuffer verticesBuffer =
            BufferUtils.createFloatBuffer(BLADE_COUNT * 8 * 5);

    public Grass(float x, float y, float z, String textureName, long globalSeed) {
        super(x, y, z);

        long seed = FeatureUtil.hashSeed(x, y, z, globalSeed);
        this.rand = new Random(seed);
        this.textured = true;
        this.r = 1f;
        this.g = 1f;
        this.b = 1f;

        this.textureId = TextureLoader.getOrLoad(textureName); // always per-instance load

        this.verticesData = new float[BLADE_COUNT * 8 * 5];
        generateGrassVertices();
        vboId = uploadToGPU();
    }

    public Grass(float x, float y, float z, float r, float g, float b, long globalSeed) {
        super(x, y, z);

        long seed = FeatureUtil.hashSeed(x, y, z, globalSeed);
        this.rand = new Random(seed);
        this.textured = false;
        this.r = r;
        this.g = g;
        this.b = b;
        this.textureId = -1; // no texture if manually colored

        this.verticesData = new float[BLADE_COUNT * 8 * 5];
        generateGrassVertices();
        vboId = uploadToGPU();
    }

    private int uploadToGPU() {
        int id = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, id);
        glBufferData(GL_ARRAY_BUFFER, verticesBuffer, GL_STATIC_DRAW);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        return id;
    }

    private void generateGrassVertices() {
        verticesBuffer.clear();

        int index = 0;
        for (int i = 0; i < BLADE_COUNT; i++) {
            float offsetX = (rand.nextFloat() - 0.5f) * SPREAD;
            float offsetZ = (rand.nextFloat() - 0.5f) * SPREAD;
            float height = BLADE_BASE_HEIGHT + rand.nextFloat() * BLADE_HEIGHT_VARIATION;
            float angle = rand.nextFloat() * 360f;
            float lean = (rand.nextFloat() - 0.5f) * 0.4f;

            for (int q = 0; q < 2; q++) {
                float a = (float) Math.toRadians(angle + q * 90f);
                float cos = (float) Math.cos(a);
                float sin = (float) Math.sin(a);

                index = putRotated(offsetX, offsetZ, -BLADE_HALF_BASE, 0f, cos, sin, 0f, 0f, index);
                index = putRotated(offsetX, offsetZ,  BLADE_HALF_BASE, 0f, cos, sin, 1f, 0f, index);
                index = putRotated(offsetX, offsetZ,  BLADE_HALF_TOP + lean, height, cos, sin, 1f, 1f, index);
                index = putRotated(offsetX, offsetZ, -BLADE_HALF_TOP + lean, height, cos, sin, 0f, 1f, index);
            }
        }

        verticesBuffer.flip();
    }

    private int putRotated(float offX, float offZ,
                            float localX, float localY,
                            float cos, float sin,
                            float u, float v,
                            int index) {
        float x = offX + localX * cos;
        float z = offZ + localX * sin;
        verticesBuffer.put(x).put(localY).put(z).put(u).put(v);
        verticesData[index++] = x;
        verticesData[index++] = localY;
        verticesData[index++] = z;
        verticesData[index++] = u;
        verticesData[index++] = v;
        return index;
    }

    @Override
    public void draw() {
        glPushMatrix();
        glTranslatef(x, y, z);

        if (textured && textureId != -1) {
            glEnable(GL_TEXTURE_2D);
            glBindTexture(GL_TEXTURE_2D, textureId);
            glColor3f(1f, 1f, 1f);
            glEnable(GL_BLEND);
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        } else {
            glDisable(GL_TEXTURE_2D);
            glColor3f(r, g, b);
            glDisable(GL_BLEND);
        }

        glBindBuffer(GL_ARRAY_BUFFER, vboId);
        glEnableClientState(GL_VERTEX_ARRAY);
        glEnableClientState(GL_TEXTURE_COORD_ARRAY);

        glVertexPointer(3, GL_FLOAT, 5 * 4, 0);
        glTexCoordPointer(2, GL_FLOAT, 5 * 4, 3 * 4);

        glDrawArrays(GL_QUADS, 0, BLADE_COUNT * 8);

        glDisableClientState(GL_TEXTURE_COORD_ARRAY);
        glDisableClientState(GL_VERTEX_ARRAY);
        glBindBuffer(GL_ARRAY_BUFFER, 0);

        if (textured && textureId != -1) {
            glDisable(GL_TEXTURE_2D);
            glDisable(GL_BLEND);
        }

        glPopMatrix();
    }

    @Override
    protected float getShadowRadius() {
        return 0.5f;
    }

    @Override
    protected float getShadowHeight() {
        return BLADE_BASE_HEIGHT + BLADE_HEIGHT_VARIATION;
    }

    @Override
    protected float getShadowAlpha() {
        return 0.2f;
    }

    @Override
    public void dispose() {
        glDeleteBuffers(vboId);
    }

    public void appendToBatch(VertexBatchBuilder builder) {
        builder.append(verticesData, x, y, z);
    }

    @Override
    public int getBatchTextureId() {
        return textureId;
    }
}
