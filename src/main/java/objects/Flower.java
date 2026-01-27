package objects;
import renderers.ShadowRenderer;
import util.FeatureUtil;

import static org.lwjgl.opengl.GL11.*;
import java.util.Random;

public class Flower extends Feature {
    private final FlowerType type;
    private final float[] petalAngles;
    private final float[] petalScales;
    private final float[] leafAngles;
    private final float[] leafSizes;
    private final Random rand;

    private int displayList = -1;

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
}
