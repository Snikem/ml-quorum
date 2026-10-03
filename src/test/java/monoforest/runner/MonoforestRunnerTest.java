package monoforest.runner;

import com.fasterxml.jackson.databind.JsonNode;
import monoforest.impl.features.queryFeatures.QueryFrequencyFamily;
import java.io.IOException;
import java.util.Arrays;
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
                "--top-k", "10", "--threshold", "1", "--text-field", "body", "--title-field", "heading"};
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
    @Test public void firstMatchesHotModeReturnsDocumentsWithoutScores() throws Exception {
        String[] command = Arrays.copyOf(args(index), args(index).length + 4);
        for (int i = 0; i < command.length; i++) if ("--top-k".equals(command[i])) command[i] = "--limit";
        command[command.length - 4] = "--hot";
        command[command.length - 3] = "true";
        command[command.length - 2] = "--repeat";
        command[command.length - 1] = "3";
        JsonNode response = MonoforestRunner.run(command);
        assertEquals("first_matches", response.path("retrieval_mode").asText());
        assertEquals(3, response.path("runs").size());
        for (JsonNode run : response.path("runs")) {
            JsonNode result = run.path("result");
            assertEquals(1, result.path("returned").asInt());
            assertEquals("EQUAL_TO", result.path("total_hits_relation").asText());
            JsonNode hit = result.path("candidates").get(0);
            assertEquals("cat dog cat", hit.path("text").asText());
            assertEquals("Example title", hit.path("title").asText());
            assertFalse(hit.has("score"));
            assertFalse(hit.has("probability"));
            assertEquals(0, run.path("timings_ms").path("explain_ms").asDouble(), 0);
            assertEquals(0, run.path("timings_ms").path("build_query_ms").asDouble(), 0);
        }
    }
    @Test public void conflictingRetrievalOptionsAreRejected() {
        String[] command = Arrays.copyOf(args(index), args(index).length + 2);
        command[command.length - 2] = "--limit";
        command[command.length - 1] = "2";
        assertThrows(IllegalArgumentException.class, () -> MonoforestRunner.run(command));
    }
    @Test public void threeQueriesReturnSameDocumentsAndPartitionEveryTiming() throws Exception {
        String[] command = Arrays.copyOf(args(index), args(index).length + 2);
        command[command.length - 2] = "--repeat";
        command[command.length - 1] = "3";
        JsonNode response = MonoforestRunner.run(command);
        assertEquals(3, response.path("query_count").asInt());
        assertEquals(3, response.path("runs").size());
        assertEquals(index.toAbsolutePath().toString(), response.path("active_index").asText());
        assertEquals(0, response.path("warmup_queries").asInt());
        int ordinal = 0;
        for (JsonNode run : response.path("runs")) {
            assertEquals(++ordinal, run.path("run").asInt());
            double sum = 0;
            for (String key : new String[]{"build_query_ms", "lucene_search_ms", "explain_ms",
                    "fetch_ids_ms", "fetch_documents_ms", "other_ms"}) {
                assertTrue(run.path("timings_ms").path(key).isNumber());
                double value = run.path("timings_ms").path(key).asDouble();
                assertTrue(Double.isFinite(value) && value >= 0);
                sum += value;
            }
            assertEquals(run.path("search_ms").asDouble(), sum, 0.000001);
            assertEquals(response.path("result").path("candidates"), run.path("result").path("candidates"));
            assertEquals("cat dog cat", run.path("result").path("candidates").get(0).path("text").asText());
        }
        assertEquals(response.path("runs").get(0).path("search_ms"), response.path("search_ms"));
    }
    @Test public void hotModeSeparatesPreparationWarmupAndThreeMeasuredSearches() throws Exception {
        useFrequencyModel();
        String[] command = Arrays.copyOf(args(index), args(index).length + 4);
        command[command.length - 4] = "--repeat";
        command[command.length - 3] = "3";
        command[command.length - 2] = "--hot";
        command[command.length - 1] = "true";
        JsonNode response = MonoforestRunner.run(command);
        assertEquals("hot_prepared_query", response.path("benchmark_mode").asText());
        assertEquals(1, response.path("warmup_queries").asInt());
        assertTrue(response.path("prepare_query_ms").asDouble() > 0);
        assertEquals(3, response.path("runs").size());
        assertTrue(response.path("warmup").path("search_ms").asDouble() > 0);
        for (JsonNode run : response.path("runs")) {
            assertEquals(0, run.path("timings_ms").path("build_query_ms").asDouble(), 0);
            assertEquals(response.path("warmup").path("result").path("candidates"), run.path("result").path("candidates"));
            assertTrue(run.path("timings_ms").path("lucene_search_ms").asDouble() > 0);
        }
    }
    @Test public void preparedSearchDoesNotReadFrequencySourceAgain() throws Exception {
        useFrequencyModel();
        Path tsv = temp.newFile("frequency.tsv").toPath();
        Files.writeString(tsv, "1\tcat dog\n2\tcat dog\n3\tcat dog\n4\tcat dog\n");
        String previous = System.getProperty(QueryFrequencyFamily.DATA_PATH_PROPERTY);
        System.setProperty(QueryFrequencyFamily.DATA_PATH_PROPERTY, tsv.toString());
        try (monoforest.MonoforestSearch search = new monoforest.MonoforestSearch(
                index.toString(), model.toString(), 1, "body", "heading")) {
            monoforest.MonoforestSearch.PreparedSearch prepared = search.prepareQuery("cat dog");
            Files.delete(tsv);
            for (int i = 0; i < 3; i++) {
                JsonNode result = prepared.search(10);
                assertEquals(1, result.path("returned").asInt());
                assertEquals(0, result.path("timings_ms").path("build_query_ms").asDouble(), 0);
            }
        } finally { restoreFrequencyProperty(previous); }
    }
    @Test public void rejectsNonPositiveRepeats() {
        for (String count : new String[]{"0", "-1"}) {
            String[] command = Arrays.copyOf(args(index), args(index).length + 2);
            command[command.length - 2] = "--repeat";
            command[command.length - 1] = count;
            assertThrows(IllegalArgumentException.class, () -> MonoforestRunner.run(command));
        }
    }
    @Test public void repeatedEmptyResultsHaveZeroExplainAndIdTime() throws Exception {
        String[] command = Arrays.copyOf(args(index), args(index).length + 2);
        command[command.length - 2] = "--repeat";
        command[command.length - 1] = "3";
        // threshold is the value immediately after --threshold.
        for (int i = 0; i < command.length; i++) if ("--threshold".equals(command[i])) command[i+1] = "99";
        JsonNode response = MonoforestRunner.run(command);
        for (JsonNode run : response.path("runs")) {
            assertEquals(0, run.path("result").path("returned").asInt());
            assertEquals(0, run.path("timings_ms").path("explain_ms").asDouble(), 0);
            assertEquals(0, run.path("timings_ms").path("fetch_ids_ms").asDouble(), 0);
        }
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

    private void useFrequencyModel() throws Exception {
        Files.writeString(model, Files.readString(model).replace("doc_word_count", "QueryMaxLogFrequency"));
    }
    @Test public void frequencyModelWorksWithBundledTsv() throws Exception {
        useFrequencyModel();
        String previous = System.getProperty(QueryFrequencyFamily.DATA_PATH_PROPERTY);
        System.clearProperty(QueryFrequencyFamily.DATA_PATH_PROPERTY);
        try {
            JsonNode result = MonoforestRunner.run(args(index));
            assertEquals(1, result.path("result").path("returned").asInt());
        } finally { restoreFrequencyProperty(previous); }
    }
    @Test public void checksFrequencyInputBeforeAttemptingRamCopy() throws Exception {
        useFrequencyModel();
        String previous = System.getProperty(QueryFrequencyFamily.DATA_PATH_PROPERTY);
        System.setProperty(QueryFrequencyFamily.DATA_PATH_PROPERTY, temp.getRoot().toPath().resolve("missing.tsv").toString());
        String[] command = Arrays.copyOf(args(index), args(index).length + 2);
        command[command.length-2] = "--ram-root";
        command[command.length-1] = temp.getRoot().toPath().resolve("unavailable-ram-root").toString();
        try {
            IOException error = assertThrows(IOException.class, () -> MonoforestRunner.run(command));
            assertTrue(error.getMessage().contains("Query-frequency TSV"));
        } finally { restoreFrequencyProperty(previous); }
    }
    @Test public void modelWithoutFrequencySplitsDoesNotRequireTsv() throws Exception {
        String previous = System.getProperty(QueryFrequencyFamily.DATA_PATH_PROPERTY);
        System.setProperty(QueryFrequencyFamily.DATA_PATH_PROPERTY, temp.getRoot().toPath().resolve("missing.tsv").toString());
        try { assertEquals(1, MonoforestRunner.run(args(index)).path("result").path("returned").asInt()); }
        finally { restoreFrequencyProperty(previous); }
    }
    private void restoreFrequencyProperty(String previous) {
        if (previous == null) System.clearProperty(QueryFrequencyFamily.DATA_PATH_PROPERTY);
        else System.setProperty(QueryFrequencyFamily.DATA_PATH_PROPERTY, previous);
    }
}
