package monoforest.impl.document.factors;

import monoforest.impl.document.DocumentFactor;
import org.apache.lucene.search.Query;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DocumentQuantityNumbers extends DocumentFactor {

    private static final Pattern NUMBER_PATTERN = Pattern.compile("\\b\\d+(?:\\.\\d+)?\\b");

    @Override
    public String getName() {
        return "DocumentQuantityNumbers";
    }

    @Override
    public float[] calculateScore(String title, String document, String doc_id) {
        float[] result = new float[2];

        result[0] = countNumbers(title);    // Кол-во чисел в заголовке
        result[1] = countNumbers(document); // Кол-во чисел в документе

        return result;
    }

    @Override
    public int getFeatureQty() {
        return 2;
    }

    @Override
    public String getDescription() {
        return "Считает количество чисел в заголовке и документе";
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