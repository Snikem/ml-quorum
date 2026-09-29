package monoforest.impl.features.documentFeatures;

import java.nio.charset.StandardCharsets;

public class DocumentSizeFamily extends DocumentFeatureFamily {
    public static final String FIELD_SIZE_KBYTE = "doc_size_kbyte";

    public DocumentSizeFamily() { super(FIELD_SIZE_KBYTE); }
    @Override public String getNameFamily() { return "DocumentSize"; }
    @Override public String getDescription() { return "Размер title + body в UTF-8, килобайты"; }

    @Override
    protected float[] calculateValues(String title, String body) {
        return new float[]{(title + body).getBytes(StandardCharsets.UTF_8).length / 1024.0f};
    }
}
