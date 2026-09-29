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
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.search.similarities.Similarity.SimScorer;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.SmallFloat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Paths;
import java.util.*;
import java.util.function.ToDoubleFunction;

/**
 * BM25 Lucene 8.6 по body. Нужны частоты и norms, записанные BM25Similarity
 * (или совместимой реализацией). Токены должны совпадать с WhitespaceAnalyzer:
 * одно поле body, без перекрывающихся токенов и синонимов. При численном расчете
 * длина токенов кодируется в norm так же, как при индексировании.
 * Для численного расчета и поиска нужно использовать одну версию индекса.
 */
public class BM25Family implements FeatureFamily, AutoCloseable {
    public static final String BM25_SCORE = "BM25Score";
    public static final float DEFAULT_K1 = 1.2f;
    public static final float DEFAULT_B = 0.75f;

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
    private final BM25Similarity similarity;
    private final Map<String, ToDoubleFunction<FeatureContext>> calculators = new LinkedHashMap<>();
    private IndexReader reader;
    private Directory ownedDirectory;
    private volatile QueryStats cachedQuery;

    public BM25Family() {
        this(null, AppConfig.getTextField());
    }

    /** Переданный reader принадлежит вызывающему коду и здесь не закрывается. */
    public BM25Family(IndexReader reader, String field) {
        this(reader, field, DEFAULT_K1, DEFAULT_B);
    }

    public BM25Family(IndexReader reader, String field, float k1, float b) {
        similarity = new BM25Similarity(k1, b);
        this.reader = reader;
        this.field = Objects.requireNonNull(field, "field");
        calculators.put(BM25_SCORE, FeatureContext::bm25);
    }

    @Override public String getNameFamily() { return "JointBM25"; }
    @Override public String getDescription() { return "BM25(body) Lucene с учетом повторов слов запроса"; }
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
            throw new UncheckedIOException("Не удалось открыть индекс BM25", e);
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
        ValueSource source = new BM25ValueSource(field, stats);
        boolean lowerBound = comparison == Comparison.GT || comparison == Comparison.GE;
        boolean inclusive = comparison == Comparison.GE || comparison == Comparison.LE;
        Query range = new FunctionRangeQuery(source,
                lowerBound ? Double.valueOf(threshold) : null,
                lowerBound ? null : Double.valueOf(threshold),
                lowerBound && inclusive, !lowerBound && inclusive);

        // Документы без слов запроса имеют BM25 = 0. Исключаем их только тогда,
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
            result = new QueryStats(reader, field, tokens, similarity);
            cachedQuery = result;
            return result;
        } catch (IOException e) {
            throw new UncheckedIOException("Не удалось прочитать статистику BM25", e);
        }
    }

    /** Общая основа для будущих производных: частоты терминов и статистика запроса. */
    private static final class FeatureContext {
        private final QueryStats query;
        private final int[] frequencies;
        private final long norm;
        private Float bm25;

        FeatureContext(QueryStats query, DocumentMarco document) {
            this.query = query;
            frequencies = new int[query.terms.length];
            List<String> body = Objects.requireNonNull(document.getTokensBody(), "Сначала токенизируйте body");
            // Кодирование длины BM25Similarity.computeNorm для WhitespaceAnalyzer.
            norm = SmallFloat.intToByte4(body.size());
            for (String token : body) {
                Integer index = query.termIndices.get(token);
                if (index != null) frequencies[index]++;
            }
        }

        float bm25() {
            if (bm25 == null) bm25 = query.score(frequencies, norm);
            return bm25;
        }
    }

    private static final class QueryStats {
        private final IndexReader reader;
        private final String[] tokens;
        private final String[] terms;
        private final Map<String, Integer> termIndices = new LinkedHashMap<>();
        private final SimScorer[] scorers;
        private final float k1;
        private final float b;

        QueryStats(IndexReader reader, String field, String[] tokens, BM25Similarity similarity) throws IOException {
            this.reader = reader;
            this.tokens = tokens.clone();
            k1 = similarity.getK1();
            b = similarity.getB();
            Map<String, Integer> queryTF = new LinkedHashMap<>();
            for (String token : tokens) queryTF.merge(Objects.requireNonNull(token, "query token"), 1, Integer::sum);
            terms = queryTF.keySet().toArray(new String[0]);
            scorers = new SimScorer[terms.length];

            for (LeafReaderContext leaf : reader.leaves()) {
                FieldInfo info = leaf.reader().getFieldInfos().fieldInfo(field);
                if (info != null && (info.getIndexOptions().compareTo(IndexOptions.DOCS_AND_FREQS) < 0 || !info.hasNorms())) {
                    throw new IllegalStateException("Для BM25 нужны частоты и norms в поле " + field);
                }
            }
            IndexSearcher searcher = new IndexSearcher(reader);
            // docCount — документы с термами в поле, не numDocs всего индекса.
            CollectionStatistics collection = searcher.collectionStatistics(field);
            for (int i = 0; i < terms.length; i++) {
                termIndices.put(terms[i], i);
                Term term = new Term(field, terms[i]);
                int df = reader.docFreq(term);
                if (collection != null && df > 0) {
                    TermStatistics termStats = searcher.termStatistics(term, df, reader.totalTermFreq(term));
                    // Как BooleanQuery после rewrite: повторы TermQuery объединяются в boost.
                    scorers[i] = similarity.scorer(queryTF.get(terms[i]).floatValue(), collection, termStats);
                }
            }
        }

        float score(int[] frequencies, long norm) {
            double sum = 0.0;
            for (int i = 0; i < frequencies.length; i++) {
                if (frequencies[i] > 0 && scorers[i] != null) sum += scorers[i].score(frequencies[i], norm);
            }
            return (float) sum;
        }
    }

    private static final class BM25ValueSource extends ValueSource {
        private final String field;
        private final QueryStats query;

        BM25ValueSource(String field, QueryStats query) { this.field = field; this.query = query; }

        @Override
        public FunctionValues getValues(Map context, LeafReaderContext leaf) throws IOException {
            Terms terms = leaf.reader().terms(field);
            if (terms != null && !terms.hasFreqs()) {
                throw new IllegalStateException("Для BM25 нужны частоты в индексированном поле " + field);
            }
            NumericDocValues norms = leaf.reader().getNormValues(field);
            if (terms != null && norms == null) {
                throw new IllegalStateException("Для BM25 нужны norms в поле " + field);
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
                        long norm = norms != null && norms.advanceExact(doc) ? norms.longValue() : 1L;
                        lastScore = query.score(frequencies, norm);
                        lastDoc = doc;
                    }
                    // Сравниваем именно float-признак, но не округляем double-порог до float.
                    return lastScore;
                }
            };
        }

        @Override public String description() {
            return "bm25(" + field + "," + Arrays.toString(query.tokens) + ",k1=" + query.k1 + ",b=" + query.b + ")";
        }
        @Override public int hashCode() {
            return Objects.hash(field, System.identityHashCode(query.reader), Arrays.hashCode(query.tokens), query.k1, query.b);
        }
        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof BM25ValueSource)) return false;
            BM25ValueSource that = (BM25ValueSource) other;
            return field.equals(that.field) && Arrays.equals(query.tokens, that.query.tokens)
                    && query.reader == that.query.reader
                    && Float.compare(query.k1, that.query.k1) == 0 && Float.compare(query.b, that.query.b) == 0;
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
