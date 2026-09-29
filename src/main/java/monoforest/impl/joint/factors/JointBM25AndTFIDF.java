package monoforest.impl.joint.factors;

import com.google.common.base.Splitter;
import monoforest.impl.joint.JointFactor;
import monoforest.util.QueryTextUtils;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.index.*;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.*;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.search.similarities.ClassicSimilarity;
import org.apache.lucene.store.FSDirectory;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public class JointBM25AndTFIDF extends JointFactor {

    private static final String INDEX_PATH = "/Volumes/Ex_Volume/msmarcoProcces/lucene_index";

    private static final String TEXT_FIELD = "text";
    private static final String ID_FIELD = "DOCNO";

    private IndexSearcher searcher;
    private IndexReader reader;
    private IndexSearcher bm25Searcher;
    private IndexSearcher tfidfSearcher;
    private Analyzer analyzer;
    private List<LeafReaderContext> cachedLeaves;

    private static final Splitter mSpaceSplit = Splitter.on(' ').omitEmptyStrings().trimResults();

    @Override
    public String getName() {
        return "Joint_BM25_TFIDF";
    }

    @Override
    public int getFeatureQty() {
        return 2;
    }

    @Override
    public String getDescription() {
        return "Calculates BM25 and TF-IDF dynamically for passed queries";
    }

    @Override
    public void prepare() {
        try {
            // Инициализация Lucene (Индексы и Searcher'ы)
            FSDirectory dir = FSDirectory.open(Paths.get(INDEX_PATH));
            reader = DirectoryReader.open(dir);
            searcher = new IndexSearcher(reader);

            bm25Searcher = new IndexSearcher(reader);
            bm25Searcher.setSimilarity(new BM25Similarity(1.2f, 0.75f));

            tfidfSearcher = new IndexSearcher(reader);
            tfidfSearcher.setSimilarity(new ClassicSimilarity());

            this.analyzer = new StandardAnalyzer();
            this.cachedLeaves = reader.leaves();

            // Загрузка запросов удалена отсюда.
            System.out.println("JointBM25AndTFIDF prepared successfully.");
        } catch (IOException e) {
            e.printStackTrace();
            throw new RuntimeException("Failed to initialize Lucene index readers", e);
        }
    }

    @Override
    public Query buildQuery(String[] queryStream, int featureIndex, Object... args) {
        return null;
    }

    @Override
    public float[] calculateScore(String queryText, String title, String document, String doc_id) {
        ArrayList<String> q = new ArrayList<>(1);
        q.add(queryText);
        return calculateForQueries(q, title, document, doc_id).get(0);
    }

    @Override
    public ArrayList<float[]> calculateForQueries(ArrayList<String> queries, String title, String document, String doc_id) {
        ArrayList<float[]> results = new ArrayList<>(queries.size());

        try {
            int globalDocId = getLuceneDocId(doc_id);
            if (globalDocId == -1) {
                // Если документ не найден в индексе, возвращаем [0, 0] для всех запросов
                for (int i = 0; i < queries.size(); i++) results.add(new float[]{0f, 0f});
                return results;
            }

            // Находим нужный сегмент
            List<LeafReaderContext> leaves = this.cachedLeaves;
            int leafIdx = ReaderUtil.subIndex(globalDocId, leaves);
            LeafReaderContext targetContext = leaves.get(leafIdx);
            int localDocId = globalDocId - targetContext.docBase;

            // QueryParser не потокобезопасен, создаем экземпляр для текущего вызова
            QueryParser parser = new QueryParser(TEXT_FIELD, analyzer);
            parser.setDefaultOperator(QueryParser.OR_OPERATOR);

            for (String rawQuery : queries) {
                float bm25Score = 0f;
                float tfidfScore = 0f;

                // 1. Очистка запроса
                String cleanQuery = QueryTextUtils.removePunct(rawQuery.trim());
                cleanQuery = QueryTextUtils.removeLuceneSpecialOps(cleanQuery);

                if (cleanQuery.isEmpty()) {
                    results.add(new float[]{0f, 0f});
                    continue;
                }

                List<String> toks = mSpaceSplit.splitToList(cleanQuery);
                if (2 * toks.size() > BooleanQuery.getMaxClauseCount()) {
                    BooleanQuery.setMaxClauseCount(2 * toks.size());
                }

                try {
                    // 2. Парсинг и создание весов
                    Query parsedQuery = parser.parse(cleanQuery);
                    Weight bm25Weight = bm25Searcher.createWeight(parsedQuery, ScoreMode.COMPLETE, 1.0f);
                    Weight tfidfWeight = tfidfSearcher.createWeight(parsedQuery, ScoreMode.COMPLETE, 1.0f);

                    // 3. Вычисление BM25
                    Scorer bm25Scorer = bm25Weight.scorer(targetContext);
                    if (bm25Scorer != null) {
                        DocIdSetIterator it = bm25Scorer.iterator();
                        if (it.advance(localDocId) == localDocId) {
                            bm25Score = bm25Scorer.score();
                        }
                    }

                    // 4. Вычисление TF-IDF
                    Scorer tfidfScorer = tfidfWeight.scorer(targetContext);
                    if (tfidfScorer != null) {
                        DocIdSetIterator it = tfidfScorer.iterator();
                        if (it.advance(localDocId) == localDocId) {
                            tfidfScore = tfidfScorer.score();
                        }
                    }
                } catch (ParseException e) {
                    System.err.println("Warning: ParseException for query -> " + cleanQuery);
                }

                // Добавляем результаты для текущего запроса
                results.add(new float[]{bm25Score, tfidfScore});
            }

            return results;

        } catch (IOException e) {
            e.printStackTrace();
            // В случае критической ошибки ввода-вывода заполняем нулями
            for (int i = 0; i < queries.size(); i++) results.add(new float[]{0f, 0f});
            return results;
        }
    }

    private int getLuceneDocId(String docIdStr) throws IOException {
        Query idQuery = new TermQuery(new Term(ID_FIELD, docIdStr));
        TopDocs hits = searcher.search(idQuery, 1);

        if (hits.totalHits.value == 0) {
            return -1; // Не найден
        }
        return hits.scoreDocs[0].doc;
    }
}