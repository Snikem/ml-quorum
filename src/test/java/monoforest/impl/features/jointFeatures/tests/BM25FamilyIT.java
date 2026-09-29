package monoforest.impl.features.jointFeatures.tests;

import org.junit.Test;
import monoforest.impl.features.AvailableIndexTest;

import monoforest.impl.AppConfig;
import monoforest.impl.DocumentMarco;
import monoforest.impl.MyTokenizer;
import monoforest.impl.features.jointFeatures.BM25Family;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.core.WhitespaceAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.ReaderUtil;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.Weight;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;

import java.nio.file.Paths;

/**
 * Интеграционный JUnit-тест (mvn test -Pindex-tests) на индексе из AppConfig.
 * Запуск: mvn test -Pindex-tests. Настройки: monoforest.testQuery, monoforest.testThreshold, monoforest.testMaxHits.
 * Проверяется каждый возвращенный документ, но не полнота результатов во всем индексе.
 *
 * Результаты выводятся в консоль. Отдельный BM25FamilyTest проверяет
 * фиксированные значения на неизменной коллекции в памяти.
 */
public class BM25FamilyIT extends AvailableIndexTest {
    private static final String TEST_QUERY = "high blood pressure treatment symptoms";
    private static final double THRESHOLD = 4.0;
    private static final int MAX_HITS = 100;

    @Test
    public void verifiesFeatureValues() throws Exception {
        String queryText = System.getProperty("monoforest.testQuery", TEST_QUERY);
        double threshold = Double.parseDouble(System.getProperty("monoforest.testThreshold", Double.toString(THRESHOLD)));
        int maxHits = Integer.getInteger("monoforest.testMaxHits", MAX_HITS);
        if (!Double.isFinite(threshold) || threshold < 0 || maxHits <= 0) {
            throw new IllegalArgumentException("Для этого теста нужен конечный порог >= 0 и максимумДокументов > 0");
        }

        String indexPath = AppConfig.getIndexDir();
        String field = AppConfig.getTextField();

        try (Directory directory = FSDirectory.open(Paths.get(indexPath));
             DirectoryReader reader = DirectoryReader.open(directory);
             Analyzer analyzer = new WhitespaceAnalyzer();
             BM25Family family = new BM25Family(reader, field)) {
            MyTokenizer tokenizer = new MyTokenizer(analyzer);
            String[] queryTokens = tokenizer.tokenize(queryText).toArray(new String[0]);
            if (queryTokens.length == 0) throw new IllegalArgumentException("Запрос должен содержать слова");

            family.prepare();
            IndexSearcher searcher = new IndexSearcher(reader);
            searcher.setSimilarity(new BM25Similarity(BM25Family.DEFAULT_K1, BM25Family.DEFAULT_B));
            // Независимый стандартный запрос Lucene для проверки формулы BM25.
            BooleanQuery.Builder nativeBuilder = new BooleanQuery.Builder();
            for (String token : queryTokens) {
                nativeBuilder.add(new TermQuery(new Term(field, token)), BooleanClause.Occur.SHOULD);
            }
            Weight nativeWeight = searcher.createWeight(searcher.rewrite(nativeBuilder.build()), ScoreMode.COMPLETE, 1.0f);
            Query query = family.buildLuceneQuery(
                    BM25Family.BM25_SCORE, queryTokens, threshold, BM25Family.Comparison.GT);

            System.out.println("Индекс: " + indexPath);
            System.out.println("Поле: " + field + "; только body, без title; k1="
                    + BM25Family.DEFAULT_K1 + "; b=" + BM25Family.DEFAULT_B);
            System.out.println("Версия индекса: " + reader.getVersion()
                    + "; поколение commit: " + reader.getIndexCommit().getGeneration()
                    + "; numDocs: " + reader.numDocs() + "; maxDoc: " + reader.maxDoc());
            System.out.println("Запрос: " + queryText);
            System.out.println("Условие: BM25 > " + threshold);
            System.out.println("Lucene Query: " + query);

            TopDocs hits = searcher.search(query, maxHits);
            System.out.println("Найдено: " + hits.totalHits + "; проверяем: " + hits.scoreDocs.length);
            if (hits.scoreDocs.length == 0) {
                throw new AssertionError("Документов нет — проверка не выполнена. Уменьшите порог или измените запрос.");
            }

            System.out.println("doc_id\tquery\tthreshold\tlucene_bm25\tfamily_bm25\tnative_bm25\tstatus");
            int thresholdFailures = 0;
            int luceneFailures = 0;
            int nativeFailures = 0;
            int passed = 0;

            for (ScoreDoc hit : hits.scoreDocs) {
                Document stored = searcher.doc(hit.doc);
                String docId = stored.get("id");
                if (docId == null) docId = stored.get("DOCNO");
                String body = stored.get(field);
                if (docId == null || body == null) {
                    throw new IllegalStateException("Lucene doc=" + hit.doc
                            + ": для пересчета и будущих эталонов нужны сохраненные id/DOCNO и " + field);
                }

                DocumentMarco document = new DocumentMarco();
                document.setDoc_id(docId);
                document.setBody(body);
                document.setTokensBody(tokenizer.tokenize(body));

                float current = family.calculateFeatureByName(BM25Family.BM25_SCORE, queryTokens, document).value;
                LeafReaderContext leaf = reader.leaves().get(ReaderUtil.subIndex(hit.doc, reader.leaves()));
                float nativeScore = nativeWeight.explain(leaf, hit.doc - leaf.docBase).getValue().floatValue();
                boolean sameAsNative = exactlyEqual(current, nativeScore);
                if (!sameAsNative) nativeFailures++;
                boolean aboveThreshold = Float.isFinite(current) && current > threshold;
                boolean sameAsLucene = exactlyEqual(current, hit.score);
                if (!aboveThreshold) thresholdFailures++;
                if (!sameAsLucene) luceneFailures++;
                boolean ok = aboveThreshold && sameAsLucene && sameAsNative;
                if (ok) passed++;

                // Float.toString сохраняет достаточно цифр для восстановления того же float.
                System.out.println(tsv(docId) + "\t" + tsv(queryText) + "\t" + threshold
                        + "\t" + Float.toString(hit.score)
                        + "\t" + Float.toString(current)
                        + "\t" + Float.toString(nativeScore)
                        + "\t" + (ok ? "PASS" : "FAIL"));
            }

            System.out.println("Успешно: " + passed + "/" + hits.scoreDocs.length);
            System.out.println("Нарушений BM25 > n: " + thresholdFailures);
            System.out.println("Расхождений со значением Lucene: " + luceneFailures);
            System.out.println("Расхождений с обычным BM25 Lucene: " + nativeFailures);
            if (passed != hits.scoreDocs.length) {
                throw new AssertionError("BM25: обнаружены расхождения, см. строки FAIL");
            }
        }
    }

    private static boolean exactlyEqual(float left, float right) {
        return Float.isFinite(left) && Float.isFinite(right)
                && Float.floatToIntBits(left) == Float.floatToIntBits(right);
    }

    private static String tsv(String text) {
        return text.replace("\\", "\\\\").replace("\t", "\\t")
                .replace("\r", "\\r").replace("\n", "\\n");
    }
}
