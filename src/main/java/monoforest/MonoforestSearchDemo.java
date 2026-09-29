package monoforest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import monoforest.impl.AppConfig;
import org.apache.lucene.document.Document;
import java.util.Locale;

/** Run main in the IDE. Edit the settings below, or override with -Dmonoforest.* VM options.
 * The model must first be exported from model.ipynb to linear_monomials.json.
 */
public final class MonoforestSearchDemo {
    // ===== Настройки ручной проверки =====
    private static final String MODEL_PATH = AppConfig.getModelPath();
    private static final String INDEX_DIR = AppConfig.getIndexDir();
    private static final String QUERY = System.getProperty("monoforest.query", "grammar terms medicine");
    private static final double THRESHOLD = Double.parseDouble(System.getProperty("monoforest.threshold", "3.0"));
    private static final int TOP_K = Integer.parseInt(System.getProperty("monoforest.topK", "10"));
    private static final int SHOW_TOP = Integer.parseInt(System.getProperty("monoforest.showTop", "10"));
    private static final boolean SHOW_DOCUMENTS = Boolean.parseBoolean(System.getProperty("monoforest.showDocuments", "true"));
    private static final String RELEVANT_ID = System.getProperty("monoforest.relevantId", "");

    public static void main(String[] args) throws Exception {
        if (SHOW_TOP < 0) throw new IllegalArgumentException("showTop must be nonnegative");
        System.out.println("Модель: " + MODEL_PATH);
        System.out.println("Индекс: " + INDEX_DIR);
        System.out.println("Запрос: " + QUERY);
        System.out.println("Условие: raw score > " + THRESHOLD + "; максимум кандидатов: " + TOP_K);
        try (MonoforestSearch provider = new MonoforestSearch(
                INDEX_DIR, MODEL_PATH, THRESHOLD,
                AppConfig.getTextField(), AppConfig.getTitleField())) {
            long start = System.nanoTime();
            ObjectNode result = provider.search(QUERY, TOP_K);
            System.out.printf(Locale.ROOT, "Поиск: %.3f сек.; возвращено: %d; совпадений: %d (%s)%n",
                    (System.nanoTime() - start) / 1e9, result.path("returned").asInt(),
                    result.path("total_hits").asLong(), result.path("total_hits_relation").asText());
            System.out.println("Lucene: " + result.path("lucene_query").asText());
            int rank = 0, relevantRank = -1;
            for (JsonNode candidate : result.path("candidates")) {
                rank++;
                String id = candidate.path("doc_id").asText();
                if (id.equals(RELEVANT_ID)) relevantRank = rank;
                if (rank > SHOW_TOP) continue;
                System.out.printf(Locale.ROOT, "%n%d. id=%s  score=%.8f  probability=%.6f%n", rank, id,
                        candidate.path("score").asDouble(), candidate.path("probability").asDouble());
                if (SHOW_DOCUMENTS) {
                    Document doc = provider.getStoredDocument(id);
                    System.out.println("title: " + preview(doc.get(provider.getTitleFieldName()), 180));
                    System.out.println("body:  " + preview(doc.get(provider.getIndexFieldName()), 500));
                }
            }
            if (rank == 0) System.out.println("Нет кандидатов выше порога. Для расширения набора уменьшите THRESHOLD.");
            if (!RELEVANT_ID.isEmpty()) System.out.println("Позиция " + RELEVANT_ID + ": "
                    + (relevantRank < 0 ? "не вошёл в выбранный top-k выше порога" : relevantRank));
        }
    }

    private static String preview(String text, int length) {
        if (text == null) return "(поле не сохранено в индексе)";
        String compact = text.replaceAll("\\s+", " ");
        return compact.length() <= length ? compact : compact.substring(0, length) + "…";
    }
}
