package monoforest.impl;

import monoforest.impl.features.*;
import monoforest.impl.features.documentFeatures.*;
import monoforest.impl.features.queryFeatures.*;
import monoforest.impl.features.jointFeatures.*;
import org.apache.lucene.analysis.core.WhitespaceAnalyzer;
import org.apache.lucene.index.*;
import org.apache.lucene.search.*;
import org.apache.lucene.queries.function.*;
import org.apache.lucene.queries.function.docvalues.DoubleDocValues;
import org.apache.lucene.util.BytesRef;
import java.io.IOException;
import java.util.*;

/** Feature > border using the same features and tokenization as AllFeaturesExtractor.
 * Counts are computed from postings/positions, never from BM25 clause scores.
 * Construct one instance per request; the IndexReader is borrowed.
 */
public final class MonomialFeatureQueries {
    private final IndexReader reader;
    private final String rawQuery, textField, titleField;
    private final String[] tokens;
    private final Map<String, FeatureFamily> families = new HashMap<>();
    private final Map<String, Float> queryValues = new HashMap<>();
    private final Map<String, Query> cache = new HashMap<>();
    private final Set<FeatureFamily> prepared = new HashSet<>();

    public MonomialFeatureQueries(IndexReader reader, String query, String textField, String titleField) {
        this.reader = Objects.requireNonNull(reader);
        this.rawQuery = Objects.requireNonNull(query);
        this.textField = Objects.requireNonNull(textField);
        this.titleField = Objects.requireNonNull(titleField);
        try (WhitespaceAnalyzer analyzer = new WhitespaceAnalyzer()) {
            tokens = new MyTokenizer(analyzer).tokenize(query).toArray(new String[0]);
        }
        List<FeatureFamily> list = new ArrayList<>(DocumentFeatureFamilies.createDefault());
        list.addAll(QueryFeatureFamilies.createDefault());
        list.add(new BM25Family(reader, textField));
        list.add(new TFIDFFamily(reader, textField));
        list.add(new BM25TopScoresFamily(reader, textField, AllFeaturesExtractor.DEFAULT_TOP_N));
        for (FeatureFamily family : list) {
            for (String name : family.getAllFeaturesNames()) families.put(name, family);
        }
    }

    public Query greaterThan(String name, double border) throws IOException {
        if (!Double.isFinite(border)) throw new IllegalArgumentException("Non-finite border");
        String key = name + ":" + Double.toHexString(border);
        Query existing = cache.get(key);
        if (existing != null) return existing;
        Query query;
        FeatureFamily family = families.get(name);
        if (family instanceof QueryFeatureFamily) {
            if (prepared.add(family)) {
                family.prepare();
                for (FeatureBase f : ((QueryFeatureFamily)family).calculateAllFeaturesInFamily(rawQuery)) {
                    queryValues.put(f.getName(), f.value);
                }
            }
            query = queryValues.get(name) > border ? new MatchAllDocsQuery() : new MatchNoDocsQuery();
        } else if (family instanceof DocumentFeatureFamily) {
            // Range queries only need the stored FloatPoint schema, not dictionary loading.
            for (LeafReaderContext leaf : reader.leaves()) {
                FieldInfo info = leaf.reader().getFieldInfos().fieldInfo(name);
                if (leaf.reader().numDocs() > 0 && (info == null || info.getPointDimensionCount() != 1
                        || info.getPointNumBytes() != Float.BYTES)) {
                    throw new IllegalStateException("Index lacks FloatPoint feature " + name + "; reindex first");
                }
            }
            query = family.buildLuceneQuery(name, tokens, border);
        } else if (family != null) {
            query = family.buildLuceneQuery(name, tokens, border);
        } else if (Arrays.asList("ExactMatchCount", "ExactMatchRatio", "TitleExactMatchCount",
                "TitleExactMatchRatio", "BigramCount", "TrigramCount", "UnorderedWindow4", "UnorderedWindow8").contains(name)) {
            String field = name.startsWith("Title") ? titleField : textField;
            if (border < 0) query = new MatchAllDocsQuery();
            else if (tokens.length == 0) query = new MatchNoDocsQuery();
            else {
                ValueSource source = new TokenCountSource(field, name, tokens);
                Query range = new FunctionRangeQuery(source, border, null, false, false);
                List<BytesRef> terms = new ArrayList<>();
                for (String token : new LinkedHashSet<>(Arrays.asList(tokens))) terms.add(new BytesRef(token));
                query = new BooleanQuery.Builder()
                        .add(new TermInSetQuery(field, terms), BooleanClause.Occur.FILTER)
                        .add(range, BooleanClause.Occur.FILTER).build();
            }
        } else throw new IllegalArgumentException("Unknown model feature: " + name);
        cache.put(key, query);
        return query;
    }

    private static final class TokenCountSource extends ValueSource {
        private final String field, feature;
        private final String[] tokens;
        TokenCountSource(String field, String feature, String[] tokens) {
            this.field = field; this.feature = feature; this.tokens = tokens.clone();
        }
        @Override public FunctionValues getValues(Map context, LeafReaderContext leaf) throws IOException {
            boolean positional = !feature.contains("ExactMatch");
            Terms fieldTerms = leaf.reader().terms(field);
            if (positional && fieldTerms != null && !fieldTerms.hasPositions()) {
                throw new IllegalStateException("Positions are required in field " + field);
            }
            Map<String, PostingsEnum> postings = new LinkedHashMap<>();
            for (String token : tokens) if (!postings.containsKey(token)) {
                postings.put(token, leaf.reader().postings(new Term(field, token),
                        positional ? PostingsEnum.POSITIONS : PostingsEnum.NONE));
            }
            return new DoubleDocValues(this) {
                private int lastDoc = -1;
                private float lastValue;
                @Override public double doubleVal(int doc) throws IOException {
                    if (doc == lastDoc) return lastValue;
                    int matchedTerms = 0;
                    Map<String, Set<Integer>> positions = new LinkedHashMap<>();
                    for (Map.Entry<String, PostingsEnum> entry : postings.entrySet()) {
                        PostingsEnum p = entry.getValue();
                        Set<Integer> values = new HashSet<>();
                        if (p != null) {
                            if (p.docID() < doc) p.advance(doc);
                            if (p.docID() == doc) {
                                matchedTerms++;
                                if (positional) for (int i = 0; i < p.freq(); i++) values.add(p.nextPosition());
                            }
                        }
                        positions.put(entry.getKey(), values);
                    }
                    if (feature.endsWith("ExactMatchCount")) lastValue = matchedTerms;
                    else if (feature.endsWith("ExactMatchRatio")) lastValue = (float)matchedTerms / postings.size();
                    else if (feature.equals("BigramCount") || feature.equals("TrigramCount")) {
                        int n = feature.equals("BigramCount") ? 2 : 3;
                        Set<List<String>> ngrams = new HashSet<>();
                        for (int i = 0; i <= tokens.length - n; i++) {
                            ngrams.add(Arrays.asList(Arrays.copyOfRange(tokens, i, i + n)));
                        }
                        int count = 0;
                        for (List<String> ngram : ngrams) {
                            for (int start : positions.get(ngram.get(0))) {
                                boolean match = true;
                                for (int j = 1; j < n; j++) match &= positions.get(ngram.get(j)).contains(start + j);
                                if (match) count++;
                            }
                        }
                        lastValue = count;
                    } else {
                        int distance = feature.equals("UnorderedWindow4") ? 5 : 9;
                        List<String> terms = new ArrayList<>(positions.keySet());
                        int count = 0;
                        for (int i = 0; i < terms.size(); i++) for (int j = i + 1; j < terms.size(); j++) {
                            Set<Integer> right = positions.get(terms.get(j));
                            for (int left : positions.get(terms.get(i))) {
                                for (int delta = 1; delta <= distance; delta++) {
                                    if (right.contains(left + delta)) count++;
                                    if (right.contains(left - delta)) count++;
                                }
                            }
                        }
                        lastValue = count;
                    }
                    lastDoc = doc;
                    return lastValue;
                }
            };
        }
        @Override public String description() { return feature + "(" + field + "," + Arrays.toString(tokens) + ")"; }
        @Override public boolean equals(Object other) {
            if (!(other instanceof TokenCountSource)) return false;
            TokenCountSource that = (TokenCountSource)other;
            return field.equals(that.field) && feature.equals(that.feature) && Arrays.equals(tokens, that.tokens);
        }
        @Override public int hashCode() { return Objects.hash(field, feature, Arrays.hashCode(tokens)); }
    }
}
