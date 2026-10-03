package monoforest.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import monoforest.MonoforestSearch;
import monoforest.impl.features.queryFeatures.QueryFrequencyFamily;
import org.apache.lucene.document.Document;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Load the index once, run measured queries sequentially, return documents and elapsed wall time as JSON. */
public final class MonoforestRunner {
    private static final ObjectMapper JSON = new ObjectMapper();

    public static void main(String[] args) {
        if (args.length == 0 || Arrays.asList(args).contains("--help")) {
            System.out.println("Usage: java -Xmx4g -jar monoforest-0.1.0-SNAPSHOT-runner.jar\n"
                    + "  --index DIR --model FILE --query TEXT [--ram-root /dev/shm]\n"
                    + "  [--threshold 3] [--top-k 10] [--text-field text] [--title-field title] [--reserve-gib 12] [--repeat 1] [--hot false]\n"
                    + "Query-frequency TSV is bundled. Optional override before -jar: -Dmonoforest.queryFrequencyTsv=/path/to/queries.tsv\n"
                    + "Runs --repeat searches on one open index. --hot true prepares once and adds one reported warmup. stdout: JSON documents and timings. stderr: loading progress.\n"
                    + "search_ms includes search and fetching document texts; excludes loading and printing JSON.\n"
                    + "The owned tmpfs copy is removed on exit; the original index is untouched.");
            return;
        }
        try { System.out.println(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(run(args))); }
        catch (Exception error) {
            System.err.println("Monoforest: " + error);
            System.exit(1);
        }
    }

    private static void validateQueryFrequencySource(Path model) throws IOException {
        JsonNode exported = JSON.readTree(model.toFile());
        if (exported == null) throw new IOException("Empty model: " + model);
        Set<String> frequencyNames = Set.of(QueryFrequencyFamily.MIN_LOG_FREQUENCY,
                QueryFrequencyFamily.MAX_LOG_FREQUENCY, QueryFrequencyFamily.MEAN_LOG_FREQUENCY);
        for (JsonNode monomial : exported.path("monomials")) {
            for (JsonNode split : monomial.path("splits")) {
                if (frequencyNames.contains(split.path("feature_name").asText())) {
                    QueryFrequencyFamily.validateDefaultSource();
                    return;
                }
            }
        }
    }

    static ObjectNode run(String[] args) throws Exception {
        Set<String> allowed = Set.of("--index", "--model", "--query", "--ram-root", "--reserve-gib", "--threshold", "--top-k", "--text-field", "--title-field", "--repeat", "--hot");
        Map<String,String> options = new HashMap<>();
        for (int i = 0; i < args.length; i += 2) {
            if (!allowed.contains(args[i]) || i + 1 == args.length || options.put(args[i], args[i+1]) != null)
                throw new IllegalArgumentException("Unknown, duplicate or incomplete option: " + args[i]);
        }
        for (String key : List.of("--index", "--model", "--query"))
            if (!options.containsKey(key)) throw new IllegalArgumentException("Missing " + key);
        Path source = Path.of(options.get("--index")).toAbsolutePath();
        Path model = Path.of(options.get("--model")).toAbsolutePath();
        if (!Files.isDirectory(source) || !Files.isRegularFile(model)) throw new IOException("Index directory or model file does not exist");
        double threshold = Double.parseDouble(options.getOrDefault("--threshold", "3"));
        int topK = Integer.parseInt(options.getOrDefault("--top-k", "10"));
        String hotOption = options.getOrDefault("--hot", "false");
        if (!Set.of("true", "false").contains(hotOption)) throw new IllegalArgumentException("--hot must be true or false");
        boolean hot = Boolean.parseBoolean(hotOption);
        int repeat = Integer.parseInt(options.getOrDefault("--repeat", "1"));
        if (repeat <= 0) throw new IllegalArgumentException("--repeat must be positive");
        long reserve = Long.parseLong(options.getOrDefault("--reserve-gib", "12"));
        if (reserve < 0 || reserve > 1048576 || topK <= 0 || !Double.isFinite(threshold))
            throw new IllegalArgumentException("Invalid reserve, top-k or threshold");
        validateQueryFrequencySource(model);
        RamIndex ram = null;
        Thread cleanup = null;
        try {
            if (options.containsKey("--ram-root")) {
                System.err.println("Copying index to tmpfs...");
                ram = RamIndex.load(source, Path.of(options.get("--ram-root")), reserve << 30);
                RamIndex owned = ram;
                cleanup = new Thread(() -> { try { owned.close(); } catch (IOException e) { System.err.println(e); } });
                Runtime.getRuntime().addShutdownHook(cleanup);
            }
            Path index = ram == null ? source : ram.path;
            System.err.println("Opening model and index...");
            long openStart = System.nanoTime();
            try (MonoforestSearch search = new MonoforestSearch(index.toString(), model.toString(), threshold,
                    options.getOrDefault("--text-field", "text"), options.getOrDefault("--title-field", "title"))) {
                double openMillis = (System.nanoTime()-openStart)/1e6;
                ObjectNode response = JSON.createObjectNode().put("mode", ram == null ? "filesystem" : "tmpfs")
                        .put("source_index", source.toString()).put("active_index", index.toString())
                        .put("copy_to_ram_ms", ram == null ? 0 : ram.copyMillis)
                        .put("open_model_and_index_ms", openMillis)
                        .put("timing_scope", "Each search includes query construction, retrieval, explanations and fetching documents; excludes index copy, opening and JSON printing")
                        .put("warmup_queries", hot ? 1 : 0).put("query_count", repeat)
                        .put("benchmark_mode", hot ? "hot_prepared_query" : "end_to_end")
                        .put("primary_metric", hot ? "timings_ms.lucene_search_ms" : "search_ms");
                if (ram != null) response.put("index_bytes", ram.bytes);
                MonoforestSearch.PreparedSearch prepared = null;
                if (hot) {
                    System.err.println("Preparing query and query features once...");
                    long prepareStart = System.nanoTime();
                    prepared = search.prepareQuery(options.get("--query"));
                    response.put("prepare_query_ms", (System.nanoTime() - prepareStart) / 1e6);
                    System.err.println("Warming up: one full query, reported separately...");
                    ObjectNode warmup = runQuery(search, prepared, options.get("--query"), topK, 0);
                    response.set("warmup", warmup);
                    printTimings("Warmup", warmup);
                }
                com.fasterxml.jackson.databind.node.ArrayNode runs = response.putArray("runs");
                for (int run = 1; run <= repeat; run++) {
                    System.err.printf(Locale.ROOT, "Running measured query %d/%d...%n", run, repeat);
                    ObjectNode entry = runQuery(search, prepared, options.get("--query"), topK, run);
                    runs.add(entry);
                    // Keep the original fields as aliases for the FIRST measured run.
                    if (run == 1) {
                        response.set("search_ms", entry.get("search_ms"));
                        response.set("result", entry.get("result"));
                    }
                    printTimings("Query " + run + "/" + repeat, entry);
                }
                return response;
            }
        } finally {
            if (cleanup != null) Runtime.getRuntime().removeShutdownHook(cleanup);
            if (ram != null) ram.close();
        }
    }
    private static ObjectNode runQuery(MonoforestSearch search, MonoforestSearch.PreparedSearch prepared,
                                       String text, int topK, int ordinal) throws IOException {
        long queryStart = System.nanoTime();
        ObjectNode result = prepared == null ? search.search(text, topK) : prepared.search(topK);
        long fetchStart = System.nanoTime();
        for (JsonNode hit : result.path("candidates")) {
            Document document = search.getStoredDocument(hit.path("doc_id").asText());
            ObjectNode candidate = (ObjectNode)hit;
            candidate.put("title", document.get(search.getTitleFieldName()));
            candidate.put("text", document.get(search.getIndexFieldName()));
        }
        long finished = System.nanoTime();
        double searchMillis = (finished - queryStart) / 1e6;
        ObjectNode timings = ((ObjectNode) result.get("timings_ms")).deepCopy();
        timings.put("fetch_documents_ms", (finished - fetchStart) / 1e6);
        double measured = 0;
        for (JsonNode value : timings) measured += value.asDouble();
        timings.put("other_ms", Math.max(0, searchMillis - measured));
        ObjectNode entry = JSON.createObjectNode().put("run", ordinal).put("search_ms", searchMillis);
        entry.set("timings_ms", timings);
        entry.set("result", result);
        return entry;
    }

    private static void printTimings(String label, ObjectNode entry) {
        JsonNode t = entry.path("timings_ms");
        System.err.printf(Locale.ROOT,
                "%s: total=%.3f ms, build=%.3f, search=%.3f, explain=%.3f, ids=%.3f, documents=%.3f, other=%.3f%n",
                label, entry.path("search_ms").asDouble(), t.path("build_query_ms").asDouble(),
                t.path("lucene_search_ms").asDouble(), t.path("explain_ms").asDouble(),
                t.path("fetch_ids_ms").asDouble(), t.path("fetch_documents_ms").asDouble(),
                t.path("other_ms").asDouble());
    }

}
