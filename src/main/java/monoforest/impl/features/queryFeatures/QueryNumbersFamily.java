package monoforest.impl.features.queryFeatures;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class QueryNumbersFamily extends QueryFeatureFamily {
    public static final String NUMBER_COUNT = "QueryNumberCount";
    private static final Pattern NUMBER_PATTERN = Pattern.compile("\\b\\d+(?:\\.\\d+)?\\b");

    public QueryNumbersFamily() { super(NUMBER_COUNT); }
    @Override public String getNameFamily() { return "QueryNumbers"; }
    @Override public String getDescription() { return "Количество чисел в запросе"; }

    @Override
    protected float[] calculateValues(String query) {
        int count = 0;
        Matcher matcher = NUMBER_PATTERN.matcher(query);
        while (matcher.find()) count++;
        return new float[]{count};
    }
}
