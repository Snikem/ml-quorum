package monoforest.impl.features.jointFeatures;

import monoforest.impl.AppConfig;
import monoforest.impl.DocumentMarco;
import monoforest.impl.features.FeatureBase;
import monoforest.impl.features.FeatureFamily;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.*;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Paths;
import java.util.*;

/**
 * N признаков запроса: BM25 документов на позициях 1..N в выдаче.
 * DocumentMarco не используется: значения одинаковы для всех документов
 * при фиксированных запросе и индексе. Недостающие позиции заполняются нулями.
 * Токены запроса должны соответствовать анализатору поля body в индексе.
 */
public class BM25TopScoresFamily implements FeatureFamily, AutoCloseable {
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

    private final int topN;
    private final String field;
    private final Map<String, Integer> featurePositions = new LinkedHashMap<>();
    private IndexSearcher searcher;
    private Directory ownedDirectory;

    public BM25TopScoresFamily(int topN) {
        this(null, AppConfig.getTextField(), topN);
    }

    /** Переданный reader остается в собственности вызывающего кода. */
    public BM25TopScoresFamily(IndexReader reader, String field, int topN) {
        if (topN <= 0) throw new IllegalArgumentException("topN должен быть > 0");
        this.topN = topN;
        this.field = Objects.requireNonNull(field, "field");
        for (int position = 0; position < topN; position++) {
            featurePositions.put("BM25Top" + (position + 1) + "Score", position);
        }
        if (reader != null) initializeSearcher(reader);
    }

    private void initializeSearcher(IndexReader reader) {
        searcher = new IndexSearcher(reader);
        searcher.setSimilarity(new BM25Similarity(BM25Family.DEFAULT_K1, BM25Family.DEFAULT_B));
    }

    @Override public String getNameFamily() { return "BM25TopScores"; }
    @Override public String getDescription() { return "BM25 первых " + topN + " документов выдачи по запросу"; }
    @Override public List<String> getAllFeaturesNames() { return new ArrayList<>(featurePositions.keySet()); }

    @Override
    public synchronized void prepare() {
        if (searcher != null) return;
        try {
            Directory directory = FSDirectory.open(Paths.get(AppConfig.getIndexDir()));
            try {
                initializeSearcher(DirectoryReader.open(directory));
                ownedDirectory = directory;
            } catch (IOException | RuntimeException e) {
                directory.close();
                throw e;
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Не удалось открыть индекс BM25 top-n", e);
        }
    }

    @Override
    public List<FeatureBase> calculateAllFeaturesInFamily(String[] tokens, DocumentMarco document) {
        float[] scores = topScores(tokens);
        List<FeatureBase> result = new ArrayList<>(topN);
        featurePositions.forEach((name, position) -> result.add(new FeatureBase(name, scores[position])));
        return result;
    }

    @Override
    public FeatureBase calculateFeatureByName(String name, String[] tokens, DocumentMarco document) {
        int position = requirePosition(name);
        return new FeatureBase(name, topScores(tokens)[position]);
    }

    /**
     * args: Number threshold [, Comparison], по умолчанию GT.
     * Это условие на признак запроса, а не отбор первых N документов:
     * оно выполняется либо для всех документов, либо ни для одного.
     */
    @Override
    public Query buildLuceneQuery(String name, String[] tokens, Object... args) {
        int position = requirePosition(name);
        if (args == null || args.length < 1 || args.length > 2 || !(args[0] instanceof Number)) {
            throw new IllegalArgumentException("Ожидаются: Number threshold [, Comparison]");
        }
        double threshold = ((Number) args[0]).doubleValue();
        if (!Double.isFinite(threshold)) throw new IllegalArgumentException("Порог должен быть конечным числом");
        if (args.length == 2 && !(args[1] instanceof Comparison)) {
            throw new IllegalArgumentException("Второй аргумент должен быть Comparison");
        }
        Comparison comparison = args.length == 2 ? (Comparison) args[1] : Comparison.GT;
        float score = topScores(tokens)[position];
        return comparison.test(score, threshold) ? new MatchAllDocsQuery() : new MatchNoDocsQuery();
    }

    private int requirePosition(String name) {
        Integer position = featurePositions.get(name);
        if (position == null) throw new IllegalArgumentException("Неизвестная фича: " + name);
        return position;
    }

    private synchronized float[] topScores(String[] tokens) {
        Objects.requireNonNull(tokens, "query tokens");
        if (searcher == null) throw new IllegalStateException("Сначала вызовите prepare()");
        Map<String, Integer> frequencies = new LinkedHashMap<>();
        for (String token : tokens) frequencies.merge(Objects.requireNonNull(token, "query token"), 1, Integer::sum);
        float[] scores = new float[topN];
        if (!frequencies.isEmpty() && searcher.getIndexReader().numDocs() > 0) {
            BooleanQuery.Builder query = new BooleanQuery.Builder();
            frequencies.forEach((term, frequency) -> {
                Query termQuery = new TermQuery(new Term(field, term));
                if (frequency > 1) termQuery = new BoostQuery(termQuery, frequency.floatValue());
                query.add(termQuery, BooleanClause.Occur.SHOULD);
            });
            try {
                TopDocs hits = searcher.search(query.build(), Math.min(topN, searcher.getIndexReader().maxDoc()));
                for (int i = 0; i < hits.scoreDocs.length; i++) scores[i] = hits.scoreDocs[i].score;
            } catch (IOException e) {
                throw new UncheckedIOException("Не удалось получить BM25 top-n", e);
            }
        }
        return scores;
    }

    @Override
    public synchronized void close() throws IOException {
        try {
            if (ownedDirectory != null) searcher.getIndexReader().close();
        } finally {
            try {
                if (ownedDirectory != null) ownedDirectory.close();
            } finally {
                searcher = null;
                ownedDirectory = null;
            }
        }
    }
}
