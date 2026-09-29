package monoforest.impl.joint.factors;

import monoforest.impl.joint.JointFactor;
import monoforest.util.QueryTextUtils;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.*;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.search.similarities.ClassicSimilarity;
import org.apache.lucene.store.FSDirectory;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;

public class JointRankBM25 extends JointFactor {
    private static final String INDEX_PATH = "/Volumes/Ex_Volume/msmarcoProcces/lucene_index";
    private static final String TEXT_FIELD = "text";
    private static final String ID_FIELD = "DOCNO";

    private IndexReader reader;
    private IndexSearcher bm25Searcher;
    private IndexSearcher tfidfSearcher;
    private Analyzer analyzer;
    private QueryParser parser;
    @Override
    public float[] calculateScore(String query, String title, String document, String doc_id) {
        String rawQuery = query.trim();
        String cleanQuery = QueryTextUtils.removeLuceneSpecialOps(QueryTextUtils.removePunct(rawQuery));

        int maxResults = 1200;
        // Настройка парсера
        QueryParser parser = new QueryParser(TEXT_FIELD, analyzer);
        parser.setDefaultOperator(QueryParser.OR_OPERATOR);

        Query parsedQuery = null;
        try {
            parsedQuery = parser.parse(cleanQuery);


            // 1. Поиск по BM25 (инвертированный индекс)
            TopDocs hits = bm25Searcher.search(parsedQuery, maxResults);
            int rank = 1;
            for (ScoreDoc sd : hits.scoreDocs) {
                // 2. Расчет TF-IDF только для прошедших порог
                Document doc = reader.document(sd.doc);
                String docNo = doc.get(ID_FIELD);
                if(docNo.equals(doc_id)) {
                    return new float[]{(float)rank};
                }
                rank++;
            }

        } catch (ParseException | IOException e) {
            throw new RuntimeException(e);
        }
        return new float[]{(float) 1201};
    }

    @Override
    public ArrayList<float[]> calculateForQueries(ArrayList<String> queries, String title, String document, String doc_id) {
        return null;
    }

    @Override
    public String getName() {
        return "rank bm25";
    }

    @Override
    public int getFeatureQty() {
        return 1;
    }

    @Override
    public String getDescription() {
        return "считает ранг по bm25 ";
    }

    @Override
    public void prepare() {
        FSDirectory dir = null;
        try {
            dir = FSDirectory.open(Paths.get(INDEX_PATH));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        try {
            this.reader = DirectoryReader.open(dir);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        // Поисковик для BM25
        this.bm25Searcher = new IndexSearcher(reader);
        this.bm25Searcher.setSimilarity(new BM25Similarity(1.2f, 0.75f));

        // Поисковик для TF-IDF
        this.tfidfSearcher = new IndexSearcher(reader);
        this.tfidfSearcher.setSimilarity(new ClassicSimilarity());

        this.analyzer = new StandardAnalyzer();
        this.parser = new QueryParser(TEXT_FIELD, analyzer);
    }

    @Override
    public Query buildQuery(String[] queryStream, int featureIndex, Object... args) {
        return null;
    }

}
