package monoforest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.lucene.analysis.core.WhitespaceAnalyzer;
import org.apache.lucene.document.*;
import org.apache.lucene.index.*;
import org.apache.lucene.store.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

public class MonoforestSearchTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private Path root, index, model;
    private final ObjectMapper json = new ObjectMapper();

    @Before public void prepare() throws Exception {
        root = temp.newFolder("resources").toPath();
        index = Files.createDirectory(root.resolve("index"));
        try (Directory dir = FSDirectory.open(index); WhitespaceAnalyzer analyzer = new WhitespaceAnalyzer();
             IndexWriter writer = new IndexWriter(dir, new IndexWriterConfig(analyzer))) {
            String[] texts = {"cat", "cat cat cat", "cat cat cat cat cat", "dog dog dog"};
            for (int i = 0; i < texts.length; i++) {
                Document doc = new Document();
                doc.add(new StringField("id", "d" + i, Field.Store.YES));
                doc.add(new TextField("body", texts[i], Field.Store.YES));
                doc.add(new StoredField("heading", "Title " + i));
                doc.add(new FloatPoint("doc_word_count", texts[i].split(" ").length));
                writer.addDocument(doc);
            }
        }
        model = root.resolve("model.json");
        Files.writeString(model, "{\"format\":\"monomial_linear_v1\",\"intercept\":-0.5,"
                + "\"feature_names\":[\"doc_word_count\",\"ExactMatchCount\"],\"monomials\":["
                + "{\"trained_coef\":2,\"splits\":["
                + "{\"feature_idx\":0,\"feature_name\":\"doc_word_count\",\"comparison\":\"GT\",\"border\":2},"
                + "{\"feature_idx\":1,\"feature_name\":\"ExactMatchCount\",\"comparison\":\"GT\",\"border\":0}]},"
                + "{\"trained_coef\":-3,\"splits\":["
                + "{\"feature_idx\":0,\"feature_name\":\"doc_word_count\",\"comparison\":\"GT\",\"border\":4}]}]}");
    }

    private MonoforestSearch search(double threshold) throws Exception {
        return new MonoforestSearch(index.toString(), model.toString(), threshold, "body", "heading");
    }

    @Test public void usesQueryTextAndReturnsOriginalSignedScores() throws Exception {
        try (MonoforestSearch search = search(-1)) {
            ObjectNode result = search.search("cat", 10);
            assertEquals(3, result.path("total_hits").asLong());
            assertEquals("d1", result.path("candidates").get(0).path("doc_id").asText());
            assertEquals(1.5, result.path("candidates").get(0).path("score").asDouble(), 0);
            assertEquals(-0.5, result.path("candidates").get(1).path("score").asDouble(), 0);
            assertEquals(1, search.search("cat", 1).path("returned").asInt());
            assertEquals("d3", search.search("dog", 1).path("candidates").get(0).path("doc_id").asText());
            assertEquals("Title 1", search.getStoredDocument("d1").get("heading"));
        }
        try (MonoforestSearch search = search(1.5)) {
            assertEquals(0, search.search("cat", 10).path("returned").asInt());
        }
    }

    @Test public void sharesOneSearchServiceAcrossThreads() throws Exception {
        try (MonoforestSearch search = search(Math.nextDown(1.5))) {
            ExecutorService pool = Executors.newFixedThreadPool(3);
            try {
                List<Callable<String>> calls = new ArrayList<>();
                for (int i = 0; i < 12; i++) {
                    final String text = i % 2 == 0 ? "cat" : "dog";
                    calls.add(() -> search.search(text, 5).path("candidates").get(0).path("doc_id").asText());
                }
                List<Future<String>> results = pool.invokeAll(calls);
                for (int i = 0; i < results.size(); i++) assertEquals(i % 2 == 0 ? "d1" : "d3", results.get(i).get());
            } finally { pool.shutdownNow(); }
        }
    }

    @Test public void validatesInputsAndClosesResources() throws Exception {
        MonoforestSearch search = search(0);
        try {
            assertThrows(NullPointerException.class, () -> search.search(null, 10));
            assertThrows(IllegalArgumentException.class, () -> search.search("cat", 0));
            assertThrows(java.io.IOException.class, () -> search.getStoredDocument("missing"));
        } finally { search.close(); }
        search.close();
        assertThrows(IllegalStateException.class, () -> search.search("cat", 10));
        assertThrows(IllegalArgumentException.class, () -> search(Double.NaN));
    }
}
