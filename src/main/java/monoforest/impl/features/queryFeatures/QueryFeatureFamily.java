package monoforest.impl.features.queryFeatures;

import monoforest.impl.DocumentMarco;
import monoforest.impl.features.FeatureBase;
import monoforest.impl.features.FeatureFamily;
import org.apache.lucene.search.Query;

import java.util.*;

/** Запросные фичи без индекса и без зависимости от документа. */
public abstract class QueryFeatureFamily implements FeatureFamily {
    private final List<String> names;

    protected QueryFeatureFamily(String... names) {
        this.names = Collections.unmodifiableList(Arrays.asList(names.clone()));
    }

    @Override public List<String> getAllFeaturesNames() { return names; }
    @Override public void prepare() { }
    protected abstract float[] calculateValues(String query);

    /** Исходная строка сохраняет пунктуацию и пробелы для точного расчета старых фичей. */
    public List<FeatureBase> calculateAllFeaturesInFamily(String query) {
        float[] values = calculateValues(query == null ? "" : query);
        if (values.length != names.size()) throw new IllegalStateException("Неверное количество фичей");
        List<FeatureBase> result = new ArrayList<>(names.size());
        for (int i = 0; i < names.size(); i++) result.add(new FeatureBase(names.get(i), values[i]));
        return result;
    }

    public FeatureBase calculateFeatureByName(String name, String query) {
        int position = names.indexOf(name);
        if (position < 0) throw new IllegalArgumentException("Неизвестная фича: " + name);
        return calculateAllFeaturesInFamily(query).get(position);
    }

    /** Адаптер: считает для строки из токенов через пробел, а не восстанавливает исходный запрос. */
    @Override
    public List<FeatureBase> calculateAllFeaturesInFamily(String[] queryTokens, DocumentMarco document) {
        return calculateAllFeaturesInFamily(joinTokens(queryTokens));
    }

    @Override
    public FeatureBase calculateFeatureByName(String name, String[] queryTokens, DocumentMarco document) {
        return calculateFeatureByName(name, joinTokens(queryTokens));
    }

    private static String joinTokens(String[] tokens) {
        Objects.requireNonNull(tokens, "queryTokens");
        for (String token : tokens) Objects.requireNonNull(token, "query token");
        return String.join(" ", tokens);
    }

    @Override
    public final Query buildLuceneQuery(String name, String[] queryTokens, Object... args) {
        throw new UnsupportedOperationException("Запросные семейства только вычисляют признаки; Lucene-запрос не строится");
    }
}
