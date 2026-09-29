package monoforest.impl.joint.factors;

import monoforest.impl.joint.JointFactor;
import org.apache.lucene.search.Query;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

public class JointQueryTfSum extends JointFactor {

    // Паттерн: всё, что НЕ буквы, НЕ цифры и НЕ пробелы (для очистки)
    private static final Pattern TOKENIZE_PATTERN = Pattern.compile("[^a-zA-Z0-9\\s]");

    @Override
    public String getName() {
        return "feature_query_tf_sum";
    }

    @Override
    public float[] calculateScore(String query, String title, String document, String doc_id) {
        // 1. Токенизация запроса (список слов, так как важны повторы в запросе, если они есть)
        String[] queryTokens = tokenize(query);

        // 2. Токенизация документа
        String[] docTokens = tokenize(document);

        // 3. Строим частотный словарь документа (аналог Python Counter)
        Map<String, Integer> docTf = new HashMap<>();
        for (String token : docTokens) {
            docTf.put(token, docTf.getOrDefault(token, 0) + 1);
        }

        // 4. Считаем сумму TF для слов запроса
        // Python: sum(doc_tf.get(term, 0) for term in query_tokens)
        float sumTf = 0;
        for (String term : queryTokens) {
            sumTf += docTf.getOrDefault(term, 0);
        }

        return new float[] { sumTf };
    }

    @Override
    public ArrayList<float[]> calculateForQueries(ArrayList<String> queries, String title, String document, String doc_id) {
        int numQueries = queries.size();

        ArrayList<float[]> result = new ArrayList<>(numQueries);

        if (document == null) document = "";

        String[] docTokens = tokenize(document);

        int mapCapacity = (int) (docTokens.length / 0.75f) + 1;
        Map<String, Integer> docTf = new HashMap<>(mapCapacity);

        for (String token : docTokens) {
            docTf.merge(token, 1, Integer::sum);
        }

        for (int i = 0; i < numQueries; i++) {
            String query = queries.get(i);

            String[] queryTokens = tokenize(query);

            float sumTf = 0;
            for (String term : queryTokens) {
                Integer count = docTf.get(term);
                if (count != null) {
                    sumTf += count;
                }
            }

            result.add(new float[] { sumTf });
        }

        return result;
    }

    @Override
    public int getFeatureQty() {
        return 1;
    }

    @Override
    public String getDescription() {
        return "Суммарная частота слов запроса в документе";
    }

    @Override
    public void prepare() {
        // Подготовка не требуется
    }

    @Override
    public Query buildQuery(String[] queryStream, int featureIndex, Object... args) {
        return null;
    }

    /**
     * Токенизация: lowercase -> удаление мусора -> split
     */
    private String[] tokenize(String text) {
        if (text == null || text.trim().isEmpty()) {
            return new String[0];
        }

        String lower = text.toLowerCase();
        String cleaned = TOKENIZE_PATTERN.matcher(lower).replaceAll(" ");
        return cleaned.trim().split("\\s+");
    }
}