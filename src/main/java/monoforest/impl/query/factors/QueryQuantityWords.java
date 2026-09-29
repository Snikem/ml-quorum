package monoforest.impl.query.factors;


import monoforest.impl.query.QueryFactor;
import org.apache.lucene.search.Query;

import java.util.Map;

public class QueryQuantityWords extends QueryFactor {

    @Override
    public String getName() {
        return "QuantityWordsFactor";
    }

    @Override
    public float[] calculateScore(String query) {
        float[] result = new float[1];

        result[0] = countWords(query);

        return result;
    }

    @Override
    public int getFeatureQty() {
        return 1;
    }

    @Override
    public String getDescription() {
        return "Считает количество слов в запросе";
    }

    @Override
    public void prepare() {

    }

    @Override
    public Query buildQuery(String[] queryStream, int featureIndex, Object... args) {
        return null;
    }

    private int countWords(String text) {
        if (text == null || text.trim().isEmpty()) {
            return 0;
        }
        // Делим по пробельным символам (пробел, таб, перенос строки)
        return text.trim().split("\\s+").length;
    }

    public Query buildQuery(float threshold, String query, int featureIndex){
        return null;
    }
}