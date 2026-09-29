package monoforest;

import com.fasterxml.jackson.databind.node.ObjectNode;
import monoforest.impl.AppConfig;
import monoforest.impl.MonomialCandidateSearch;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.*;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/** Standalone search service: trained monomial score > threshold, at most maxQty hits.
 * The model and index are loaded once. Each request has its own feature/query state.
 * Close only after all concurrent searches have completed.
 */
public class MonoforestSearch implements AutoCloseable {
    public static final String MODEL_PATH_PARAM = "modelPath";
    public static final String THRESHOLD_PARAM = "threshold";
    public static final String TITLE_FIELD_PARAM = "titleFieldName";
    public static final String DEFAULT_MODEL_PATH = "linear_monomials.json";
    public static final double DEFAULT_THRESHOLD = 0.0;

    private final String indexFieldName;
    private final String titleFieldName;
    private final double threshold;
    private final MonomialCandidateSearch engine;
    private final Directory directory;
    private final DirectoryReader reader;
    private final IndexSearcher searcher;
    private volatile boolean closed;

    /** Direct Java configuration, useful for experiments without a JSON config file. */
    public MonoforestSearch(String indexDir, String modelPath, double threshold,
                                     String indexFieldName, String titleFieldName) throws IOException {
        if (!Double.isFinite(threshold)) throw new IllegalArgumentException("threshold must be finite");
        this.threshold = threshold;
        this.indexFieldName = requireName(indexFieldName);
        this.titleFieldName = requireName(titleFieldName);
        Path model = Paths.get(requireName(modelPath));
        if (!Files.isRegularFile(model)) {
            throw new IOException("Monomial JSON model not found: " + model
                    + ". Run export_monomial_model(linear_monom_model, path) in model.ipynb first.");
        }
        engine = new MonomialCandidateSearch(model, indexFieldName, titleFieldName);
        Path index = Paths.get(indexDir == null || indexDir.isEmpty() ? AppConfig.getIndexDir() : indexDir);
        if (!Files.isDirectory(index)) throw new IOException("Lucene index directory not found: " + index);
        Directory openedDirectory = FSDirectory.open(index);
        DirectoryReader openedReader;
        try {
            openedReader = DirectoryReader.open(openedDirectory);
        } catch (IOException | RuntimeException error) {
            try { openedDirectory.close(); } catch (IOException closeError) { error.addSuppressed(closeError); }
            throw error;
        }
        directory = openedDirectory;
        reader = openedReader;
        searcher = new IndexSearcher(reader);
    }

    private static String requireName(String value) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException("Path/field name must not be empty");
        return value;
    }
    private void ensureOpen() { if (closed) throw new IllegalStateException("Provider is closed"); }
    /** Detailed result includes double raw scores, probabilities and the total-hit relation. */
    public ObjectNode search(String queryText, int maxQty) throws IOException {
        ensureOpen();
        Objects.requireNonNull(queryText, "queryText");
        if (maxQty <= 0) throw new IllegalArgumentException("maxQty must be positive");
        return engine.search(searcher, queryText, threshold, maxQty);
    }

    /** Optional document preview for the manual test program. */
    public Document getStoredDocument(String docId) throws IOException {
        ensureOpen();
        TopDocs hits = searcher.search(new TermQuery(new Term("id", docId)), 1);
        if (hits.scoreDocs.length == 0) throw new IOException("Document id not found: " + docId);
        return searcher.doc(hits.scoreDocs[0].doc, new HashSet<>(Arrays.asList("id", indexFieldName, titleFieldName)));
    }
    public String getIndexFieldName() { return indexFieldName; }
    public String getTitleFieldName() { return titleFieldName; }

    @Override public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        try { reader.close(); } finally { directory.close(); }
    }

    public static void main(String[] args) throws Exception { MonoforestSearchDemo.main(args); }
}
