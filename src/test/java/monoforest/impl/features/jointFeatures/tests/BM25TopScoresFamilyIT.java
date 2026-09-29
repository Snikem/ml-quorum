package monoforest.impl.features.jointFeatures.tests;

import org.junit.Test;
import monoforest.impl.features.AvailableIndexTest;

import monoforest.impl.AppConfig;
import monoforest.impl.DocumentMarco;
import monoforest.impl.MyTokenizer;
import monoforest.impl.features.FeatureBase;
import monoforest.impl.features.jointFeatures.BM25Family;
import monoforest.impl.features.jointFeatures.BM25TopScoresFamily;
import org.apache.lucene.analysis.core.WhitespaceAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.*;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;

import java.nio.file.Paths;
import java.util.List;

/** Реальный индекс из AppConfig. Запуск: mvn test -Pindex-tests. Параметры: monoforest.testQuery, monoforest.testTopN. */
public class BM25TopScoresFamilyIT extends AvailableIndexTest {
    private static final String TEST_QUERY = "high blood pressure treatment symptoms";
    private static final int TOP_N = 3;

    @Test
    public void verifiesFeatureValues() throws Exception {
        String queryText = System.getProperty("monoforest.testQuery", TEST_QUERY);
        int n = Integer.getInteger("monoforest.testTopN", TOP_N);
        if (n <= 0) throw new IllegalArgumentException("n должен быть > 0");
        String path = AppConfig.getIndexDir();
        String field = AppConfig.getTextField();
        try (Directory directory = FSDirectory.open(Paths.get(path));
             DirectoryReader reader = DirectoryReader.open(directory);
             WhitespaceAnalyzer analyzer = new WhitespaceAnalyzer();
             BM25TopScoresFamily family = new BM25TopScoresFamily(reader, field, n);
             BM25Family documentFamily = new BM25Family(reader, field)) {
            family.prepare();
            documentFamily.prepare();
            MyTokenizer tokenizer = new MyTokenizer(analyzer);
            String[] tokens = tokenizer.tokenize(queryText).toArray(new String[0]);
            if (tokens.length == 0) throw new IllegalArgumentException("Введите непустой запрос");
            IndexSearcher searcher = new IndexSearcher(reader);
            searcher.setSimilarity(new BM25Similarity(BM25Family.DEFAULT_K1, BM25Family.DEFAULT_B));
            BooleanQuery.Builder query = new BooleanQuery.Builder();
            for (String token : tokens) query.add(new TermQuery(new Term(field, token)), BooleanClause.Occur.SHOULD);
            TopDocs hits = searcher.search(query.build(), n);
            if (hits.scoreDocs.length == 0) throw new AssertionError("Нет документов для проверки, измените запрос");
            List<FeatureBase> features = family.calculateAllFeaturesInFamily(tokens, null);
            List<String> names = family.getAllFeaturesNames();
            if (features.size() != n || names.size() != n) throw new AssertionError("Число фичей должно быть равно n");

            System.out.println("Индекс: " + path + "; версия: " + reader.getVersion() + "; запрос: " + queryText);
            System.out.println("n=" + n + "; найдено в top-n: " + hits.scoreDocs.length);
            System.out.println("feature\tdoc_id\ttop_feature\tlucene_score\trecomputed_bm25\tstatus");
            for (int i = 0; i < n; i++) {
                float expected = i < hits.scoreDocs.length ? hits.scoreDocs[i].score : 0f;
                float recomputed = 0f;
                String docId = "<нет документа>";
                if (i < hits.scoreDocs.length) {
                    Document stored = searcher.doc(hits.scoreDocs[i].doc);
                    docId = stored.get("id");
                    if (docId == null) docId = stored.get("DOCNO");
                    String body = stored.get(field);
                    if (docId == null || body == null) throw new IllegalStateException("Нужны сохраненные id/DOCNO и " + field);
                    DocumentMarco document = new DocumentMarco();
                    document.setDoc_id(docId);
                    document.setTokensBody(tokenizer.tokenize(body));
                    recomputed = documentFamily.calculateFeatureByName(BM25Family.BM25_SCORE, tokens, document).value;
                }
                float single = family.calculateFeatureByName(names.get(i), tokens, null).value;
                if (!same(expected, features.get(i).value) || !same(expected, single) || !same(expected, recomputed)) {
                    throw new AssertionError(names.get(i) + ": top=" + features.get(i).value
                            + ", single=" + single + ", Lucene=" + expected + ", пересчет=" + recomputed);
                }
                System.out.println(names.get(i) + "\t" + docId + "\t" + features.get(i).value
                        + "\t" + expected + "\t" + recomputed + "\tPASS");
            }
            System.out.println("PASS: все " + n + " фичей совпали с выдачей и пересчетом BM25.");
        }
    }

    private static boolean same(float left, float right) {
        return Float.isFinite(left) && Float.isFinite(right) && Float.floatToIntBits(left) == Float.floatToIntBits(right);
    }
}
