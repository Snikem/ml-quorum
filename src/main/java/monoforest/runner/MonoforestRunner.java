package monoforest.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import monoforest.MonoforestSearch;
import org.apache.lucene.document.Document;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Load the index once, run exactly one query, return documents and elapsed wall time as JSON. */
public final class MonoforestRunner {
    private static final ObjectMapper JSON = new ObjectMapper();

    public static void main(String[] args) {
        if (args.length == 0 || Arrays.asList(args).contains("--help")) {
            System.out.println("Usage: java -Xmx4g -jar monoforest-0.1.0-SNAPSHOT-runner.jar\n"
                    + "  --index DIR --model FILE --query TEXT [--ram-root /dev/shm]\n"
                    + "  [--threshold 3] [--top-k 10] [--text-field text] [--title-field title] [--reserve-gib 12]\n"
                    + "Runs one query without warmup. stdout: JSON documents and timings. stderr: loading progress.\n"
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

    static ObjectNode run(String[] args) throws Exception {
        Set<String> allowed = Set.of("--index", "--model", "--query", "--ram-root", "--reserve-gib", "--threshold", "--top-k", "--text-field", "--title-field");
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
        long reserve = Long.parseLong(options.getOrDefault("--reserve-gib", "12"));
        if (reserve < 0 || reserve > 1048576 || topK <= 0 || !Double.isFinite(threshold))
            throw new IllegalArgumentException("Invalid reserve, top-k or threshold");
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
                System.err.println("Running one query...");
                long queryStart = System.nanoTime();
                ObjectNode result = search.search(options.get("--query"), topK);
                for (JsonNode hit : result.path("candidates")) {
                    Document document = search.getStoredDocument(hit.path("doc_id").asText());
                    ObjectNode candidate = (ObjectNode)hit;
                    candidate.put("title", document.get(search.getTitleFieldName()));
                    candidate.put("text", document.get(search.getIndexFieldName()));
                }
                double searchMillis = (System.nanoTime()-queryStart)/1e6;
                ObjectNode response = JSON.createObjectNode().put("mode", ram == null ? "filesystem" : "tmpfs")
                        .put("source_index", source.toString()).put("copy_to_ram_ms", ram == null ? 0 : ram.copyMillis)
                        .put("open_model_and_index_ms", openMillis).put("search_ms", searchMillis)
                        .put("timing_scope", "One search including features, scoring and fetching document texts; excludes index copy, opening and JSON printing")
                        .put("warmup_queries", 0);
                if (ram != null) response.put("index_bytes", ram.bytes);
                response.set("result", result);
                return response;
            }
        } finally {
            if (cleanup != null) Runtime.getRuntime().removeShutdownHook(cleanup);
            if (ram != null) ram.close();
        }
    }
}
