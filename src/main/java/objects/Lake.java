package objects;

import util.FeatureUtil;
import static org.lwjgl.opengl.GL11.*;
import java.util.Random;

public class Lake extends Feature {
    private final float radiusX;
    private final float radiusZ;
    private final float depth;
    private final Random rand;
    private final float baseHeight;
    private final int slices = 40;
    private final float[] shapeOffsets; // Precomputed wobble

    public Lake(float x, float y, float z, long globalSeed) {
        super(x, y, z);

        long seed = FeatureUtil.hashSeed(x, y, z, globalSeed);
        this.rand = new Random(seed);

        // Completely random lake size between 5f and 40f
        this.radiusX = 5f + rand.nextFloat() * 35f; // 5–40
        this.radiusZ = 5f + rand.nextFloat() * 35f; // 5–40

        this.depth = 1.5f + rand.nextFloat() * 3.5f; // 1.5–5 depth range (optionally adjust this)
        this.baseHeight = y - depth;

        this.shapeOffsets = new float[slices];
        for (int i = 0; i < slices; i++) {
            shapeOffsets[i] = 0.9f + rand.nextFloat() * 0.2f; // slight wobble between 0.9–1.1
        }
    }
    @Override

    public void draw() {
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glDisable(GL_TEXTURE_2D);
        glColor4f(0.2f, 0.5f, 0.8f, 0.6f);

        glBegin(GL_TRIANGLE_FAN);
        glNormal3f(0,1,0);
        glVertex3f(x, y, z);
        for (int i=0; i<=slices; i++) {
            int idx = i % slices;
            double angle = 2*Math.PI*idx/slices;
            float off = shapeOffsets[idx];
            float dx = (float)Math.cos(angle)*radiusX*off;
            float dz = (float)Math.sin(angle)*radiusZ*off;
            glVertex3f(x+dx, y, z+dz);
        }
        glEnd();

        glEnable(GL_TEXTURE_2D);
        glDisable(GL_BLEND);
    }




    public float getRadiusX() {
        return radiusX;
    }

    public float getRadiusZ() {
        return radiusZ;
    }

    public float getDepth() {
        return depth;
    }

    public float getBaseHeight() {
        return baseHeight;
    }

    public void lower(float amount) {
        this.y -= amount;
    }

    public float getShapeOffset(float angleRadians) {
        angleRadians = angleRadians % (2f * (float) Math.PI);
        if (angleRadians < 0) angleRadians += 2f * (float) Math.PI;

        float sliceAngle = (2f * (float) Math.PI) / slices;
        int index = (int) (angleRadians / sliceAngle) % slices;
        return shapeOffsets[index];
    }
}
