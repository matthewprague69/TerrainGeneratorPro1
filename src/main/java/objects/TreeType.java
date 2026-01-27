package objects;

public enum TreeType {
        OAK(
                "bark_oak.png", "leaves_oak.png",
                3f, 7f, 0.75f, 2.5f,
                2, 4, 4, 30f,
                1, 1, TreeRenderStyle.DEFAULT),

        BIRCH(
                "bark_birch.png", "leaves_birch.png",
                4f, 6f, 0.4f, 2.0f,
                2, 3, 4, 25f,
                1, 1, TreeRenderStyle.DEFAULT),

        JUNGLE(
                "bark_jungle.png", "leaves_jungle.png",
                6f, 10f, 0.8f, 3.5f,
                3, 5, 5, 15f,
                2, 1, TreeRenderStyle.DEFAULT),

        BLOSSOM(
                "bark_blossom.png", "leaves_blossom.png",
                3f, 6f, 0.5f, 2.8f,
                2, 4, 4, 20f,
                2, 1, TreeRenderStyle.DEFAULT),

        GOLIATH_BLOSSOM(
                "bark_blossom.png", "leaves_blossom.png",
                30f, 70f, 9f, 20f,
                2, 2, 4, 20f,
                2, 3, TreeRenderStyle.DEFAULT),

        GIANT_OAK(
                "dark_bark.png", "leaves_oak.png",
                15f, 35f, 4f, 8f,
                2, 3, 4, 10f,
                2, 3, TreeRenderStyle.DEFAULT),

        RED_BERRY_BUSH(
                "bark_oak.png", "leaves_red_berry.png",
                0.5f, 1.3f, 0.2f, 1f,
                3, 5, 4, 25f,
                1, 0, TreeRenderStyle.DEFAULT),

        BUSH(
                "bark_oak.png", "leaves_bush.png",
                0.5f, 1.3f, 0.2f, 1f,
                3, 5, 4, 25f,
                1, 0, TreeRenderStyle.DEFAULT),

        BLOOMING_BUSH(
                "bark_oak.png", "leaves_blooming_bush.png",
                0.5f, 0.7f, 0.2f, 1f,
                3, 5, 4, 25f,
                1, 0, TreeRenderStyle.DEFAULT),

        SWAMP_TREE(
                "dark_bark.png", "leaves_bush.png",
                2f, 5f, 0.2f, 1f,
                4, 4, 4, 25f,
                1, 1, TreeRenderStyle.DEFAULT),

        SPRUCE(
                "bark_spruce.png", "leaves_spruce.png",
                6f, 12f, 0.5f, 2.5f,
                0, 0, 0, 0f,
                1, 6, TreeRenderStyle.SPRUCE);

        public final String trunkTex;
        public final String leafTex;
        public final float minHeight, maxHeight;
        public final float baseThickness;
        public final float leafSize;
        public final int minBranchCount, maxBranchCount;
        public final int maxBranchDepth;
        public final float trunkCurveFactor;
        public final int leafClusterSize;
        public final int trunkSegments;
        public final TreeRenderStyle renderStyle;

        TreeType(
                String trunkTex, String leafTex,
                float minHeight, float maxHeight,
                float baseThickness, float leafSize,
                int minBranchCount, int maxBranchCount,
                int maxBranchDepth, float trunkCurveFactor,
                int leafClusterSize, int trunkSegments,
                TreeRenderStyle renderStyle
        ) {
                this.trunkTex = trunkTex;
                this.leafTex = leafTex;
                this.minHeight = minHeight;
                this.maxHeight = maxHeight;
                this.baseThickness = baseThickness;
                this.leafSize = leafSize;
                this.minBranchCount = minBranchCount;
                this.maxBranchCount = maxBranchCount;
                this.maxBranchDepth = maxBranchDepth;
                this.trunkCurveFactor = trunkCurveFactor;
                this.leafClusterSize = leafClusterSize;
                this.trunkSegments = trunkSegments;
                this.renderStyle = renderStyle;
        }
}

