package monoforest.impl;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.apache.lucene.index.*;
import org.apache.lucene.search.*;
import org.apache.lucene.store.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Load the Python export and retrieve top-k documents with raw score > threshold.
 * args: model.json indexDir query threshold topK [textField] [titleField]
 */
public final class MonomialCandidateSearch {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final JsonNode model;
    private final String textField, titleField;

    public MonomialCandidateSearch(Path modelPath, String textField, String titleField) throws IOException {
        this.model = JSON.readTree(modelPath.toFile());
        this.textField = Objects.requireNonNull(textField);
        this.titleField = Objects.requireNonNull(titleField);
        validate(model);
    }

    private static double number(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isNumber() || !Double.isFinite(value.doubleValue())) {
            throw new IllegalArgumentException("Missing/invalid number: " + field);
        }
        return value.doubleValue();
    }

    private static void validate(JsonNode model) {
        if (!"monomial_linear_v1".equals(model.path("format").asText())) {
            throw new IllegalArgumentException("Unsupported model format");
        }
        number(model, "intercept");
        JsonNode names = model.path("feature_names"), monomials = model.path("monomials");
        if (!names.isArray() || !monomials.isArray() || monomials.size() == 0) {
            throw new IllegalArgumentException("feature_names and nonempty monomials arrays required");
        }
        Set<String> unique = new HashSet<>();
        for (JsonNode name : names) {
            if (!name.isTextual() || !unique.add(name.textValue())) throw new IllegalArgumentException("Invalid feature names");
        }
        for (JsonNode monomial : monomials) {
            number(monomial, "trained_coef");
            if (!monomial.path("splits").isArray()) throw new IllegalArgumentException("splits array required");
            for (JsonNode split : monomial.path("splits")) {
                JsonNode index = split.get("feature_idx");
                if (index == null || !index.isIntegralNumber() || !index.canConvertToInt()
                        || index.intValue() < 0 || index.intValue() >= names.size()) {
                    throw new IllegalArgumentException("Invalid feature_idx");
                }
                if (!names.get(index.intValue()).asText().equals(split.path("feature_name").asText())
                        || !"GT".equals(split.path("comparison").asText())) {
                    throw new IllegalArgumentException("Feature name/index mismatch or unsupported comparison");
                }
                number(split, "border");
            }
        }
    }

    public MonomialCandidateQuery buildQuery(IndexReader reader, String queryText, double threshold) throws IOException {
        MonomialFeatureQueries features = new MonomialFeatureQueries(reader, queryText, textField, titleField);
        List<Query> queries = new ArrayList<>();
        double[] coefficients = new double[model.path("monomials").size()];
        int i = 0;
        for (JsonNode monomial : model.path("monomials")) {
            coefficients[i++] = number(monomial, "trained_coef");
            BooleanQuery.Builder conjunction = new BooleanQuery.Builder();
            int count = 0;
            boolean impossible = false;
            for (JsonNode split : monomial.path("splits")) {
                Query condition = features.greaterThan(split.path("feature_name").asText(), number(split, "border"));
                if (condition instanceof MatchNoDocsQuery) { impossible = true; break; }
                if (!(condition instanceof MatchAllDocsQuery)) {
                    conjunction.add(condition, BooleanClause.Occur.FILTER);
                    count++;
                }
            }
            queries.add(impossible ? new MatchNoDocsQuery()
                    : count == 0 ? new MatchAllDocsQuery() : conjunction.build());
        }
        return new MonomialCandidateQuery(queries, coefficients, number(model, "intercept"), threshold);
    }

    /** At most topK hits. A strict threshold can legitimately return fewer documents. */
    public ObjectNode search(IndexSearcher searcher, String queryText, double threshold, int topK) throws IOException {
        if (topK <= 0) throw new IllegalArgumentException("topK must be positive");
        long buildStart = System.nanoTime();
        MonomialCandidateQuery query = buildQuery(searcher.getIndexReader(), queryText, threshold);
        long buildNanos = System.nanoTime() - buildStart;
        return execute(searcher, queryText, threshold, topK, query, buildNanos);
    }

    /** Reuse a query prepared for this reader; query-dependent resources are already resolved. */
    public ObjectNode searchPrepared(IndexSearcher searcher, String queryText, double threshold,
                                     int topK, MonomialCandidateQuery query) throws IOException {
        if (topK <= 0) throw new IllegalArgumentException("topK must be positive");
        return execute(searcher, queryText, threshold, topK, query, 0);
    }

    private ObjectNode execute(IndexSearcher searcher, String queryText, double threshold, int topK,
                               MonomialCandidateQuery query, long buildNanos) throws IOException {
        long searchStart = System.nanoTime();
        TopDocs hits = searcher.search(query, topK);
        long searchNanos = System.nanoTime() - searchStart;
        long explainNanos = 0, idNanos = 0;
        ObjectNode result = JSON.createObjectNode();
        result.put("query", queryText);
        result.put("threshold", threshold);
        result.put("comparison", "GT");
        result.put("total_hits", hits.totalHits.value);
        result.put("total_hits_relation", hits.totalHits.relation.toString());
        result.put("returned", hits.scoreDocs.length);
        result.put("lucene_query", query.toString());
        ArrayNode candidates = result.putArray("candidates");
        for (ScoreDoc hit : hits.scoreDocs) {
            // Explanation exposes the double model score; subtracting the shift
            // from ScoreDoc.score alone would retain Lucene's float rounding.
            long explainStart = System.nanoTime();
            double raw = searcher.explain(query, hit.doc).getDetails()[0].getValue().doubleValue();
            explainNanos += System.nanoTime() - explainStart;
            long idStart = System.nanoTime();
            String id = searcher.doc(hit.doc, Collections.singleton("id")).get("id");
            idNanos += System.nanoTime() - idStart;
            if (id == null) throw new IllegalStateException("Candidate lacks stored id field");
            double probability = raw >= 0 ? 1 / (1 + Math.exp(-raw)) : Math.exp(raw) / (1 + Math.exp(raw));
            candidates.addObject().put("doc_id", id).put("score", raw).put("probability", probability);
        }
        result.putObject("timings_ms")
                .put("build_query_ms", buildNanos / 1e6)
                .put("lucene_search_ms", searchNanos / 1e6)
                .put("explain_ms", explainNanos / 1e6)
                .put("fetch_ids_ms", idNanos / 1e6);
        return result;
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 5 || args.length > 7) {
            throw new IllegalArgumentException("Usage: model.json indexDir query threshold topK [textField] [titleField]");
        }
        MonomialCandidateSearch engine = new MonomialCandidateSearch(Paths.get(args[0]),
                args.length > 5 ? args[5] : AppConfig.getTextField(),
                args.length > 6 ? args[6] : AppConfig.getTitleField());
        try (Directory directory = FSDirectory.open(Paths.get(args[1]));
             IndexReader reader = DirectoryReader.open(directory)) {
            ObjectNode result = engine.search(new IndexSearcher(reader), args[2], Double.parseDouble(args[3]), Integer.parseInt(args[4]));
            System.out.println(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(result));
        }
    }
}
