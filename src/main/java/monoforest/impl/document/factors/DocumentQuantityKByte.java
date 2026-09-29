package monoforest.impl.document.factors;

import monoforest.impl.document.DocumentFactor;
import org.apache.lucene.search.Query;

import java.nio.charset.StandardCharsets;

public class DocumentQuantityKByte extends DocumentFactor {

    @Override
    public String getName() {
        return "quantity_document_kbyte";
    }

    @Override
    public float[] calculateScore(String title, String document, String doc_id) {
        // Логика Python: text = (title or "") + (document or "")
        StringBuilder sb = new StringBuilder();
        if (title != null) sb.append(title);
        if (document != null) sb.append(document);

        String text = sb.toString();

        // Логика Python: len(text.encode('utf-8'))
        // Важно использовать именно UTF-8, чтобы размер совпадал с Python
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);

        // Перевод в килобайты
        float sizeKb = bytes.length / 1024.0f;

        return new float[] { sizeKb };
    }

    @Override
    public int getFeatureQty() {
        return 1;
    }

    @Override
    public String getDescription() {
        return "Длина документа (заголовок + текст) в килобайтах";
    }

    @Override
    public void prepare() {
        // Подготовка не требуется
    }

    @Override
    public Query buildQuery(String[] queryStream, int featureIndex, Object... args) {
        return null;
    }

}