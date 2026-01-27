package objects;

import util.VertexBatchBuilder;

public interface BatchableFeature {
    void appendToBatch(VertexBatchBuilder builder);
    int getBatchTextureId();
}
