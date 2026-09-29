package monoforest.impl;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import monoforest.impl.features.*;
import monoforest.impl.features.jointFeatures.*;
import org.apache.lucene.document.*;
import org.apache.lucene.analysis.core.WhitespaceAnalyzer;
import org.apache.lucene.index.*;
import org.apache.lucene.search.*;
import org.apache.lucene.store.*;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;

public class MonomialCandidateSearchTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();
    private static final String[] TEXTS = {"cat dog cat dog", "cat", "other", "dog cat x dog", "cat cat dog cat dog", ""};
    private static final String[] TITLES = {"cat", "dog", "", "cat dog", "cat cat", ""};
    private Directory index() throws Exception {
        Directory dir = new ByteBuffersDirectory();
        try (WhitespaceAnalyzer analyzer = new WhitespaceAnalyzer();
             IndexWriter writer = new IndexWriter(dir, new IndexWriterConfig(analyzer))) {
            for (int i = 0; i < TEXTS.length; i++) {
                Document doc = new Document();
                doc.add(new StringField("id", "d" + i, Field.Store.YES));
                doc.add(new TextField("body", TEXTS[i], Field.Store.YES));
                doc.add(new TextField("title", TITLES[i], Field.Store.YES));
                doc.add(new FloatPoint("doc_word_count", TEXTS[i].isEmpty() ? 0 : TEXTS[i].split(" ").length));
                writer.addDocument(doc);
                if (i == 2) writer.commit();
            }
            Document deleted = new Document();
            deleted.add(new StringField("id", "deleted", Field.Store.YES));
            writer.addDocument(deleted);
            writer.commit();
            writer.deleteDocuments(new Term("id", "deleted"));
        }
        return dir;
    }

    @Test public void signedScoresStrictThresholdAndDocumentsWithNoActiveMonomials() throws Exception {
        try (Directory dir = index(); DirectoryReader reader = DirectoryReader.open(dir)) {
            IndexSearcher searcher = new IndexSearcher(reader);
            List<Query> conditions = Arrays.asList(new TermQuery(new Term("body", "cat")),
                    new TermQuery(new Term("body", "dog")), new MatchAllDocsQuery());
            double[] coef = {2, -3, 0.25};
            double[] expected = {-0.25, 2.75, 0.75, -0.25, -0.25, 0.75};
            for (double threshold : new double[]{-1, -0.25, 0, 0.75, 2.75, 3}) {
                MonomialCandidateQuery query = new MonomialCandidateQuery(conditions, coef, 0.5, threshold);
                TopDocs hits = searcher.search(query, 20);
                int count = 0;
                for (double score : expected) if (score > threshold) count++;
                assertEquals(count, hits.scoreDocs.length);
                double previous = Double.POSITIVE_INFINITY;
                for (ScoreDoc hit : hits.scoreDocs) {
                    int id = Integer.parseInt(searcher.doc(hit.doc).get("id").substring(1));
                    Explanation explanation = searcher.explain(query, hit.doc);
                    assertEquals(expected[id], explanation.getDetails()[0].getValue().doubleValue(), 0);
                    assertTrue(expected[id] > threshold);
                    assertTrue(expected[id] <= previous);
                    assertTrue(hit.score >= 0);
                    previous = expected[id];
                }
                assertEquals(count, searcher.count(query));
            }
            MonomialCandidateQuery negative = new MonomialCandidateQuery(conditions.subList(0, 1), new double[]{-2}, 0, -1);
            assertEquals(2, searcher.count(negative));
            assertEquals(0, searcher.count(new MonomialCandidateQuery(Collections.emptyList(), new double[0], 1, 1)));
            assertEquals(6, searcher.count(new MonomialCandidateQuery(Collections.emptyList(), new double[0], 1, 0)));
        }
    }

    @Test public void countQueriesMatchNumericFeaturesAtEveryBoundary() throws Exception {
        try (Directory dir = index(); DirectoryReader reader = DirectoryReader.open(dir);
             WhitespaceAnalyzer analyzer = new WhitespaceAnalyzer()) {
            IndexSearcher searcher = new IndexSearcher(reader);
            MyTokenizer tokenizer = new MyTokenizer(analyzer);
            List<FeatureFamily> families = Arrays.asList(new ExactMatchFamily(), new TitleExactMatchFamily(), new UnorderedWindowFamily(),
                    new BM25Family(reader, "body"), new TFIDFFamily(reader, "body"));
            for (String queryText : new String[]{"cat dog cat", "cat cat", "cat dog x", "", "cat dog cat x dog"}) {
                String[] tokens = tokenizer.tokenize(queryText).toArray(new String[0]);
                MonomialFeatureQueries builder = new MonomialFeatureQueries(reader, queryText, "body", "title");
                for (FeatureFamily family : families) for (String feature : family.getAllFeaturesNames()) {
                    for (int d = 0; d < TEXTS.length; d++) {
                        DocumentMarco document = new DocumentMarco();
                        document.setBody(TEXTS[d]); document.setTitle(TITLES[d]);
                        document.setTokensBody(tokenizer.tokenize(TEXTS[d])); document.tokenizeTitle(tokenizer);
                        double value = family.calculateFeatureByName(feature, tokens, document).value;
                        for (double border : new double[]{-1, 0, 0.5, 1, 2, 5, value, Math.nextDown(value), Math.nextUp(value)}) {
                            Query condition = builder.greaterThan(feature, border);
                            assertEquals(feature + " query=" + queryText + " doc=" + d + " border=" + border + " value=" + value,
                                    value > border, searcher.explain(condition, d).isMatch());
                        }
                    }
                }
            }
        }
    }

    @Test public void exportedModelLoadsAndReturnsRawScores() throws Exception {
        ObjectMapper json = new ObjectMapper();
        ObjectNode model = json.createObjectNode().put("format", "monomial_linear_v1").put("intercept", -0.5);
        model.putArray("feature_names").add("doc_word_count").add("ExactMatchCount").add("QueryWordCount");
        ArrayNode terms = model.putArray("monomials");
        ObjectNode first = terms.addObject().put("trained_coef", 2);
        first.putArray("splits").addObject().put("feature_idx", 0).put("feature_name", "doc_word_count").put("comparison", "GT").put("border", 2);
        ObjectNode second = terms.addObject().put("trained_coef", -1);
        second.putArray("splits").addObject().put("feature_idx", 1).put("feature_name", "ExactMatchCount").put("comparison", "GT").put("border", 1);
        ObjectNode third = terms.addObject().put("trained_coef", 0.25);
        third.putArray("splits").addObject().put("feature_idx", 2).put("feature_name", "QueryWordCount").put("comparison", "GT").put("border", 1);
        Path path = tmp.newFile("model.json").toPath();
        json.writeValue(path.toFile(), model);
        try (Directory dir = index(); DirectoryReader reader = DirectoryReader.open(dir)) {
            MonomialCandidateSearch engine = new MonomialCandidateSearch(path, "body", "title");
            ObjectNode results = engine.search(new IndexSearcher(reader), "cat dog", 0, 2);
            assertEquals(2, results.path("returned").asInt());
            for (JsonNode hit : results.path("candidates")) assertEquals(0.75, hit.path("score").asDouble(), 0);
            assertEquals(0, engine.search(new IndexSearcher(reader), "cat dog", 0.75, 100).path("returned").asInt());
        }
        ((ObjectNode)first.path("splits").get(0)).put("feature_name", "wrong");
        json.writeValue(path.toFile(), model);
        assertThrows(IllegalArgumentException.class, () -> new MonomialCandidateSearch(path, "body", "title"));
    }
}
