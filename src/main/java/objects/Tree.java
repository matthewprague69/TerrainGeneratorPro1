package objects;

import util.FeatureUtil;
import util.TextureLoader;

import static org.lwjgl.opengl.GL11.*;
import java.util.*;

public class Tree extends Feature {
    private final Branch root;
    private final Random rand;
    private static final int SEGMENTS = 12;

    private final int barkTex;
    private final int leafTex;
    private final TreeType type;
    private final boolean hasLeaves;
    private final float height;

    private static final Map<TreeType, Branch> spruceTemplateCache = new HashMap<>();
    private static final Map<TreeType, Integer> spruceDisplayLists = new HashMap<>();
    private static final Map<TreeType, Integer> spruceTrunkDisplayLists = new HashMap<>();
    private int displayList = -1; // Per-tree list (for normal trees)
    private int trunkDisplayList = -1;

    public Tree(float x, float y, float z, TreeType type, boolean hasLeaves, long globalSeed) {
        super(x, y, z);
        this.type = type;
        this.hasLeaves = hasLeaves;

        long seed = FeatureUtil.hashSeed(x, y, z, globalSeed);
        this.rand = new Random(seed);

        this.barkTex = TextureLoader.getOrLoad(type.trunkTex);
        this.leafTex = TextureLoader.getOrLoad(type.leafTex);

        this.height = type.minHeight + rand.nextFloat() * (type.maxHeight - type.minHeight);

        if (type.renderStyle == TreeRenderStyle.SPRUCE) {
            Branch template = getOrCreateSpruceTemplate(type, height);
            this.root = cloneBranch(template);
            buildSpruceDisplayListIfNeeded(type);
            buildSpruceTrunkDisplayListIfNeeded(type);
        } else {
            this.root = generateTrunk(height, type.baseThickness);
            buildDisplayList(true); // For normal trees
            buildDisplayList(false);
        }
    }

    public Tree(float x, float y, float z, TreeType type, long globalSeed) {
        this(x, y, z, type, true, globalSeed);
    }

    @Override
    public void dispose() {
        if (displayList != -1) {
            glDeleteLists(displayList, 1);
        }
        if (trunkDisplayList != -1) {
            glDeleteLists(trunkDisplayList, 1);
        }
        // Note: Spruce display lists are static/shared, we don't delete them here
    }

    private static Branch getOrCreateSpruceTemplate(TreeType type, float height) {
        return spruceTemplateCache.computeIfAbsent(type, t -> generateSpruceTemplate(t, height));
    }

    private static Branch generateSpruceTemplate(TreeType type, float totalHeight) {
        int trunkSegments = type.trunkSegments > 0 ? type.trunkSegments : 20;
        float segmentHeight = totalHeight / trunkSegments;
        float baseThickness = type.baseThickness;

        Branch root = new Branch();
        root.length = segmentHeight;
        root.thickness = baseThickness;
        root.rotX = 0f;
        root.rotZ = 0f;

        Branch current = root;

        for (int i = 1; i <= trunkSegments; i++) {
            Branch next = new Branch();
            next.length = segmentHeight;
            float taper = 1f - (i / (float) trunkSegments) * 0.5f;
            next.thickness = baseThickness * taper;
            next.rotX = 0f;
            next.rotZ = 0f;

            current.children.add(next);
            current = next;

            if (i < trunkSegments) {
                float branchLength = type.leafSize * (1f - i / (float) trunkSegments);

                for (int b = 0; b < 4; b++) {
                    float baseAngle = 90f * b;
                    for (int j = 0; j < 2; j++) {
                        Branch branch = new Branch();
                        branch.length = branchLength;
                        branch.thickness = baseThickness * 0.15f;
                        branch.rotZ = baseAngle + (j - 1) * 15f;
                        branch.rotX = -120f;
                        branch.applyOutwardTilt = true;

                        Branch split1 = new Branch();
                        split1.length = branchLength * 0.5f;
                        split1.thickness = baseThickness * 0.1f;
                        split1.rotZ = -15f;
                        split1.rotX = -30f;
                        split1.applyOutwardTilt = true;

                        Branch split2 = new Branch();
                        split2.length = branchLength * 0.5f;
                        split2.thickness = baseThickness * 0.1f;
                        split2.rotZ = 15f;
                        split2.rotX = -30f;
                        split2.applyOutwardTilt = true;

                        branch.children.add(split1);
                        branch.children.add(split2);

                        current.children.add(branch);
                    }
                }
            }
        }

        Branch topCluster = generateSpruceTopCluster(type.leafSize * 0.7f);
        current.children.add(topCluster);

        return root;
    }

    private static Branch generateSpruceTopCluster(float size) {
        Branch cluster = new Branch();
        cluster.length = -10f;
        cluster.thickness = 0f;
        cluster.rotX = 0f;
        cluster.rotZ = 0f;

        int layers = 3;
        for (int i = 0; i < layers; i++) {
            Branch ring = new Branch();
            float ringRadius = size * (1f - i * 0.4f);
            int planes = 6;

            for (int j = 0; j < planes; j++) {
                Branch leaf = new Branch();
                leaf.length = 0f;
                leaf.thickness = 0f;
                leaf.rotX = 0f;
                leaf.rotZ = (360f / planes) * j;
                ring.children.add(leaf);
            }
            cluster.children.add(ring);
        }

        Branch top = new Branch();
        cluster.children.add(top);

        return cluster;
    }

    private static Branch cloneBranch(Branch original) {
        Branch copy = new Branch();
        copy.length = original.length;
        copy.thickness = original.thickness;
        copy.rotX = original.rotX;
        copy.rotZ = original.rotZ;
        copy.applyOutwardTilt = original.applyOutwardTilt;
        for (Branch child : original.children) {
            copy.children.add(cloneBranch(child));
        }
        return copy;
    }

    private void buildSpruceDisplayListIfNeeded(TreeType type) {
        if (!spruceDisplayLists.containsKey(type)) {
            int list = glGenLists(1);
            glNewList(list, GL_COMPILE);
            drawBranchRecursive(root, 0, true);
            glEndList();
            spruceDisplayLists.put(type, list);
        }
    }

    private void buildSpruceTrunkDisplayListIfNeeded(TreeType type) {
        if (!spruceTrunkDisplayLists.containsKey(type)) {
            int list = glGenLists(1);
            glNewList(list, GL_COMPILE);
            drawBranchRecursive(root, 0, false);
            glEndList();
            spruceTrunkDisplayLists.put(type, list);
        }
    }

    private void buildDisplayList(boolean drawLeaves) {
        int list = glGenLists(1);
        glNewList(list, GL_COMPILE);
        drawBranchRecursive(root, 0, drawLeaves);
        glEndList();
        if (drawLeaves) {
            displayList = list;
        } else {
            trunkDisplayList = list;
        }
    }

    private Branch generateTrunk(float totalHeight, float thickness) {
        if (type.trunkSegments <= 0) {
            Branch root = new Branch();
            root.length = 0f;
            root.thickness = thickness;
            root.rotX = 0f;
            root.rotZ = 0f;

            int branchCount = type.minBranchCount + rand.nextInt(type.maxBranchCount - type.minBranchCount + 1);
            for (int i = 0; i < branchCount; i++) {
                float branchLength = totalHeight * (0.6f + rand.nextFloat() * 0.4f);
                float branchThickness = thickness * 0.6f;
                root.children.add(generateBranch(1, branchLength, branchThickness));
            }
            return root;
        }

        float segmentHeight = totalHeight / type.trunkSegments;
        float baseTiltX = (rand.nextFloat() * 2f - 1f) * type.trunkCurveFactor * 0.4f;
        float baseTiltZ = (rand.nextFloat() * 2f - 1f) * type.trunkCurveFactor * 0.4f;

        Branch root = new Branch();
        root.length = segmentHeight;
        root.thickness = thickness;
        root.rotX = baseTiltX;
        root.rotZ = baseTiltZ;

        Branch current = root;
        for (int i = 1; i < type.trunkSegments; i++) {
            Branch next = new Branch();
            next.length = segmentHeight;
            float taper = 1f - (i / (float) type.trunkSegments) * 0.4f;
            next.thickness = thickness * taper;
            next.rotX = baseTiltX;
            next.rotZ = baseTiltZ;

            current.children.add(next);
            current = next;
        }

        float lastSegmentLength = current.length;
        int branchCount = type.minBranchCount + rand.nextInt(type.maxBranchCount - type.minBranchCount + 1);
        for (int i = 0; i < branchCount; i++) {
            float branchLength = lastSegmentLength * (0.6f + rand.nextFloat() * 0.4f);
            float branchThickness = thickness * 0.6f;
            current.children.add(generateBranch(1, branchLength, branchThickness));
        }

        return root;
    }

    private Branch generateBranch(int depth, float length, float thickness) {
        Branch b = new Branch();
        b.length = length;
        b.thickness = thickness;
        float maxSpreadAngle = 90f;
        b.rotX = (depth == 0) ? 0 : rand.nextFloat() * maxSpreadAngle - maxSpreadAngle / 2f;
        b.rotZ = (depth == 0) ? 0 : rand.nextFloat() * maxSpreadAngle - maxSpreadAngle / 2f;
        b.applyOutwardTilt = true;

        if (depth < type.maxBranchDepth && thickness > 0.05f) {
            int count = type.minBranchCount + rand.nextInt(type.maxBranchCount - type.minBranchCount + 1);
            for (int i = 0; i < count; i++) {
                float newLength = length * (0.6f + rand.nextFloat() * 0.3f);
                float newThickness = thickness * 0.6f;
                b.children.add(generateBranch(depth + 1, newLength, newThickness));
            }
        }

        return b;
    }

    @Override
    public void draw() {
        glPushMatrix();
        glTranslatef(x, y, z);

        glEnable(GL_TEXTURE_2D);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glDepthMask(true);
        glEnable(GL_ALPHA_TEST);
        glAlphaFunc(GL_GREATER, 0.5f);
        glColor4f(1f, 1f, 1f, 1f);

        if (type.renderStyle == TreeRenderStyle.SPRUCE) {
            glCallList(spruceDisplayLists.get(type));
        } else {
            glCallList(displayList);
        }

        glDisable(GL_ALPHA_TEST);
        glDisable(GL_BLEND);
        glDisable(GL_TEXTURE_2D);
        glPopMatrix();
    }

    @Override
    public void drawSimplified() {
        glPushMatrix();
        glTranslatef(x, y, z);

        glEnable(GL_TEXTURE_2D);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glDepthMask(true);
        glEnable(GL_ALPHA_TEST);
        glAlphaFunc(GL_GREATER, 0.5f);
        glColor4f(1f, 1f, 1f, 1f);

        if (type.renderStyle == TreeRenderStyle.SPRUCE) {
            glCallList(spruceTrunkDisplayLists.get(type));
        } else {
            glCallList(trunkDisplayList);
        }

        glDisable(GL_ALPHA_TEST);
        glDisable(GL_BLEND);
        glDisable(GL_TEXTURE_2D);
        glPopMatrix();
    }

    @Override
    public void drawDepth() {
        draw();
    }

    @Override
    protected float getShadowRadius() {
        return Math.max(type.leafSize * 0.7f, type.baseThickness * 1.8f);
    }

    @Override
    protected float getShadowHeight() {
        return height;
    }

    @Override
    protected float getShadowAlpha() {
        return 0.5f;
    }

    private void drawBranchRecursive(Branch branch, int depth, boolean drawLeaves) {
        glPushMatrix();

        if (branch.applyOutwardTilt) {
            glRotatef(branch.rotZ, 0, 1, 0);
            glRotatef(branch.rotX, 1, 0, 0);
        } else {
            glRotatef(branch.rotX, 1, 0, 0);
            glRotatef(branch.rotZ, 0, 0, 1);
        }

        if (branch.length > 0) {
            glBindTexture(GL_TEXTURE_2D, barkTex);
            drawTexturedCylinder(branch.thickness, branch.length);
            glTranslatef(0, branch.length, 0);
        }

        if (branch.children.isEmpty()) {
            if (drawLeaves) {
                if (type.renderStyle == TreeRenderStyle.SPRUCE) {
                    drawSpruceLeafRing(type.leafSize * 0.5f);
                } else if (hasLeaves && depth >= 3) {
                    drawCrownLeafPlanes();
                }
            }
        } else {
            for (Branch child : branch.children) {
                drawBranchRecursive(child, depth + 1, drawLeaves);
            }
        }

        glPopMatrix();
    }

    private void drawTexturedCylinder(float baseRadius, float height) {
        float topRadius = baseRadius * 0.8f;
        glBegin(GL_QUAD_STRIP);
        for (int i = 0; i <= SEGMENTS; i++) {
            double angle = 2 * Math.PI * i / SEGMENTS;
            float x = (float) Math.cos(angle);
            float z = (float) Math.sin(angle);
            float u = (float) i / SEGMENTS;

            glTexCoord2f(u, 0);
            glVertex3f(x * baseRadius, 0, z * baseRadius);
            glTexCoord2f(u, 1);
            glVertex3f(x * topRadius, height, z * topRadius);
        }
        glEnd();
    }

    private void drawCrownLeafPlanes() {
        glBindTexture(GL_TEXTURE_2D, leafTex);
        float radius = type.leafSize * 0.5f;

        int rings = 1;
        int leavesPerRing = 2;
        float ringHeightStep = radius * 0.5f;

        for (int r = 0; r < rings; r++) {
            float yOffset = r * ringHeightStep;
            float angleOffset = (r % 2 == 0) ? 0 : (float) Math.PI / leavesPerRing;

            for (int i = 0; i < leavesPerRing; i++) {
                float angle = (float) (2 * Math.PI * i / leavesPerRing) + angleOffset;
                float dx = (float) Math.cos(angle) * radius;
                float dz = (float) Math.sin(angle) * radius;

                glPushMatrix();
                glTranslatef(dx, yOffset, dz);
                glRotatef((float) Math.toDegrees(angle), 0, 1, 0);
                glRotatef(90f, 1, 0, 0);
                drawLeafPlane(radius);
                glPopMatrix();
            }
        }

        glPushMatrix();
        glTranslatef(0, rings * ringHeightStep + radius * 0.5f, 0);
        glRotatef(90f, 1, 0, 0);
        drawLeafPlane(radius);
        glPopMatrix();
    }

    private void drawSpruceLeafRing(float radius) {
        glBindTexture(GL_TEXTURE_2D, leafTex);
        for (int i = 0; i < 3; i++) {
            float angle = i * 190f;
            glPushMatrix();
            glRotatef(angle, 0, 1, 0);
            glTranslatef(radius, 0, 0);
            glRotatef(190f, 1, 0, 0);
            drawLeafPlane(radius * 0.5f);
            glPopMatrix();
        }
    }

    private void drawLeafPlane(float radius) {
        int segments = 20;
        glBegin(GL_TRIANGLE_FAN);
        glTexCoord2f(0.5f, 0.5f);
        glVertex3f(0, 0, 0);
        for (int i = 0; i <= segments; i++) {
            double angle = 2 * Math.PI * i / segments;
            float x = (float) Math.cos(angle) * radius;
            float y = (float) Math.sin(angle) * radius;
            glTexCoord2f(0.5f + x / (2 * radius), 0.5f + y / (2 * radius));
            glVertex3f(x, y, 0);
        }
        glEnd();
    }

    public boolean collidesWith(float px, float py, float pz) {
        float dx = px - x;
        float dz = pz - z;
        float distSq = dx * dx + dz * dz;
        float radius = type.baseThickness * 1.1f;
        return distSq < radius * radius && Math.abs(py - y) < type.maxHeight;
    }

    private static class Branch {
        float length, thickness;
        float rotX, rotZ;
        List<Branch> children = new ArrayList<>();
        boolean applyOutwardTilt = false;
    }
}
