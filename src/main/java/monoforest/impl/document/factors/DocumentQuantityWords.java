package monoforest.impl.document.factors;


import monoforest.impl.document.DocumentFactor;
import org.apache.lucene.search.Query;

public class DocumentQuantityWords extends DocumentFactor {

    @Override
    public String getName() {
        return "QuantityWordsFactor";
    }

    @Override
    public float[] calculateScore(String title, String document, String doc_id) {
        float[] result = new float[1];

        result[0] = countWords(title) + countWords(document);

        return result;
    }

    @Override
    public int getFeatureQty() {
        return 1; // Мы возвращаем 3 числа: len(query)
    }

    @Override
    public String getDescription() {
        return "Считает длину слов в запросе";
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
}