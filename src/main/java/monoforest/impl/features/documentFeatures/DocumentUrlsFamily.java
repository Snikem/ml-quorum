package monoforest.impl.features.documentFeatures;

import java.util.regex.Pattern;

public class DocumentUrlsFamily extends DocumentFeatureFamily {
    public static final String FIELD_HAS_URL = "doc_has_url";
    private static final Pattern URL_PATTERN = Pattern.compile("https?://|www\\.");

    public DocumentUrlsFamily() { super(FIELD_HAS_URL); }
    @Override public String getNameFamily() { return "DocumentUrls"; }
    @Override public String getDescription() { return "Наличие URL в title + body"; }

    @Override
    protected float[] calculateValues(String title, String body) {
        // Конкатенация без разделителя и регистр сохранены из старого расчета.
        return new float[]{URL_PATTERN.matcher(title + body).find() ? 1f : 0f};
    }
}
