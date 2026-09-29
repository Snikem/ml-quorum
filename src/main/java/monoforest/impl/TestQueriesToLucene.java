package monoforest.impl;

import monoforest.impl.joint.factors.*;

import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.*;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class TestQueriesToLucene {

    private static final String INDEX_PATH = "/Volumes/Ex_Volume/msmarcoProcces/lucene_index_positions";
    // Укажи здесь название поля, в котором хранится текст документа в индексе
    private static final String FIELD_NAME = "text";

    public static void main(String[] args) {
        String testQuery = "grammar terms medicine today";
        // Простая токенизация для генерации Lucene запроса (в проде используй свой tokenizeAndClean)
        MyTokenizer myTokenizer = new MyTokenizer();
        String[] queryStream = myTokenizer.tokenize(testQuery).toArray(new String[0]);

        try {
            // 1. Открываем индекс
            Directory dir = FSDirectory.open(Paths.get(INDEX_PATH));
            IndexReader reader = DirectoryReader.open(dir);
            IndexSearcher searcher = new IndexSearcher(reader);

            // 2. Строим запрос для проверки window8Count > 0.5 (то есть хотя бы 1 биграмма с окном 8)
            Query luceneQuery = buildWindow8Query(queryStream, FIELD_NAME);
            System.out.println("Сгенерированный запрос: " + luceneQuery.toString());

            TopDocs topDocs = searcher.search(luceneQuery, 10000);
            ScoreDoc[] hits = topDocs.scoreDocs;
            System.out.println("Всего найдено документов в Lucene: " + topDocs.totalHits.value);

            if (hits.length == 0) {
                System.out.println("Документы не найдены. Тест завершен.");
                return;
            }

            // 3. Равномерно берем до 1000 документов
            int numSamples = Math.min(534, hits.length);
            List<ScoreDoc> sampledHits = new ArrayList<>();
            double step = (double) hits.length / numSamples;

            for (int i = 0; i < numSamples; i++) {
                int index = (int) (i * step);
                sampledHits.add(hits[index]);
            }

            System.out.println("Отобрано для проверки: " + sampledHits.size() + " документов.");

            // 4. Подготавливаем твой класс фичей
            JointUnorderedWindow cl = new JointUnorderedWindow();
            cl.prepare();

            int passed = 0;
            int failed = 0;

            // 5. Прогоняем тест
            for (ScoreDoc hit : sampledHits) {
                Document luceneDoc = searcher.doc(hit.doc);
                String rawBody = luceneDoc.get(FIELD_NAME);

                if (rawBody == null) {
                    rawBody = "";
                    System.out.println("CHU BLY");
                }

                // Вызываем твою функцию, передавая ОЧИЩЕННЫЙ текст
                float[] scores = cl.calculateScore(testQuery, "mock_title", rawBody, "msmarco_doc_57_965596910");
                float window8Count = scores[3];

                if (window8Count > 0.5) {
                    passed++;
                } else {
                    System.out.println(rawBody);
                    System.out.println("_______________________________________");
                    failed++;
                }
            }
            System.out.println("FAIL_SUKA"+failed);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Строит Lucene-запрос для окна 8, создавая комбинации из всех уникальных слов запроса.
     * Эмулирует глобальное неупорядоченное скользящее окно.
     */
    private static Query buildWindow8Query(String[] tokens, String fieldName) {
        if (tokens == null || tokens.length < 2) {
            return new BooleanQuery.Builder().build();
        }

        // 1. Оставляем только уникальные токены, как в твоем алгоритме
        Set<String> uniqueTerms = new HashSet<>();
        for (String token : tokens) {
            uniqueTerms.add(token);
        }

        List<String> uniqueList = new ArrayList<>(uniqueTerms);

        // Если уникальных слов меньше 2, пар быть не может
        if (uniqueList.size() < 2) {
            return new BooleanQuery.Builder().build();
        }

        BooleanQuery.Builder booleanQueryBuilder = new BooleanQuery.Builder();

        // 2. Строим все возможные комбинации пар
        for (int i = 0; i < uniqueList.size() - 1; i++) {
            for (int j = i + 1; j < uniqueList.size(); j++) {
                String w1 = uniqueList.get(i);
                String w2 = uniqueList.get(j);

                // Прямой порядок: w1 -> w2
                PhraseQuery.Builder pqBuilderForward = new PhraseQuery.Builder();
                pqBuilderForward.add(new Term(fieldName, w1));
                pqBuilderForward.add(new Term(fieldName, w2));
                pqBuilderForward.setSlop(8);
                booleanQueryBuilder.add(pqBuilderForward.build(), BooleanClause.Occur.SHOULD);

                // Обратный порядок: w2 -> w1
                // Добавляем явно, чтобы избежать штрафов Lucene за перестановку в slop
                PhraseQuery.Builder pqBuilderBackward = new PhraseQuery.Builder();
                pqBuilderBackward.add(new Term(fieldName, w2));
                pqBuilderBackward.add(new Term(fieldName, w1));
                pqBuilderBackward.setSlop(8);
                booleanQueryBuilder.add(pqBuilderBackward.build(), BooleanClause.Occur.SHOULD);
            }
        }

        // Больше 0.5 означает хотя бы 1 совпадение любой из сгенерированных пар
        booleanQueryBuilder.setMinimumNumberShouldMatch(1);

        return booleanQueryBuilder.build();
    }
}
