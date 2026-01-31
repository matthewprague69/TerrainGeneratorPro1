package objects;

import util.VertexColorBatchBuilder;

public interface ColorBatchableFeature {
    void appendToColorBatch(VertexColorBatchBuilder builder);
}
