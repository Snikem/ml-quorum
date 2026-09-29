package monoforest.impl;

import org.json.JSONObject;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.GZIPInputStream;

public class FullFeatureDumper {

    //TODO:хард
    private static final String DOCS_INPUT_DIR = "/Volumes/Ex_Volume/msmarco/msmarco_v2_doc/";
    private static final String QUERIES_FILE = "/Volumes/Ex_Volume/msmarcoProcces/input_data/dev/QuestionFields.jsonl";
    private static final String OUTPUT_BINARY_FILE = "/Volumes/Ex_Volume/msmarcoProcces/full_features.bin";

    static class QueryInfo {
        String id;
        String text;
        float[] factors;
        public QueryInfo(String id, String text, float[] factors) {
            this.id = id;
            this.text = text;
            this.factors = factors;
        }
    }

    public static void main(String[] args) {
        System.out.println("=== Starting Feature Generator with PROFILING ===");


        System.out.println("Initializing FactorManager...");
        FactorManager manager = new FactorManager();

        System.out.println(manager.getVectorsName());

        System.out.println("Loading queries from: " + QUERIES_FILE);
        List<QueryInfo> queries = loadQueriesFromJson(QUERIES_FILE);
        System.out.println("Loaded " + queries.size() + " queries.");

        if (queries.isEmpty()) {
            System.err.println("Error: No queries loaded. Exiting.");
            return;
        }
        ArrayList<float[]> listQueriesFactors = new ArrayList<>();
        ArrayList<String> textQueries = new ArrayList<>();


        for( QueryInfo queryInfo : queries) {
            listQueriesFactors.add(queryInfo.factors);
            textQueries.add(queryInfo.text);
        }



        int totalPairsProcessed = 0;
        long startTime = System.currentTimeMillis();

        long totalExtractTime = 0;   // Время на manager.extractAll (математика)
        long totalLoopTime = 0;      // Общее время цикла обработки файла
        long stored = 0;
        try (DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(Paths.get(OUTPUT_BINARY_FILE))))) {

            for (int i = 0; i < 60; i++) {
                String fileName = String.format("msmarco_doc_%02d.gz", i);
                File file = new File(DOCS_INPUT_DIR, fileName);

                if (!file.exists()) {
                    System.err.println("Skipping missing file: " + fileName);
                    continue;
                }

                System.out.println("Processing file (" + (i + 1) + "/60): " + fileName);

                try (BufferedReader br = new BufferedReader(new InputStreamReader(
                        new GZIPInputStream(Files.newInputStream(file.toPath())), StandardCharsets.UTF_8))) {

                    String line;

                    while ((line = br.readLine()) != null) {
                        long tStartLoop = System.nanoTime();

                        try {

                            JSONObject docJson = new JSONObject(line);
                            String docId = docJson.getString("docid");
                            String title = docJson.optString("title", "");
                            String headings = docJson.optString("headings", "");
                            String body = docJson.optString("body", "");
                            String fullDocText = headings + "\n" + body;

                            // --- Внутренний цикл по запросам ---


                            // --- ТАЙМЕР 2: Расчет факторов (Heavy Math) ---
                            long t2 = System.nanoTime();

                            ArrayList<float[]> res =  manager.extractJointFactorsByQueries(textQueries, title, body, docId);
                            long t3 = System.nanoTime();


                            totalExtractTime += (t3 - t2);

                            for (int j = 0; j < res.size(); j++) {

                                String currentQueryId = queries.get(j).id;
                                // 2. Берем итоговый вектор факторов
                                float[] vector = res.get(j);

                                if (vector[0] > 0.4 && vector[1] > 0.4) {
                                    dos.writeUTF(currentQueryId); // ID Запроса
                                    dos.writeUTF(docId);          // ID Документа (одинаковый для всей пачки)

                                    // Пишем сам вектор
                                    for (float f : vector) {
                                        dos.writeFloat(f);
                                    }
                                    stored++;

                                }
                            }

                            totalPairsProcessed++;
                            if (totalPairsProcessed % 1000 == 0) {
                                printBestMatchDebugInfo(docId, fullDocText, textQueries, res);
                            }


                        } catch (Exception e) {
                            System.out.println("eeee + "+e);
                        }

                        long tEndLoop = System.nanoTime();
                        totalLoopTime += (tEndLoop - tStartLoop);

                        int docsDone = totalPairsProcessed;
                        if (docsDone > 0 && docsDone % 1000 == 0) {
                            printProfileStats(docsDone, totalPairsProcessed, totalExtractTime, totalLoopTime);
                            System.out.println("процент записи  " +  ((float)stored / (float)((long) totalPairsProcessed * textQueries.size())));

                            // Сбрасываем таймеры, чтобы видеть скорость ТЕКУЩЕГО блока, а не среднюю с начала
                            // (Если хотите среднюю с начала - закомментируйте сброс)
                            totalExtractTime = 0;
                            totalLoopTime = 0;
                        }
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }

            dos.flush();

        } catch (IOException e) {
            e.printStackTrace();
            return;
        }

        System.out.println("Patching header...");
        try (RandomAccessFile raf = new RandomAccessFile(OUTPUT_BINARY_FILE, "rw")) {
            raf.seek(0);
            raf.writeInt(totalPairsProcessed);
        } catch (IOException e) {
            e.printStackTrace();
        }

        long endTime = System.currentTimeMillis();
        System.out.println("Done in " + (endTime - startTime) / 1000 + " sec.");
    }

    private static void printProfileStats(int docsDone, int pairsDone,long tExtract, long tTotal) {
        // Переводим в миллисекунды для читаемости

        double msExtract = tExtract / 1_000_000.0;
        double msTotal = tTotal / 1_000_000.0;

        // Вычисляем проценты
        // Обратите внимание: сумма может не давать 100%, так как есть накладные расходы Java (циклы, GC)
        double pExtract = (msExtract / msTotal) * 100;

        System.out.printf("\rDocs: %d | Pairs: %d | Extract: %.0f (%.1f%%)",
                docsDone, pairsDone,  msExtract, pExtract);
    }

    private static List<QueryInfo> loadQueriesFromJson(String filePath) {
        List<QueryInfo> list = new ArrayList<>();
        FactorManager manager = new FactorManager();
        try (BufferedReader br = Files.newBufferedReader(Paths.get(filePath), StandardCharsets.UTF_8)) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                try {
                    JSONObject json = new JSONObject(line);
                    String id = json.optString("DOCNO");
                    String text = json.optString("text_raw");
                    if (!id.isEmpty() && !text.isEmpty()) {
                        list.add(new QueryInfo(id, text, manager.extractQueryFactors(text)));
                    }
                } catch (Exception e) {}
            }
        } catch (IOException e) { e.printStackTrace(); }
        return list;
    }


    /**
     * Выводит полный текст документа, текст случайного запроса и их ИТОГОВЫЙ вектор.
     */
    private static void printRandomDebugInfo(String docId, String fullDocText, ArrayList<String> queries, ArrayList<float[]> finalVectors) {
        if (queries.isEmpty()) return;

        // Выбираем случайный индекс запроса из текущей пачки
        int rIdx = java.util.concurrent.ThreadLocalRandom.current().nextInt(queries.size());

        String randomQuery = queries.get(rIdx);
        float[] finalVector = finalVectors.get(rIdx);

        System.out.println("\n================ [DEBUG SAMPLE] ================");
        System.out.println("📄 DOC ID: " + docId);

        // ВЫВОД ПОЛНОГО ДОКУМЕНТА (как вы просили)
        System.out.println("📜 DOCUMENT TEXT:\n" + fullDocText);

        System.out.println("\n❓ RANDOM QUERY (" + rIdx + "): " + randomQuery);

        // Вывод вектора в красивом формате
        System.out.print("📊 FINAL VECTOR (" + finalVector.length + "): [");
        for (int i = 0; i < finalVector.length; i++) {
            // Форматируем до 4 знаков, чтобы влезало в экран
            System.out.printf("%.4f", finalVector[i]);
            if (i < finalVector.length - 1) System.out.print(", ");
        }
        System.out.println("]");
        System.out.println("================================================\n");
    }

    private static void printBestMatchDebugInfo(String docId, String fullDocText, ArrayList<String> queries, ArrayList<float[]> finalVectors) {
        if (queries.isEmpty() || finalVectors.isEmpty()) return;

        // ИНДЕКСЫ ВАШИХ ФИЧЕЙ (Проверьте по вашему коду!)
        // Если Joint_BM25_TFIDF идет первым, то BM25 = 0, TF-IDF = 1
        int bm25Idx = 0;
        int tfidfIdx = 1;

        int nonZeroCount = 0;
        float maxScore = -1f;
        int bestIdx = -1;

        // 1. Проходим по всем результатам и ищем ненулевые совпадения
        for (int i = 0; i < finalVectors.size(); i++) {
            float[] vec = finalVectors.get(i);

            // Проверяем размер вектора на всякий случай
            if (vec.length <= bm25Idx) continue;

            float score = vec[bm25Idx];

            if (score > 0.0001f) {
                nonZeroCount++;
                // Ищем максимальный скор, чтобы показать самый наглядный пример
                if (score > maxScore) {
                    maxScore = score;
                    bestIdx = i;
                }
            }
        }

        // 2. Если совпадений нет вообще — ничего не выводим (или выводим короткое сообщение)
        if (nonZeroCount == 0) {
            // Раскомментируйте, если хотите видеть отчеты о "пустых" документах
            // System.out.println("DocID: " + docId + " | No matches found in " + queries.size() + " queries.");
            return;
        }

        // 3. Выводим информацию о ЛУЧШЕМ совпадении
        String bestQuery = queries.get(bestIdx);
        float[] bestVector = finalVectors.get(bestIdx);

        System.out.println("\n✅ ================ [MATCH FOUND] ================");
        System.out.println("📄 DOC ID: " + docId);
        System.out.println("📊 Stats: " + nonZeroCount + " non-zero matches out of " + queries.size() + " queries.");

        // Выводим фрагмент текста (первые 300 символов), чтобы не засорять консоль
        String snippet = (fullDocText.length() > 300) ? fullDocText.substring(0, 300) + "..." : fullDocText;
        System.out.println("📜 Text Snippet:\n" + snippet.replace("\n", " "));

        System.out.println("\n🏆 BEST MATCH QUERY (" + bestIdx + "): [" + bestQuery + "]");

        // Выводим ключевые значения
        System.out.printf("👉 BM25:   %.4f%n", bestVector[bm25Idx]);
        System.out.printf("👉 TF-IDF: %.4f%n", bestVector[tfidfIdx]);

        // Полный вектор (опционально)
        System.out.print("📦 Full Vector: " + java.util.Arrays.toString(bestVector));
        System.out.println("\n=================================================\n");
    }
}