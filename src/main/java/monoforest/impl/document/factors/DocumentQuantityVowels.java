package monoforest.impl.document.factors;

import monoforest.impl.document.DocumentFactor;
import org.apache.lucene.search.Query;

public class DocumentQuantityVowels extends DocumentFactor {

    @Override
    public String getName() {
        return "DocumentQuantityVowels";
    }

    @Override
    public float[] calculateScore(String title, String document, String doc_id) {
        float[] result = new float[1];
        // Считаем только для title, как просили
        result[0] = countVowels(document);
        return result;
    }

    @Override
    public int getFeatureQty() {
        return 1; // Возвращаем 1 число: кол-во гласных в заголовке
    }

    @Override
    public String getDescription() {
        return "Считает количество гласных в  документе.";
    }

    @Override
    public void prepare() {
        // Нет предварительной подготовки
    }

    @Override
    public Query buildQuery(String[] queryStream, int featureIndex, Object... args) {
        return null;
    }

    private int countVowels(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int count = 0;
        String vowels = "aeiouAEIOU";
        for (int i = 0; i < text.length(); i++) {
            if (vowels.indexOf(text.charAt(i)) != -1) {
                count++;
            }
        }
        return count;
    }
}