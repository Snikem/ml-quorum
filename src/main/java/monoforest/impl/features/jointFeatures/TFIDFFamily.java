package monoforest.impl.features.jointFeatures;

import monoforest.impl.AppConfig;
import monoforest.impl.DocumentMarco;
import monoforest.impl.features.FeatureBase;
import monoforest.impl.features.FeatureFamily;
import org.apache.lucene.index.*;
import org.apache.lucene.queries.function.*;
import org.apache.lucene.queries.function.docvalues.DoubleDocValues;
import org.apache.lucene.queries.function.valuesource.TermFreqValueSource;
import org.apache.lucene.search.*;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.BytesRef;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Paths;
import java.util.*;
import java.util.function.ToDoubleFunction;

/**
 * TF берется только из body. Токены DocumentMarco и запроса должны совпадать
 * с анализатором индексированного поля (в текущем проекте WhitespaceAnalyzer).
 * Для численного расчета и поиска нужно использовать одну версию индекса.
 */
public class TFIDFFamily implements FeatureFamily, AutoCloseable {
    public static final String TFIDF_SCORE = "TFIDFScore";

    public enum Comparison {
        GT, LT, GE, LE;

        public boolean test(double value, double threshold) {
            switch (this) {
                case GT: return value > threshold;
                case LT: return value < threshold;
                case GE: return value >= threshold;
                case LE: return value <= threshold;
                default: throw new AssertionError(this);
            }
        }
    }

    private final String field;
    private final Map<String, ToDoubleFunction<FeatureContext>> calculators = new LinkedHashMap<>();
    private IndexReader reader;
    private Directory ownedDirectory;
    private volatile QueryStats cachedQuery;

    public TFIDFFamily() {
        this(null, AppConfig.getTextField());
    }

    /** Переданный reader принадлежит вызывающему коду и здесь не закрывается. */
    public TFIDFFamily(IndexReader reader, String field) {
        this.reader = reader;
        this.field = Objects.requireNonNull(field, "field");
        calculators.put(TFIDF_SCORE, FeatureContext::tfidf);
    }

    @Override public String getNameFamily() { return "JointTFIDF"; }
    @Override public String getDescription() { return "Сумма TF(body) * IDF с учетом повторов слов запроса"; }
    @Override public List<String> getAllFeaturesNames() { return new ArrayList<>(calculators.keySet()); }

    @Override
    public void prepare() {
        if (reader != null) return;
        try {
            Directory directory = FSDirectory.open(Paths.get(AppConfig.getIndexDir()));
            try {
                reader = DirectoryReader.open(directory);
                ownedDirectory = directory;
            } catch (IOException | RuntimeException e) {
                directory.close();
                throw e;
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Не удалось открыть индекс TF-IDF", e);
        }
    }

    @Override
    public List<FeatureBase> calculateAllFeaturesInFamily(String[] tokens, DocumentMarco document) {
        FeatureContext context = new FeatureContext(queryStats(tokens), document);
        List<FeatureBase> result = new ArrayList<>(calculators.size());
        calculators.forEach((name, calculator) ->
                result.add(new FeatureBase(name, (float) calculator.applyAsDouble(context))));
        return result;
    }

    @Override
    public FeatureBase calculateFeatureByName(String name, String[] tokens, DocumentMarco document) {
        ToDoubleFunction<FeatureContext> calculator = requireFeature(name);
        return new FeatureBase(name, (float) calculator.applyAsDouble(new FeatureContext(queryStats(tokens), document)));
    }

    /** args: числовой порог, затем Comparison (по умолчанию GT). */
    @Override
    public Query buildLuceneQuery(String name, String[] tokens, Object... args) {
        requireFeature(name);
        if (args == null || args.length < 1 || args.length > 2 || !(args[0] instanceof Number)) {
            throw new IllegalArgumentException("Ожидаются: Number threshold [, Comparison]");
        }
        double threshold = ((Number) args[0]).doubleValue();
        if (!Double.isFinite(threshold)) throw new IllegalArgumentException("Порог должен быть конечным числом");
        if (args.length == 2 && !(args[1] instanceof Comparison)) {
            throw new IllegalArgumentException("Второй аргумент должен быть Comparison");
        }
        Comparison comparison = args.length == 2 ? (Comparison) args[1] : Comparison.GT;
        QueryStats stats = queryStats(tokens);
        ValueSource source = new TFIDFValueSource(field, stats);
        boolean lowerBound = comparison == Comparison.GT || comparison == Comparison.GE;
        boolean inclusive = comparison == Comparison.GE || comparison == Comparison.LE;
        Query range = new FunctionRangeQuery(source,
                lowerBound ? Double.valueOf(threshold) : null,
                lowerBound ? null : Double.valueOf(threshold),
                lowerBound && inclusive, !lowerBound && inclusive);

        // Документы без слов запроса имеют TF-IDF = 0. Исключаем их только тогда,
        // когда ноль не удовлетворяет условию; для LT с положительным порогом они нужны.
        if (comparison.test(0.0, threshold)) return range;
        if (stats.terms.length == 0) return new MatchNoDocsQuery();
        List<BytesRef> terms = new ArrayList<>(stats.terms.length);
        for (String term : stats.terms) terms.add(new BytesRef(term));
        return new BooleanQuery.Builder()
                .add(new TermInSetQuery(field, terms), BooleanClause.Occur.FILTER)
                .add(range, BooleanClause.Occur.MUST)
                .build();
    }

    private ToDoubleFunction<FeatureContext> requireFeature(String name) {
        ToDoubleFunction<FeatureContext> calculator = calculators.get(name);
        if (calculator == null) throw new IllegalArgumentException("Неизвестная фича: " + name);
        return calculator;
    }

    private QueryStats queryStats(String[] tokens) {
        Objects.requireNonNull(tokens, "query tokens");
        if (reader == null) throw new IllegalStateException("Сначала вызовите prepare()");
        QueryStats result = cachedQuery;
        if (result != null && Arrays.equals(result.tokens, tokens)) return result;
        try {
            // Ограниченный кеш: только последний запрос; никаких данных документов.
            result = new QueryStats(reader, field, tokens);
            cachedQuery = result;
            return result;
        } catch (IOException e) {
            throw new UncheckedIOException("Не удалось прочитать IDF", e);
        }
    }

    /** Общая основа для будущих производных: частоты терминов и статистика запроса. */
    private static final class FeatureContext {
        private final QueryStats query;
        private final int[] frequencies;
        private Float tfidf;

        FeatureContext(QueryStats query, DocumentMarco document) {
            this.query = query;
            frequencies = new int[query.terms.length];
            for (String token : Objects.requireNonNull(document.getTokensBody(), "Сначала токенизируйте body")) {
                Integer index = query.termIndices.get(token);
                if (index != null) frequencies[index]++;
            }
        }

        float tfidf() {
            if (tfidf == null) tfidf = query.score(frequencies);
            return tfidf;
        }
    }

    private static final class QueryStats {
        private final String[] tokens;
        private final String[] terms;
        private final Map<String, Integer> termIndices = new LinkedHashMap<>();
        private final int[] tokenIndices;
        private final double[] idfs;

        QueryStats(IndexReader reader, String field, String[] tokens) throws IOException {
            this.tokens = tokens.clone();
            tokenIndices = new int[tokens.length];
            for (int i = 0; i < tokens.length; i++) {
                String token = Objects.requireNonNull(tokens[i], "query token");
                Integer index = termIndices.get(token);
                if (index == null) {
                    index = termIndices.size();
                    termIndices.put(token, index);
                }
                tokenIndices[i] = index;
            }
            terms = termIndices.keySet().toArray(new String[0]);
            idfs = new double[terms.length];
            for (int i = 0; i < terms.length; i++) {
                int df = reader.docFreq(new Term(field, terms[i]));
                double n = (double) reader.numDocs() + 1.0;
                // Сохраняем также особую ветку df == 0 из старого класса.
                idfs[i] = df == 0 ? Math.log(n) : Math.log(n / (df + 1.0)) + 1.0;
            }
        }

        float score(int[] frequencies) {
            double sum = 0.0;
            // Порядок сложения и повторы совпадают со старым численным расчетом.
            for (int index : tokenIndices) sum += frequencies[index] * idfs[index];
            return (float) sum;
        }
    }

    private static final class TFIDFValueSource extends ValueSource {
        private final String field;
        private final QueryStats query;

        TFIDFValueSource(String field, QueryStats query) { this.field = field; this.query = query; }

        @Override
        public FunctionValues getValues(Map context, LeafReaderContext leaf) throws IOException {
            Terms terms = leaf.reader().terms(field);
            if (terms != null && !terms.hasFreqs()) {
                throw new IllegalStateException("Для TF-IDF нужны частоты в индексированном поле " + field);
            }
            FunctionValues[] termValues = new FunctionValues[query.terms.length];
            for (int i = 0; i < termValues.length; i++) {
                String term = query.terms[i];
                termValues[i] = new TermFreqValueSource(field, term, field, new BytesRef(term)).getValues(context, leaf);
            }
            return new DoubleDocValues(this) {
                private final int[] frequencies = new int[termValues.length];
                private int lastDoc = -1;
                private float lastScore;

                @Override
                public double doubleVal(int doc) throws IOException {
                    if (doc != lastDoc) {
                        for (int i = 0; i < termValues.length; i++) frequencies[i] = termValues[i].intVal(doc);
                        lastScore = query.score(frequencies);
                        lastDoc = doc;
                    }
                    // Сравниваем именно float-признак, но не округляем double-порог до float.
                    return lastScore;
                }
            };
        }

        @Override public String description() { return "tfidf(" + field + "," + Arrays.toString(query.tokens) + ")"; }
        @Override public int hashCode() {
            return Objects.hash(field, Arrays.hashCode(query.tokens), Arrays.hashCode(query.idfs));
        }
        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof TFIDFValueSource)) return false;
            TFIDFValueSource that = (TFIDFValueSource) other;
            return field.equals(that.field) && Arrays.equals(query.tokens, that.query.tokens)
                    && Arrays.equals(query.idfs, that.query.idfs);
        }
    }

    /** Вызывать после завершения всех расчетов, без одновременного поиска. */
    @Override
    public void close() throws IOException {
        try {
            if (ownedDirectory != null) reader.close();
        } finally {
            try {
                if (ownedDirectory != null) ownedDirectory.close();
            } finally {
                reader = null;
                ownedDirectory = null;
                cachedQuery = null;
            }
        }
    }
}
