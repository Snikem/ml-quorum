package monoforest.impl.features.documentFeatures;

import monoforest.impl.DocumentMarco;
import monoforest.impl.features.FeatureBase;
import monoforest.impl.features.FeatureFamily;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.FloatPoint;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.search.MatchNoDocsQuery;
import org.apache.lucene.search.Query;

import java.util.*;

/** Общая запись числовых полей и диапазонные запросы для документных семейств. */
public abstract class DocumentFeatureFamily implements FeatureFamily {
    public enum Comparison { GT, LT, GE, LE }

    private final List<String> fields;

    protected DocumentFeatureFamily(String... fields) {
        this.fields = Collections.unmodifiableList(Arrays.asList(fields.clone()));
    }

    @Override public List<String> getAllFeaturesNames() { return fields; }
    @Override public void prepare() { }

    /** title и body нормализованы: вместо null передается пустая строка. */
    protected abstract float[] calculateValues(String title, String body);

    private float[] values(String title, String body) {
        float[] values = calculateValues(title == null ? "" : title, body == null ? "" : body);
        if (values.length != fields.size()) throw new IllegalStateException("Неверное количество фичей");
        return values;
    }

    @Override
    public List<FeatureBase> calculateAllFeaturesInFamily(String[] queryTokens, DocumentMarco document) {
        Objects.requireNonNull(document, "document");
        return asFeatures(values(document.getTitle(), document.getBody()));
    }

    @Override
    public FeatureBase calculateFeatureByName(String name, String[] queryTokens, DocumentMarco document) {
        int position = requirePosition(name);
        Objects.requireNonNull(document, "document");
        return new FeatureBase(name, values(document.getTitle(), document.getBody())[position]);
    }

    /** Вызывается индексатором перед writer.addDocument(). Пересчетов внутри цикла полей нет. */
    public void addToLuceneDocument(Document document, String title, String body) {
        Objects.requireNonNull(document, "document");
        float[] values = values(title, body);
        for (int i = 0; i < fields.size(); i++) {
            String field = fields.get(i);
            document.removeFields(field);
            document.add(new FloatPoint(field, values[i]));
            document.add(new StoredField(field, values[i]));
        }
    }

    /** Чтение готового вектора из searcher.doc(id), без словаря и повторной токенизации. */
    public List<FeatureBase> readFromLuceneDocument(Document document) {
        float[] values = new float[fields.size()];
        for (int i = 0; i < fields.size(); i++) {
            IndexableField field = document.getField(fields.get(i));
            if (field == null || field.numericValue() == null) {
                throw new IllegalStateException("Нет числового поля " + fields.get(i) + "; требуется переиндексация");
            }
            values[i] = field.numericValue().floatValue();
        }
        return asFeatures(values);
    }

    /** args: Number threshold [, Comparison], по умолчанию GT. Словарь для запросов не нужен. */
    @Override
    public Query buildLuceneQuery(String name, String[] queryTokens, Object... args) {
        requirePosition(name);
        if (args == null || args.length < 1 || args.length > 2 || !(args[0] instanceof Number)) {
            throw new IllegalArgumentException("Ожидаются: Number threshold [, Comparison]");
        }
        double threshold = ((Number) args[0]).doubleValue();
        if (!Double.isFinite(threshold)) throw new IllegalArgumentException("Порог должен быть конечным числом");
        if (args.length == 2 && !(args[1] instanceof Comparison)) {
            throw new IllegalArgumentException("Второй аргумент должен быть Comparison");
        }
        Comparison comparison = args.length == 2 ? (Comparison) args[1] : Comparison.GT;
        float boundary = (float) threshold;
        float lower = -Float.MAX_VALUE;
        float upper = Float.MAX_VALUE;
        // FloatPoint включает границы. Учитываем исходный double-порог, а не только его float-округление.
        switch (comparison) {
            case GT: lower = boundary <= threshold ? Math.nextUp(boundary) : boundary; break;
            case GE: lower = boundary < threshold ? Math.nextUp(boundary) : boundary; break;
            case LT: upper = boundary >= threshold ? Math.nextDown(boundary) : boundary; break;
            case LE: upper = boundary > threshold ? Math.nextDown(boundary) : boundary; break;
            default: throw new AssertionError(comparison);
        }
        if (lower > upper) return new MatchNoDocsQuery("Нет конечных float-значений в диапазоне");
        // Lucene различает -0 и +0, арифметические сравнения Java — нет.
        if (lower == 0f) lower = -0f;
        if (upper == 0f) upper = 0f;
        return FloatPoint.newRangeQuery(name, lower, upper);
    }

    private int requirePosition(String name) {
        int position = fields.indexOf(name);
        if (position < 0) throw new IllegalArgumentException("Неизвестная фича: " + name);
        return position;
    }

    private List<FeatureBase> asFeatures(float[] values) {
        List<FeatureBase> features = new ArrayList<>(fields.size());
        for (int i = 0; i < fields.size(); i++) features.add(new FeatureBase(fields.get(i), values[i]));
        return features;
    }

}
