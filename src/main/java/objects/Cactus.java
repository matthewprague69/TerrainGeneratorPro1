package objects;

import util.FeatureUtil;
import util.TextureLoader;

import static org.lwjgl.opengl.GL11.*;
import java.util.*;

public class Cactus extends Feature {
    private final List<Segment> segments = new ArrayList<>();
    private final int texture;
    private final Random rand;
    private final float maxHeight;

    private static final int SEGMENTS = 16; // More sides for rounder cactus

    public Cactus(float x, float y, float z, long globalSeed) {
        super(x, y, z);

        this.texture = TextureLoader.getOrLoad("cactus.png");

        long seed = FeatureUtil.hashSeed(x, y, z, globalSeed);
        this.rand = new Random(seed);

        float height = 2.5f + rand.nextFloat() * 2.5f; // Taller cactus
        segments.add(new Segment(height, 0.3f, 0f, 0f)); // main trunk

        // Main arms
        int arms = 1 + rand.nextInt(3); // More arms
        for (int i = 0; i < arms; i++) {
            float armHeight = 1.0f + rand.nextFloat() * 0.8f;
            float offsetY = 0.7f + rand.nextFloat() * (height - 1.5f);
            float angle = rand.nextFloat() * 360f;
            segments.add(new Segment(armHeight, 0.15f, offsetY, angle));

            // Maybe split the arm!
            if (rand.nextFloat() < 0.5f) {
                float splitHeight = 0.5f + rand.nextFloat() * 0.5f;
                segments.add(new Segment(splitHeight, 0.1f, offsetY + armHeight * 0.5f, angle + rand.nextFloat() * 60f - 30f));
            }
        }

        float maxSegmentHeight = 0f;
        for (Segment segment : segments) {
            maxSegmentHeight = Math.max(maxSegmentHeight, segment.offsetY + segment.height);
        }
        this.maxHeight = maxSegmentHeight;
    }

    @Override
    public void draw() {
        glPushMatrix();
        glTranslatef(x, y, z);

        glEnable(GL_TEXTURE_2D);
        glBindTexture(GL_TEXTURE_2D, texture);
        glColor3f(1f, 1f, 1f);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

        for (Segment seg : segments) {
            glPushMatrix();
            glTranslatef(0f, seg.offsetY, 0f);
            if (seg.angle != 0f) {
                glRotatef(seg.angle, 0f, 1f, 0f);
                glRotatef(45f, 0f, 0f, 1f); // lean
            }
            drawTexturedCylinder(seg.radius, seg.height);
            drawTopCap(seg.radius, seg.height);
            glPopMatrix();
        }

        glDisable(GL_TEXTURE_2D);
        glDisable(GL_BLEND);
        glPopMatrix();
    }

    @Override
    protected float getShadowRadius() {
        return 0.6f;
    }

    @Override
    protected float getShadowHeight() {
        return maxHeight;
    }

    @Override
    protected float getShadowAlpha() {
        return 0.45f;
    }

    private void drawTexturedCylinder(float baseRadius, float height) {
        glBegin(GL_QUAD_STRIP);
        for (int i = 0; i <= SEGMENTS; i++) {
            double angle = 2 * Math.PI * i / SEGMENTS;
            float x = (float) Math.cos(angle);
            float z = (float) Math.sin(angle);
            float u = (float) i / SEGMENTS;

            glTexCoord2f(u, 0);
            glVertex3f(x * baseRadius, 0, z * baseRadius);
            glTexCoord2f(u, 1);
            glVertex3f(x * baseRadius, height, z * baseRadius);
        }
        glEnd();
    }

    private void drawTopCap(float radius, float height) {
        glBegin(GL_TRIANGLE_FAN);
        glTexCoord2f(0.5f, 0.5f);
        glVertex3f(0, height, 0); // Center of top

        for (int i = 0; i <= SEGMENTS; i++) {
            double angle = 2 * Math.PI * i / SEGMENTS;
            float x = (float) Math.cos(angle) * radius;
            float z = (float) Math.sin(angle) * radius;
            glTexCoord2f(0.5f + x, 0.5f + z);
            glVertex3f(x, height, z); // Vertex around edge
        }
        glEnd();
    }

    @Override
    public void dispose() {
        // Nothing to dispose yet
    }

    private static class Segment {
        float height, radius;
        float offsetY;
        float angle;

        Segment(float height, float radius, float offsetY, float angle) {
            this.height = height;
            this.radius = radius;
            this.offsetY = offsetY;
            this.angle = angle;
        }
    }
}
