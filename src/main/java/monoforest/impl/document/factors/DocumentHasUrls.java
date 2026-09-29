package monoforest.impl.document.factors;

import monoforest.impl.document.DocumentFactor;
import org.apache.lucene.search.Query;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DocumentHasUrls extends DocumentFactor {

    // Регулярное выражение: https?:// или www.
    // В Java для экранирования точки нужен двойной слеш: \\.
    private static final Pattern URL_PATTERN = Pattern.compile("https?://|www\\.");

    @Override
    public String getName() {
        return "has_urls";
    }

    @Override
    public float[] calculateScore(String title, String document, String doc_id) {
        // Собираем текст
        StringBuilder sb = new StringBuilder();
        if (title != null) sb.append(title);
        if (document != null) sb.append(document);

        String text = sb.toString();

        // Ищем совпадение
        Matcher matcher = URL_PATTERN.matcher(text);
        boolean hasUrl = matcher.find();

        // Возвращаем 1.0 если есть URL, 0.0 если нет
        return new float[] { hasUrl ? 1.0f : 0.0f };
    }

    @Override
    public int getFeatureQty() {
        return 1;
    }

    @Override
    public String getDescription() {
        return "Наличие URL в заголовке или документе (1.0 - да, 0.0 - нет)";
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