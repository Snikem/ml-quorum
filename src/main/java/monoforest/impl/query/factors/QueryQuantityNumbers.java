package monoforest.impl.query.factors;

import monoforest.impl.query.QueryFactor;
import org.apache.lucene.search.Query;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class QueryQuantityNumbers extends QueryFactor {

    // Компилируем паттерн один раз для скорости
    // В Java двойные слеши для экранирования: \\b\\d+(?:\\.\\d+)?\\b
    private static final Pattern NUMBER_PATTERN = Pattern.compile("\\b\\d+(?:\\.\\d+)?\\b");

    @Override
    public String getName() {
        return "QueryQuantityNumbers";
    }

    @Override
    public float[] calculateScore(String query) {
        float[] result = new float[1];
        result[0] = countNumbers(query);
        return result;
    }

    @Override
    public int getFeatureQty() {
        return 1;
    }

    @Override
    public String getDescription() {
        return "Считает количество чисел в запросе";
    }

    @Override
    public void prepare() {
    }

    @Override
    public Query buildQuery(String[] queryStream, int featureIndex, Object... args) {
        return null;
    }

    private int countNumbers(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int count = 0;
        Matcher matcher = NUMBER_PATTERN.matcher(text);
        while (matcher.find()) {
            count++;
        }
        return count;
    }
}