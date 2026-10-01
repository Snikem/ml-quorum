package monoforest.runner;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.lucene.analysis.core.WhitespaceAnalyzer;
import org.apache.lucene.document.*;
import org.apache.lucene.index.*;
import org.apache.lucene.store.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.nio.file.*;
import static org.junit.Assert.*;

public class MonoforestRunnerTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private Path index, model;
    @Before public void fixture() throws Exception {
        index = temp.newFolder("index").toPath();
        try (Directory directory = FSDirectory.open(index); WhitespaceAnalyzer analyzer = new WhitespaceAnalyzer();
             IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig(analyzer))) {
            Document document = new Document();
            document.add(new StringField("id", "d1", Field.Store.YES));
            document.add(new TextField("body", "cat dog cat", Field.Store.YES));
            document.add(new StoredField("heading", "Example title"));
            document.add(new FloatPoint("doc_word_count", 3));
            writer.addDocument(document);
        }
        model = temp.newFile("model.json").toPath();
        Files.writeString(model, "{\"format\":\"monomial_linear_v1\",\"intercept\":0,\"feature_names\":[\"doc_word_count\"],"
                + "\"monomials\":[{\"trained_coef\":2,\"splits\":[{\"feature_idx\":0,\"feature_name\":\"doc_word_count\",\"comparison\":\"GT\",\"border\":1}]}]}");
    }
    private String[] args(Path path) {
        return new String[]{"--index", path.toString(), "--model", model.toString(), "--query", "cat dog",
                "--threshold", "1", "--text-field", "body", "--title-field", "heading"};
    }
    @Test public void oneQueryReturnsStoredDocumentsAndSeparateTimings() throws Exception {
        JsonNode result = MonoforestRunner.run(args(index));
        assertEquals("filesystem", result.path("mode").asText());
        assertEquals(0, result.path("copy_to_ram_ms").asDouble(), 0);
        assertEquals(0, result.path("warmup_queries").asInt());
        assertTrue(result.path("search_ms").asDouble() > 0);
        assertTrue(result.path("open_model_and_index_ms").asDouble() > 0);
        assertEquals(1, result.path("result").path("returned").asInt());
        JsonNode document = result.path("result").path("candidates").get(0);
        assertEquals("d1", document.path("doc_id").asText());
        assertEquals("Example title", document.path("title").asText());
        assertEquals("cat dog cat", document.path("text").asText());
        assertEquals(2, document.path("score").asDouble(), 0);
    }
    @Test public void copyIsSearchableAndOnlyOwnedCopyIsDeleted() throws Exception {
        Path root = temp.newFolder("ram-root").toPath();
        Path unrelated = Files.writeString(root.resolve("keep.txt"), "keep");
        Path copied;
        try (RamIndex ram = RamIndex.copy(index, root, 0, false)) {
            copied = ram.path;
            assertTrue(ram.bytes > 0);
            assertEquals(1, MonoforestRunner.run(args(copied)).path("result").path("returned").asInt());
        }
        assertFalse(Files.exists(copied));
        assertTrue(Files.exists(unrelated));
        assertEquals(1, MonoforestRunner.run(args(index)).path("result").path("returned").asInt());
    }
    @Test public void refusesCopyWhileWriterIsOpen() throws Exception {
        try (Directory directory = FSDirectory.open(index); WhitespaceAnalyzer analyzer = new WhitespaceAnalyzer();
             IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig(analyzer))) {
            assertThrows(LockObtainFailedException.class,
                    () -> RamIndex.copy(index, temp.newFolder("target").toPath(), 0, false));
        }
    }
}
