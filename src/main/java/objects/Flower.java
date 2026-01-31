package objects;
import renderers.ShadowRenderer;
import util.FeatureUtil;
import util.VertexColorBatchBuilder;

import static org.lwjgl.opengl.GL11.*;
import java.util.Random;

public class Flower extends Feature implements ColorBatchableFeature {
    private final FlowerType type;
    private final float[] petalAngles;
    private final float[] petalScales;
    private final float[] leafAngles;
    private final float[] leafSizes;
    private final Random rand;

    private int displayList = -1;
    private final float[] batchVertices;

    public Flower(float x, float y, float z, FlowerType type, long globalSeed) {
        super(x, y, z);
        this.type = type;

        long seed = FeatureUtil.hashSeed(x, y, z, globalSeed);
        this.rand = new Random(seed);

        int petalCount = 6;
        petalAngles = new float[petalCount];
        petalScales = new float[petalCount];
        for (int i = 0; i < petalCount; i++) {
            petalAngles[i] = rand.nextFloat() * 15f - 7.5f;
            petalScales[i] = 0.9f + rand.nextFloat() * 0.2f;
        }

        leafAngles = new float[] {
                rand.nextFloat() * 360f,
                rand.nextFloat() * 360f
        };
        leafSizes = new float[] {
                0.1f + rand.nextFloat() * 0.05f,
                0.1f + rand.nextFloat() * 0.05f
        };

        buildDisplayList();
        this.batchVertices = buildBatchVertices();
    }

    private void buildDisplayList() {
        displayList = glGenLists(1);
        glNewList(displayList, GL_COMPILE);

        // --- Stem ---
        float stemHeight = type.height;
        glColor3f(0.2f, 0.6f, 0.2f);
        glBegin(GL_QUADS);
        glVertex3f(-0.01f, 0, 0);
        glVertex3f( 0.01f, 0, 0);
        glVertex3f( 0.01f, stemHeight, 0);
        glVertex3f(-0.01f, stemHeight, 0);
        glEnd();

        // --- Leaves ---
        glColor3f(0.15f, 0.5f, 0.15f);
        for (int i = 0; i < leafAngles.length; i++) {
            glPushMatrix();
            glTranslatef(0, 0.1f, 0);
            glRotatef(leafAngles[i], 0, 1, 0);
            float s = leafSizes[i];

            glBegin(GL_TRIANGLES);
            glVertex3f(0, 0, 0);
            glVertex3f(-s, 0, s);
            glVertex3f(-s, 0, -s);
            glEnd();
            glPopMatrix();
        }

        // --- Bloom (petals) ---
        glTranslatef(0, stemHeight, 0);
        glColor3f(type.r, type.g, type.b);

        float petalLength = 0.14f;
        float petalWidth  = 0.06f;
        for (int i = 0; i < petalAngles.length; i++) {
            float baseAngle = (360f / petalAngles.length) * i;
            glPushMatrix();
            glRotatef(baseAngle + petalAngles[i], 0, 1, 0);

            glBegin(GL_POLYGON);
            glVertex3f(0, 0, 0);
            for (int j = 0; j <= 8; j++) {
                double θ = Math.PI * j / 8;
                float px = (float)Math.sin(θ) * petalWidth  * petalScales[i];
                float py = 0.05f         * (float)Math.cos(θ);
                float pz = petalLength   * petalScales[i];
                glVertex3f(px, py, pz);
            }
            glEnd();

            glPopMatrix();
        }

        // --- Center Bud ---
        glColor3f(1.0f, 0.9f, 0.1f);
        float budSize = 0.035f;
        glBegin(GL_TRIANGLE_FAN);
        glVertex3f(0, 0.02f, 0);
        for (int i = 0; i <= 12; i++) {
            double angle = 2 * Math.PI * i / 12;
            float bx = (float)Math.cos(angle) * budSize;
            float bz = (float)Math.sin(angle) * budSize;
            glVertex3f(bx, 0.02f, bz);
        }
        glEnd();

        glEndList();
    }

    @Override
    public void draw() {
        glPushMatrix();
        glTranslatef(x, y, z);
        ShadowRenderer.setUseTexture(false);
        glCallList(displayList);
        ShadowRenderer.setUseTexture(true);
        glPopMatrix();
    }

    @Override
    public void appendToColorBatch(VertexColorBatchBuilder builder) {
        builder.append(batchVertices, x, y, z);
    }

    @Override
    protected float getShadowRadius() {
        return 0.25f;
    }

    @Override
    protected float getShadowHeight() {
        return type.height;
    }

    @Override
    protected float getShadowAlpha() {
        return 0.25f;
    }

    @Override
    public void dispose() {
        if (displayList != -1) {
            glDeleteLists(displayList, 1);
        }
    }

    private float[] buildBatchVertices() {
        float width = 0.18f;
        float height = type.height;
        float half = width * 0.5f;
        float[] vertices = new float[6 * 6 * 2];
        int idx = 0;
        idx = putQuad(vertices, idx, -half, 0f, 0f, half, height, 0f, type.r, type.g, type.b);
        idx = putQuad(vertices, idx, 0f, 0f, -half, 0f, height, half, type.r, type.g, type.b);
        return vertices;
    }

    private int putQuad(float[] vertices, int idx,
                        float x1, float y1, float z1,
                        float x2, float y2, float z2,
                        float r, float g, float b) {
        idx = putVertex(vertices, idx, x1, y1, z1, r, g, b);
        idx = putVertex(vertices, idx, x2, y1, z2, r, g, b);
        idx = putVertex(vertices, idx, x2, y2, z2, r, g, b);
        idx = putVertex(vertices, idx, x1, y1, z1, r, g, b);
        idx = putVertex(vertices, idx, x2, y2, z2, r, g, b);
        idx = putVertex(vertices, idx, x1, y2, z1, r, g, b);
        return idx;
    }

    private int putVertex(float[] vertices, int idx, float x, float y, float z, float r, float g, float b) {
        vertices[idx++] = x;
        vertices[idx++] = y;
        vertices[idx++] = z;
        vertices[idx++] = r;
        vertices[idx++] = g;
        vertices[idx++] = b;
        return idx;
    }
}
