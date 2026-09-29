package monoforest.impl.query.factors;

import monoforest.impl.query.QueryFactor;
import org.apache.lucene.search.Query;

public class QueryQuantityConsonants extends QueryFactor {

    @Override
    public String getName() {
        return "quantity_consonants_in_query";
    }

    @Override
    public float[] calculateScore(String query) {
        float[] result = new float[1];
        result[0] = countConsonants(query);
        return result;
    }

    @Override
    public int getFeatureQty() {
        return 1;
    }

    @Override
    public String getDescription() {
        return "Считает количество согласных букв в запросе";
    }

    @Override
    public void prepare() {
        // Подготовка не требуется
    }

    @Override
    public Query buildQuery(String[] queryStream, int featureIndex, Object... args) {
        return null;
    }

    private int countConsonants(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int count = 0;
        // Строка согласных из вашего примера
        String consonants = "bcdfghjklmnpqrstvwxyzBCDFGHJKLMNPQRSTVWXYZ";

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            // Проверяем: если символ есть в строке согласных -> увеличиваем счетчик
            if (consonants.indexOf(c) != -1) {
                count++;
            }
        }
        return count;
    }
}