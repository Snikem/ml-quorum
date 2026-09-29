package monoforest.impl;

import monoforest.impl.features.AllFeaturesExtractor;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.json.JSONObject;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Raw inputs of DatasetOrchestrator + query split of DatasetOrchestrator2, using new features. */
public final class DatasetOrchestrator3 {
    // === НАСТРОЙКИ: редактируются здесь, запуск через main() без аргументов ===
    static final String NEGATIVE_INPUT_DIR = "/Volumes/Ex_Volume/DatasetStream/randomNegative/negative_qrels_with_queries";
    static final String POSITIVE_INPUT_DIR = "/Volumes/Ex_Volume/DatasetStream/onlyPositive/qrels_with_queries_train";
    static final String BM25_INPUT_DIR = "/Volumes/Ex_Volume/DatasetStream/bm_25_streamTop100";
    static final String OUTPUT_DIR = "/Volumes/Ex_Volume/DatasetStream/totalSet/new_features";
    static final String INDEX_DIR = "/Volumes/Ex_Volume/msmarcoProcces/lucene_index_positions";
    static final String TEXT_FIELD = "text";
    static final int PART_COUNT = 60; // Файлы 00..59, как getStream(1.0f, ...) в старом оркестраторе.
    static final int BM25_TOP_N = 3;
    // Фактический лимит DatasetOrchestrator2; -1 включает весь BM25-поток.
    public static final long LEGACY_BM25_LIMIT = 100_002;
    static final long SPLIT_SEED = 42;
    static final double TRAIN_QUERY_FRACTION = 0.95;

    static final class Inputs {
        final Path negative, positive, bm25;
        final int partCount;
        final long bm25Limit;

        Inputs(Path root, int partCount, long bm25Limit) {
            this(root.resolve("randomNegative/negative_qrels_with_queries"),
                    root.resolve("onlyPositive/qrels_with_queries_train"),
                    root.resolve("bm_25_streamTop100"), partCount, bm25Limit);
        }

        Inputs(Path negative, Path positive, Path bm25, int partCount, long bm25Limit) {
            if (partCount < 1 || partCount > 60) throw new IllegalArgumentException("PART_COUNT must be 1..60");
            if (bm25Limit < -1) throw new IllegalArgumentException("LEGACY_BM25_LIMIT must be >= -1");
            this.negative = negative;
            this.positive = positive;
            this.bm25 = bm25;
            this.partCount = partCount;
            this.bm25Limit = bm25Limit;
        }

        Path directory(int source) { return source == 0 ? negative : source == 1 ? positive : bm25; }
        Path file(int source, int part) {
            return directory(source).resolve(String.format(Locale.ROOT,
                    source == 2 ? "qrels_final_%02d.tsv" : "qrels_doc_%02d_with_docs.tsv", part));
        }
        void validate() throws IOException {
            for (int source = 0; source < 3; source++) {
                if (!Files.isDirectory(directory(source)) || !Files.isReadable(directory(source))) {
                    throw new IOException("Missing/unreadable raw input directory: " + directory(source));
                }
            }
        }
    }

    public static void main(String[] args) throws Exception {
        Inputs inputs = new Inputs(Paths.get(NEGATIVE_INPUT_DIR), Paths.get(POSITIVE_INPUT_DIR),
                Paths.get(BM25_INPUT_DIR), PART_COUNT, LEGACY_BM25_LIMIT);
        Path output = Paths.get(OUTPUT_DIR);
        inputs.validate();
        if (Files.exists(output)) throw new FileAlreadyExistsException(output.toString());
        System.out.println("Raw negatives: " + inputs.negative + "\nRaw positives: " + inputs.positive
                + "\nRaw BM25: " + inputs.bm25 + "\nStatistics index: " + INDEX_DIR);
        try (Directory directory = FSDirectory.open(Paths.get(INDEX_DIR));
             DirectoryReader reader = DirectoryReader.open(directory);
             AllFeaturesExtractor extractor = new AllFeaturesExtractor(reader, TEXT_FIELD, BM25_TOP_N)) {
            extractor.prepare();
            new DatasetOrchestrator3().generate(inputs, output, extractor, INDEX_DIR, TEXT_FIELD, BM25_TOP_N);
        }
    }

    void generate(Inputs inputs, Path output, AllFeaturesExtractor extractor,
                  String indexDescription, String textField, int topN) throws IOException {
        inputs.validate();
        if (Files.exists(output)) throw new FileAlreadyExistsException(output.toString());
        Path absoluteOutput = output.toAbsolutePath();
        Files.createDirectories(absoluteOutput.getParent());
        Path staging = Files.createTempDirectory(absoluteOutput.getParent(), output.getFileName() + ".building-");
        System.out.println("Writing " + extractor.getFeatureNames().size() + " features to " + staging);
        Map<String, List<Long>> groups = new HashMap<>();
        Path spoolPath = staging.resolve("raw_candidates.bin");
        List<String> missing = new ArrayList<>();
        List<String> readFiles = new ArrayList<>();
        long[] sourceRows = new long[3];
        long trainRows = 0, validRows = 0;
        int trainQueries, validQueries;
        try (RandomAccessFile spool = new RandomAccessFile(spoolPath.toFile(), "rw")) {
            // Same source and shard order as the three old getStream(1.0f, ...) calls.
            for (int source = 0; source < 3; source++) {
                for (int part = 0; part < inputs.partCount; part++) {
                    if (source == 2 && inputs.bm25Limit >= 0 && sourceRows[source] >= inputs.bm25Limit) break;
                    Path file = inputs.file(source, part);
                    if (!Files.exists(file)) {
                        missing.add(file.toString());
                        System.out.println("Skipping missing file (legacy behavior): " + file);
                        continue;
                    }
                    readFiles.add(file.toString());
                    System.out.println("Reading raw input: " + file);
                    try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                        String line;
                        long lineNumber = 0;
                        while ((source != 2 || inputs.bm25Limit < 0 || sourceRows[source] < inputs.bm25Limit)
                                && (line = reader.readLine()) != null) {
                            lineNumber++;
                            if (line.trim().isEmpty()) continue;
                            Candidate candidate;
                            try { candidate = Candidate.parse(line, source == 2); }
                            catch (RuntimeException e) { throw new IOException(file + ":" + lineNumber + ": " + e.getMessage(), e); }
                            groups.computeIfAbsent(candidate.query, key -> new ArrayList<>()).add(spool.getFilePointer());
                            candidate.write(spool);
                            sourceRows[source]++;
                        }
                    }
                }
                if (sourceRows[source] == 0 && !(source == 2 && inputs.bm25Limit == 0)) {
                    throw new IOException("No candidates read from " + inputs.directory(source));
                }
            }
            List<String> queries = new ArrayList<>(groups.keySet());
            Collections.shuffle(queries, new Random(SPLIT_SEED));
            trainQueries = (int) (queries.size() * TRAIN_QUERY_FRACTION);
            validQueries = queries.size() - trainQueries;
            if (trainQueries == 0 || validQueries == 0) throw new IOException("Need at least two query groups");
            long started = System.nanoTime();
            try (BufferedWriter train = Files.newBufferedWriter(staging.resolve("train.csv"), StandardCharsets.UTF_8);
                 BufferedWriter valid = Files.newBufferedWriter(staging.resolve("valid.csv"), StandardCharsets.UTF_8)) {
                writeHeader(train, extractor.getFeatureNames());
                writeHeader(valid, extractor.getFeatureNames());
                for (int i = 0; i < queries.size(); i++) {
                    String query = queries.get(i);
                    BufferedWriter writer = i < trainQueries ? train : valid;
                    for (long offset : groups.get(query)) {
                        spool.seek(offset);
                        Candidate candidate = Candidate.read(spool, query);
                        float[] features;
                        try { features = extractor.extract(query, candidate.docId, candidate.title, candidate.body); }
                        catch (RuntimeException e) { throw new IOException("Feature calculation failed for " + query + " / " + candidate.docId, e); }
                        writeRow(writer, candidate, features);
                        if (i < trainQueries) trainRows++; else validRows++;
                        long count = trainRows + validRows;
                        if (count % 1000 == 0) System.out.printf(Locale.ROOT, "%d rows, %.1f rows/s%n",
                                count, count / ((System.nanoTime() - started) / 1e9));
                    }
                }
            }
        }
        Files.delete(spoolPath);
        try (BufferedWriter schema = Files.newBufferedWriter(staging.resolve("feature_schema.csv"), StandardCharsets.UTF_8)) {
            schema.write("column_index,feature_name,family\r\n");
            for (String row : extractor.getSchemaRows()) {
                String[] fields = row.split("\t", -1);
                schema.write(csvField(fields[0]) + "," + csvField(fields[1]) + "," + csvField(fields[2]) + "\r\n");
            }
        }
        Files.write(staging.resolve("feature_names.txt"), extractor.getFeatureNames(), StandardCharsets.UTF_8);
        JSONObject manifest = new JSONObject();
        manifest.put("negativeInput", inputs.negative.toAbsolutePath().toString());
        manifest.put("positiveInput", inputs.positive.toAbsolutePath().toString());
        manifest.put("bm25Input", inputs.bm25.toAbsolutePath().toString());
        manifest.put("readFiles", readFiles);
        manifest.put("missingFiles", missing);
        manifest.put("sourceRows", sourceRows);
        manifest.put("partCount", inputs.partCount);
        manifest.put("bm25RowLimit", inputs.bm25Limit);
        manifest.put("index", indexDescription);
        manifest.put("textField", textField);
        manifest.put("documentTextSource", "title/body from raw input JSON; no document lookup in Lucene");
        manifest.put("seed", SPLIT_SEED);
        manifest.put("trainQueryFraction", TRAIN_QUERY_FRACTION);
        manifest.put("bm25TopN", topN);
        manifest.put("featureCount", extractor.getFeatureNames().size());
        manifest.put("features", extractor.getFeatureNames());
        manifest.put("trainRows", trainRows);
        manifest.put("validRows", validRows);
        manifest.put("trainQueries", trainQueries);
        manifest.put("validQueries", validQueries);
        manifest.put("header", true);
        manifest.put("format", "csv");
        manifest.put("encoding", "UTF-8");
        manifest.put("rankPolicy", "Input BM25 rank is not included in the new feature families");
        Files.write(staging.resolve("manifest.json"), manifest.toString(2).getBytes(StandardCharsets.UTF_8));
        Files.move(staging, absoluteOutput);
        System.out.printf("DONE: train=%d rows/%d queries, valid=%d rows/%d queries, output=%s%n",
                trainRows, trainQueries, validRows, validQueries, absoluteOutput);
    }

    static void writeHeader(Writer writer, List<String> features) throws IOException {
        writer.write("label,query,doc_id");
        for (String name : features) writer.write("," + csvField(name));
        writer.write("\r\n");
    }

    static void writeRow(Writer writer, Candidate row, float[] features) throws IOException {
        writer.write(csvField(row.label) + "," + csvField(row.query) + "," + csvField(row.docId));
        for (float value : features) writer.write("," + Float.toString(value));
        writer.write("\r\n");
    }

    static String csvField(String value) {
        if (value.indexOf(',') >= 0 || value.indexOf('"') >= 0 || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    static final class Candidate {
        final String label, query, docId, title, body;
        Candidate(String label, String query, String docId, String title, String body) {
            this.label = label; this.query = query; this.docId = docId; this.title = title; this.body = body;
        }
        static Candidate parse(String line, boolean bm25) {
            // Positive/random: query, 0, docId, label, JSON. BM25: query, 0, docId, JSON, rank.
            String[] fields = line.split("\t", 5);
            if (fields.length != 5 || fields[0].isEmpty() || fields[2].isEmpty()) {
                throw new IllegalArgumentException("Expected five raw TSV columns with query and docId");
            }
            String label = bm25 ? "0" : fields[3];
            if (!Float.isFinite(Float.parseFloat(label))) throw new IllegalArgumentException("Invalid label");
            if (bm25 && !Float.isFinite(Float.parseFloat(fields[4]))) throw new IllegalArgumentException("Invalid BM25 rank");
            String json = fields[bm25 ? 3 : 4];
            // Unescape only CSV-wrapped JSON; do not corrupt valid JSON containing empty strings.
            if (json.startsWith("\"") && json.endsWith("\"")) json = json.substring(1, json.length() - 1).replace("\"\"", "\"");
            JSONObject document = new JSONObject(json);
            return new Candidate(label, fields[0], fields[2], document.optString("title", ""), document.optString("body", ""));
        }
        void write(RandomAccessFile file) throws IOException {
            writeString(file, label); writeString(file, docId); writeString(file, title); writeString(file, body);
        }
        static Candidate read(RandomAccessFile file, String query) throws IOException {
            return new Candidate(readString(file), query, readString(file), readString(file), readString(file));
        }
        private static void writeString(RandomAccessFile file, String value) throws IOException {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            file.writeInt(bytes.length); file.write(bytes);
        }
        private static String readString(RandomAccessFile file) throws IOException {
            byte[] bytes = new byte[file.readInt()]; file.readFully(bytes);
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }
}
