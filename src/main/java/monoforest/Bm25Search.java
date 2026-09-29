package monoforest;

import monoforest.util.QueryTextUtils;
import org.apache.lucene.analysis.core.WhitespaceAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.*;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/** BM25 baseline for standalone retrieval experiments. */
public final class Bm25Search implements AutoCloseable {
    private final Directory directory;
    private final DirectoryReader reader;
    private final IndexSearcher searcher;
    private final String field;
    private final boolean exactMatch;
    public Bm25Search(Path index, String field, float k1, float b, boolean exactMatch) throws IOException {
        this.field = Objects.requireNonNull(field, "field");
        this.exactMatch = exactMatch;
        BM25Similarity similarity = new BM25Similarity(k1, b);
        directory = FSDirectory.open(index);
        try { reader = DirectoryReader.open(directory); }
        catch (IOException | RuntimeException error) {
            try { directory.close(); } catch (IOException closeError) { error.addSuppressed(closeError); }
            throw error;
        }
        searcher = new IndexSearcher(reader);
        searcher.setSimilarity(similarity);
    }
    public List<String> search(String text, int topK) throws Exception {
        Objects.requireNonNull(text, "text");
        if (topK <= 0) throw new IllegalArgumentException("topK must be positive");
        if (text.isEmpty()) return Collections.emptyList();
        Query query;
        try (WhitespaceAnalyzer analyzer = new WhitespaceAnalyzer()) {
            if (exactMatch) query = new TermQuery(new Term(field, text));
            else {
                String cleaned = QueryTextUtils.removeLuceneSpecialOps(QueryTextUtils.removePunct(text.trim()));
                if (cleaned.trim().isEmpty()) return Collections.emptyList();
                int clauses = 2 * cleaned.split("\\s+").length;
                if (clauses > BooleanQuery.getMaxClauseCount()) BooleanQuery.setMaxClauseCount(clauses);
                QueryParser parser = new QueryParser(field, analyzer);
                parser.setDefaultOperator(QueryParser.OR_OPERATOR);
                query = parser.parse(cleaned);
            }
        }
        List<String> ids = new ArrayList<>();
        for (ScoreDoc hit : searcher.search(query, topK).scoreDocs) {
            Document doc = searcher.doc(hit.doc);
            String id = doc.get("id");
            if (id == null) id = doc.get("DOCNO");
            if (id == null) throw new IOException("Index document has neither stored id nor DOCNO: " + hit.doc);
            ids.add(id);
        }
        return ids;
    }
    @Override public void close() throws IOException {
        try { reader.close(); } finally { directory.close(); }
    }
}
