package monoforest.impl.document.factors;

import monoforest.impl.document.DocumentFactor;
import org.apache.lucene.search.Query;

public class DocumentQuantityParagraphs extends DocumentFactor {

    @Override
    public String getName() {
        return "quantity_paragraphs";
    }

    @Override
    public float[] calculateScore(String title, String document, String doc_id) {
        // Если документа нет или он пустой
        if (document == null || document.trim().isEmpty()) {
            return new float[]{0f};
        }

        // Логика Python: document.strip() и split по r'\n\s*\n'
        // В Java двойной слеш для экранирования в строках: \\n\\s*\\n
        String[] paragraphs = document.trim().split("\\n\\s*\\n");

        // Логика Python: фильтруем пустые (if p.strip())
        int count = 0;
        for (String p : paragraphs) {
            if (!p.trim().isEmpty()) {
                count++;
            }
        }

        return new float[] { (float) count };
    }

    @Override
    public int getFeatureQty() {
        return 1;
    }

    @Override
    public String getDescription() {
        return "количество абзацев в документе";
    }

    @Override
    public void prepare() {
        // Нет предварительной подготовки
    }

    @Override
    public Query buildQuery(String[] queryStream, int featureIndex, Object... args) {
        return null;
    }
}