package monoforest.impl;

import monoforest.impl.features.AllFeaturesExtractor;
import org.apache.lucene.analysis.core.WhitespaceAnalyzer;
import org.apache.lucene.document.*;
import org.apache.lucene.index.*;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;

public class DatasetOrchestrator3Test {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void readsAllThreeRawSourcesWithoutDocumentLookupAndReproducesLegacySplit() throws Exception {
        Path root = temporary.newFolder("raw").toPath();
        DatasetOrchestrator3.Inputs inputs = new DatasetOrchestrator3.Inputs(root, 2, 27);
        Map<String, List<String>> expected = new HashMap<>();
        for (int source = 0; source < 3; source++) {
            Files.createDirectories(inputs.directory(source));
            for (int part = 0; part < 2; part++) {
                List<String> rows = new ArrayList<>();
                for (int q = part * 20; q < (part + 1) * 20; q++) {
                    String query = "cat q" + q;
                    String id = "raw-only-" + source + "-" + q;
                    String label = source == 1 ? "1" : "0";
                    String json = "{\"title\":\"cat\",\"body\":\"cat cat dog\",\"headings\":\"ignored extra words\"}";
                    if (source == 1) json = "\"" + json.replace("\"", "\"\"") + "\"";
                    String row = query + "\t0\t" + id + "\t" + (source == 2 ? json + "\t123" : label + "\t" + json);
                    rows.add(row);
                    if (source != 2 || q < 27) {
                        expected.computeIfAbsent(query, k -> new ArrayList<>()).add(label + "," + query + "," + id);
                    }
                    // Preserve duplicates even across source shards.
                    if (source == 0 && q == 0) {
                        rows.add(row);
                        expected.get(query).add(label + "," + query + "," + id);
                    }
                }
                Files.write(inputs.file(source, part), rows, StandardCharsets.UTF_8);
            }
        }
        Path output = root.resolve("result");
        try (Directory directory = statisticsIndex(); DirectoryReader reader = DirectoryReader.open(directory);
             AllFeaturesExtractor extractor = new AllFeaturesExtractor(reader, "text", 3)) {
            extractor.prepare();
            // No id/title/stored text exist in the index. All doc IDs occur only in the raw TSV.
            assertNull(reader.document(0).get("id"));
            assertNull(reader.document(0).get("text"));
            new DatasetOrchestrator3().generate(inputs, output, extractor, "test", "text", 3);
            List<String> names = extractor.getFeatureNames();
            assertEquals(35, names.size());
            assertEquals(17, extractor.getSchemaRows().stream().map(s -> s.split("\t")[2]).distinct().count());
            List<String> queries = new ArrayList<>(expected.keySet());
            Collections.shuffle(queries, new Random(42));
            List<String> trainMetadata = new ArrayList<>(), validMetadata = new ArrayList<>();
            for (int i = 0; i < queries.size(); i++) (i < 38 ? trainMetadata : validMetadata).addAll(expected.get(queries.get(i)));
            checkSplit(output.resolve("train.csv"), names, trainMetadata);
            checkSplit(output.resolve("valid.csv"), names, validMetadata);
            JSONObject manifest = new JSONObject(Files.readString(output.resolve("manifest.json")));
            assertEquals(41, manifest.getJSONArray("sourceRows").getInt(0));
            assertEquals(40, manifest.getJSONArray("sourceRows").getInt(1));
            assertEquals(27, manifest.getJSONArray("sourceRows").getInt(2));
            assertEquals(38, manifest.getInt("trainQueries"));
            assertEquals(2, manifest.getInt("validQueries"));
            assertEquals(6, manifest.getJSONArray("readFiles").length());
            assertFalse(Files.exists(output.resolve("raw_candidates.bin")));
            assertFalse(Files.exists(output.resolve("train.tsv")));
            assertThrows(FileAlreadyExistsException.class,
                    () -> new DatasetOrchestrator3().generate(inputs, output, extractor, "test", "text", 3));
        }
    }

    private static void checkSplit(Path path, List<String> names, List<String> expected) throws IOException {
        List<String> rows = Files.readAllLines(path);
        assertEquals("label,query,doc_id," + String.join(",", names), rows.get(0));
        assertEquals(expected.size() + 1, rows.size());
        for (int i = 1; i < rows.size(); i++) {
            String[] fields = rows.get(i).split(",");
            assertEquals(expected.get(i - 1), fields[0] + "," + fields[1] + "," + fields[2]);
            assertEquals(names.size() + 3, fields.length);
            assertValue(fields, names, "ExactMatchCount", 1f);
            assertValue(fields, names, "TitleExactMatchCount", 1f);
            assertValue(fields, names, "doc_word_count", 4f);
            assertValue(fields, names, "BM25Top3Score", 0f);
            for (int j = 3; j < fields.length; j++) assertTrue(Float.isFinite(Float.parseFloat(fields[j])));
        }
    }

    @Test public void parsesLegacyJsonAndWritesCsvIncludingEmptyTitleAndLongUnicodeBody() throws Exception {
        String body = "длинный body\n".repeat(10000);
        JSONObject json = new JSONObject().put("title", "").put("body", body);
        String raw = "cats, \"dogs\"\t0\t001\t1\t" + json;
        DatasetOrchestrator3.Candidate row = DatasetOrchestrator3.Candidate.parse(raw, false);
        assertEquals("", row.title);
        assertEquals(body, row.body);
        String wrapped = "\"" + json.toString().replace("\"", "\"\"") + "\"";
        DatasetOrchestrator3.Candidate bm25 = DatasetOrchestrator3.Candidate.parse("cats\t0\t001\t" + wrapped + "\t17", true);
        assertEquals("0", bm25.label);
        assertEquals(body, bm25.body);
        try (RandomAccessFile spool = new RandomAccessFile(temporary.newFile(), "rw")) {
            row.write(spool);
            spool.seek(0);
            DatasetOrchestrator3.Candidate restored = DatasetOrchestrator3.Candidate.read(spool, row.query);
            assertEquals(body, restored.body);
            assertEquals(row.docId, restored.docId);
        }
        StringWriter csv = new StringWriter();
        DatasetOrchestrator3.writeHeader(csv, Collections.singletonList("Feature"));
        DatasetOrchestrator3.writeRow(csv, row, new float[]{0.25f});
        assertEquals("label,query,doc_id,Feature\r\n1,\"cats, \"\"dogs\"\"\",001,0.25\r\n", csv.toString());
        try (Directory directory = statisticsIndex(); DirectoryReader reader = DirectoryReader.open(directory);
             AllFeaturesExtractor extractor = new AllFeaturesExtractor(reader, "text", 3)) {
            extractor.prepare();
            float[] features = extractor.extract("cat", "absent-from-index", "", "cat dog");
            assertEquals(0f, features[extractor.getFeatureNames().indexOf("TitleExactMatchCount")], 0f);
        }
    }

    @Test public void usesOldRawPathsAndSplitDefaultsAndRejectsInvalidInput() throws Exception {
        DatasetOrchestrator3.Inputs inputs = new DatasetOrchestrator3.Inputs(Paths.get(DatasetOrchestrator3.NEGATIVE_INPUT_DIR),
                Paths.get(DatasetOrchestrator3.POSITIVE_INPUT_DIR), Paths.get(DatasetOrchestrator3.BM25_INPUT_DIR),
                DatasetOrchestrator3.PART_COUNT, DatasetOrchestrator3.LEGACY_BM25_LIMIT);
        assertEquals("/Volumes/Ex_Volume/DatasetStream/randomNegative/negative_qrels_with_queries/qrels_doc_00_with_docs.tsv", inputs.file(0, 0).toString());
        assertEquals("/Volumes/Ex_Volume/DatasetStream/onlyPositive/qrels_with_queries_train/qrels_doc_59_with_docs.tsv", inputs.file(1, 59).toString());
        assertEquals("/Volumes/Ex_Volume/DatasetStream/bm_25_streamTop100/qrels_final_00.tsv", inputs.file(2, 0).toString());
        assertEquals(60, inputs.partCount);
        assertEquals(100002, inputs.bm25Limit);
        assertEquals(42, DatasetOrchestrator3.SPLIT_SEED);
        assertEquals(0.95, DatasetOrchestrator3.TRAIN_QUERY_FRACTION, 0.0);
        assertThrows(IllegalArgumentException.class, () -> DatasetOrchestrator3.Candidate.parse("0\tquery\tdoc\t1.2", false));
        assertThrows(IllegalArgumentException.class, () -> DatasetOrchestrator3.Candidate.parse("q\t0\tdoc\tNaN\t{}", false));
        assertThrows(IllegalArgumentException.class, () -> DatasetOrchestrator3.Candidate.parse("q\t0\tdoc\t{}\tNaN", true));
        assertEquals("\"a\r\nb\"", DatasetOrchestrator3.csvField("a\r\nb"));
    }

    private static void assertValue(String[] row, List<String> names, String name, float value) {
        assertTrue(name, names.contains(name));
        assertEquals(name, value, Float.parseFloat(row[3 + names.indexOf(name)]), 1e-6f);
    }

    private static Directory statisticsIndex() throws Exception {
        Directory directory = new ByteBuffersDirectory();
        try (WhitespaceAnalyzer analyzer = new WhitespaceAnalyzer();
             IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig(analyzer))) {
            Document document = new Document();
            document.add(new TextField("text", "cat dog", Field.Store.NO));
            writer.addDocument(document);
        }
        return directory;
    }
}
