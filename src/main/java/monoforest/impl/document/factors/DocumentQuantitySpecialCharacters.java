package monoforest.impl.document.factors;

import monoforest.impl.document.DocumentFactor;
import org.apache.lucene.search.Query;

public class DocumentQuantitySpecialCharacters extends DocumentFactor {

    @Override
    public String getName() {
        return "DocumentQuantitySpecialCharacters";
    }

    @Override
    public float[] calculateScore(String title, String document, String doc_id) {

        String text = title +
                document;

        // Удаляем английские буквы, цифры и пробелы
        String specialChars = text.replaceAll("[a-zA-Z0-9\\s]", "");

        return new float[] { (float) specialChars.length() };
    }

    @Override
    public int getFeatureQty() {
        return 1;
    }

    @Override
    public String getDescription() {
        return "Количество специальных символов в документе (заголовок + текст)";
    }

    @Override
    public void prepare() {
    }

    @Override
    public Query buildQuery(String[] queryStream, int featureIndex, Object... args) {
        return null;
    }
}