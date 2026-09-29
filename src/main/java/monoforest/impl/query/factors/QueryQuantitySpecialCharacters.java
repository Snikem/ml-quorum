package monoforest.impl.query.factors;

import monoforest.impl.query.QueryFactor;
import org.apache.lucene.search.Query;

public class QueryQuantitySpecialCharacters extends QueryFactor {

    @Override
    public String getName() {
        return "QueryQuantitySpecialCharacters";
    }

    @Override
    public float[] calculateScore(String query) {
        if (query == null) {
            return new float[]{0f};
        }

        // Логика Python: re.sub(r'[a-zA-Z0-9\s]', '', query)
        // Заменяем все a-z, A-Z, 0-9 и пробелы на пустоту. Оставшееся - спецсимволы.
        String specialChars = query.replaceAll("[a-zA-Z0-9\\s]", "");

        return new float[] { (float) specialChars.length() };
    }

    @Override
    public int getFeatureQty() {
        return 1;
    }

    @Override
    public String getDescription() {
        return "Количество специальных символов в запросе";
    }

    @Override
    public void prepare() {
    }

    @Override
    public Query buildQuery(String[] queryStream, int featureIndex, Object... args) {
        return null;
    }
}