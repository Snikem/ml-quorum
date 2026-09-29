package monoforest.impl.features.documentFeatures;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Один способ поиска чисел, примененный к title и body. */
public class DocumentNumbersFamily extends DocumentFeatureFamily {
    public static final String FIELD_TITLE_NUMBER_COUNT = "doc_title_number_count";
    public static final String FIELD_BODY_NUMBER_COUNT = "doc_body_number_count";
    private static final Pattern NUMBER_PATTERN = Pattern.compile("\\b\\d+(?:\\.\\d+)?\\b");

    public DocumentNumbersFamily() { super(FIELD_TITLE_NUMBER_COUNT, FIELD_BODY_NUMBER_COUNT); }
    @Override public String getNameFamily() { return "DocumentNumbers"; }
    @Override public String getDescription() { return "Количество чисел в title и body"; }

    @Override
    protected float[] calculateValues(String title, String body) {
        return new float[]{count(title), count(body)};
    }

    private static int count(String text) {
        int count = 0;
        Matcher matcher = NUMBER_PATTERN.matcher(text);
        while (matcher.find()) count++;
        return count;
    }
}
