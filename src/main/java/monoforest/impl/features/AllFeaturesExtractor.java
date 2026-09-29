package monoforest.impl.features;

import monoforest.impl.DocumentMarco;
import monoforest.impl.MyTokenizer;
import monoforest.impl.features.documentFeatures.*;
import monoforest.impl.features.queryFeatures.*;
import monoforest.impl.features.jointFeatures.*;
import org.apache.lucene.analysis.core.WhitespaceAnalyzer;
import org.apache.lucene.index.IndexReader;

import java.util.*;

/** All new families in a stable column order. One instance per worker; reader is borrowed. */
public final class AllFeaturesExtractor implements AutoCloseable {
    public static final int DEFAULT_TOP_N = 3;
    private final WhitespaceAnalyzer analyzer = new WhitespaceAnalyzer();
    private final MyTokenizer tokenizer = new MyTokenizer(analyzer);
    private final List<QueryFeatureFamily> queries = QueryFeatureFamilies.createDefault();
    private final List<DocumentFeatureFamily> documents = DocumentFeatureFamilies.createDefault();
    private final List<FeatureFamily> joint;
    private final BM25TopScoresFamily topScores;
    private final List<FeatureFamily> families = new ArrayList<>();
    private final List<String> names = new ArrayList<>();
    private String cachedQuery;
    private String[] cachedTokens;
    private List<FeatureBase> cachedQueryFeatures;
    private List<FeatureBase> cachedTopScores;
    private boolean prepared;

    public AllFeaturesExtractor(IndexReader reader, String field, int topN) {
        Objects.requireNonNull(reader, "reader");
        joint = Arrays.asList(new BM25Family(reader, field), new TFIDFFamily(reader, field),
                new ExactMatchFamily(), new TitleExactMatchFamily(), new UnorderedWindowFamily());
        topScores = new BM25TopScoresFamily(reader, field, topN);
        families.addAll(queries);
        families.addAll(documents);
        families.addAll(joint);
        families.add(topScores);
        for (FeatureFamily family : families) names.addAll(family.getAllFeaturesNames());
        if (new HashSet<>(names).size() != names.size()) {
            throw new IllegalStateException("Duplicate feature names");
        }
    }

    public void prepare() {
        if (prepared) return;
        for (FeatureFamily family : families) {
            // These counters only use tokens. Its legacy prepare() opens an unused reader.
            if (!(family instanceof UnorderedWindowFamily)) family.prepare();
        }
        prepared = true;
    }

    public List<String> getFeatureNames() { return Collections.unmodifiableList(names); }

    /** Zero-based TSV column, name, family; first three columns are metadata. */
    public List<String> getSchemaRows() {
        List<String> result = new ArrayList<>();
        int column = 3;
        for (FeatureFamily family : families) {
            for (String name : family.getAllFeaturesNames()) {
                result.add(column++ + "\t" + name + "\t" + family.getNameFamily());
            }
        }
        return result;
    }

    public float[] extract(String query, String docId, String title, String body) {
        if (!prepared) throw new IllegalStateException("Call prepare() first");
        Objects.requireNonNull(query, "query");
        if (!query.equals(cachedQuery)) {
            cachedTokens = tokenizer.tokenize(query).toArray(new String[0]);
            cachedQueryFeatures = new ArrayList<>();
            for (QueryFeatureFamily family : queries) {
                // Preserve original whitespace/punctuation for query character features.
                cachedQueryFeatures.addAll(family.calculateAllFeaturesInFamily(query));
            }
            cachedTopScores = topScores.calculateAllFeaturesInFamily(cachedTokens, null);
            cachedQuery = query;
        }
        DocumentMarco document = new DocumentMarco();
        document.setDoc_id(docId);
        document.setTitle(title == null ? "" : title);
        document.setBody(body == null ? "" : body);
        document.setTokensBody(tokenizer.tokenize(document.getBody()));
        document.tokenizeTitle(tokenizer);
        List<FeatureBase> features = new ArrayList<>(names.size());
        features.addAll(cachedQueryFeatures);
        for (FeatureFamily family : documents) {
            features.addAll(family.calculateAllFeaturesInFamily(cachedTokens, document));
        }
        for (FeatureFamily family : joint) {
            features.addAll(family.calculateAllFeaturesInFamily(cachedTokens, document));
        }
        features.addAll(cachedTopScores);
        if (features.size() != names.size()) throw new IllegalStateException("Feature count changed");
        float[] result = new float[features.size()];
        for (int i = 0; i < result.length; i++) {
            FeatureBase feature = features.get(i);
            if (!names.get(i).equals(feature.getName()) || !Float.isFinite(feature.value)) {
                throw new IllegalStateException("Invalid feature at column " + (i + 3) + ": " + names.get(i));
            }
            result[i] = feature.value;
        }
        return result;
    }

    @Override public void close() { analyzer.close(); }
}
