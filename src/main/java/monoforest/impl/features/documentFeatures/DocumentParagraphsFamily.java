package monoforest.impl.features.documentFeatures;

public class DocumentParagraphsFamily extends DocumentFeatureFamily {
    public static final String FIELD_PARAGRAPH_COUNT = "doc_paragraph_count";

    public DocumentParagraphsFamily() { super(FIELD_PARAGRAPH_COUNT); }
    @Override public String getNameFamily() { return "DocumentParagraphs"; }
    @Override public String getDescription() { return "Количество абзацев в body"; }

    @Override
    protected float[] calculateValues(String title, String body) {
        int count = 0;
        if (!body.trim().isEmpty()) {
            for (String paragraph : body.trim().split("\\n\\s*\\n")) {
                if (!paragraph.trim().isEmpty()) count++;
            }
        }
        return new float[]{count};
    }
}
