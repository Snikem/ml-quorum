package monoforest.impl;

import monoforest.impl.joint.factors.JointSmarterBigramTrigram;
import monoforest.impl.joint.factors.JointUnorderedWindow;
import org.json.JSONObject;
import org.rocksdb.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPInputStream;
import java.util.concurrent.TimeUnit;

public class deleteForOnce {
    static {
        RocksDB.loadLibrary();
    }

    private static final String QUERY_FEATURES_TSV = "/Volumes/Ex_Volume/msmarcoProcces/query_features.tsv";
    private static final String DOC_FEATURES_DIR = "/Volumes/Ex_Volume/msmarcoProcces/doc_features_split/";

    private static Map<String, float[]> queryVectors;
    private static Map<String, float[]> docVectors;

    private static Map<String, float[]> loadQueryFeatures() throws Exception {
        System.out.print("Загрузка векторов запросов... ");
        Map<String, float[]> map = new HashMap<>();

        Path path = Paths.get(QUERY_FEATURES_TSV);
        if (!Files.exists(path)) {
            System.err.println("Файл " + QUERY_FEATURES_TSV + " не найден!");
            return map;
        }

        try (BufferedReader br = Files.newBufferedReader(path)) {
            String line;
            while ((line = br.readLine()) != null) {
                String[] parts = line.split("\t");
                if (parts.length < 2) continue;

                String qId = parts[0];
                String[] strFeats = parts[1].split(",");
                float[] feats = new float[strFeats.length];
                for (int i = 0; i < strFeats.length; i++) {
                    feats[i] = Float.parseFloat(strFeats[i]);
                }
                map.put(qId, feats);
            }
        }
        System.out.println("Готово! Загружено запросов: " + map.size());
        return map;
    }

    // ==========================================
    // ШАГ 2: Чтение 60 бинарных файлов документов
    // ==========================================
    private static Map<String, float[]> loadDocFeatures() throws Exception {
        System.out.println("Загрузка 12 млн векторов документов в память (это займет время и RAM)...");
        // Задаем начальную емкость (capacity), чтобы избежать лишних реаллокаций памяти
        Map<String, float[]> map = new HashMap<>(13_000_000);

        for (int i = 0; i < 60; i++) {
            String fileName = String.format("doc_features_%02d.bin", i);
            Path filePath = Paths.get(DOC_FEATURES_DIR, fileName);

            if (!Files.exists(filePath)) continue;

            try (DataInputStream dis = new DataInputStream(new BufferedInputStream(Files.newInputStream(filePath)))) {
                int count = dis.readInt();
                int featSize = dis.readInt();

                for (int j = 0; j < count; j++) {
                    String docId = dis.readUTF();
                    float[] feats = new float[featSize];
                    for (int k = 0; k < featSize; k++) {
                        feats[k] = dis.readFloat();
                    }
                    map.put(docId, feats);
                }
            }
            if ((i + 1) % 10 == 0) {
                System.out.println("  Прочитано файлов: " + (i + 1) + "/60...");
            }
        }
        System.out.println("Готово! Загружено документов: " + map.size());
        return map;
    }

    public static void main(String[] args) throws Exception {
        deleteForOnce ff = new deleteForOnce();
        // 1. Загрузка векторов запросов
        queryVectors = loadQueryFeatures();

        // 2. Загрузка векторов документов (ВНИМАНИЕ: Требует много RAM!)
        docVectors = loadDocFeatures();

        ff.buildAllFeatures("/Volumes/Ex_Volume/msmarcoProcces/input_data/dev/QuestionFields.jsonl",
                "/Volumes/Ex_Volume/msmarco/msmarco_v2_doc",
                "/Volumes/Ex_Volume/msmarcoProcces/bms",
                "/Volumes/Ex_Volume/msmarcoProcces/itog2Factor");
    }

    public void buildAllFeatures(String queriesFilePath, String docsDir, String candidatesDir, String outputDir) {
        long globalStartTime = System.currentTimeMillis();

        System.out.println("[START] Загрузка запросов...");
        Map<String, String> queryMap = loadQueries(queriesFilePath);
        System.out.println("[INFO] Загружено запросов: " + queryMap.size());

        //int[] targetSplits = {1};
        Path outDirPath = Paths.get(outputDir);

        try {
            if (!Files.exists(outDirPath)) Files.createDirectories(outDirPath);

            //for (int splitId : targetSplits) {
            for(int splitId = 0; splitId < 60; splitId++){
                String splitIdStr = String.format("%02d", splitId);
                System.out.println("\n" + "=".repeat(50));
                System.out.println(">>> СПЛИТ " + splitIdStr);

                Path docsFile = Paths.get(docsDir, "msmarco_doc_" + splitIdStr + ".gz");
                Path candidatesFile = Paths.get(candidatesDir, "split_" + splitIdStr + ".jsonl");
                Path outputFile = outDirPath.resolve("features_" + splitIdStr + ".jsonl");
                Path dbPath = outDirPath.resolve("temp_rocksdb_" + splitIdStr);

                if (!Files.exists(candidatesFile) || !Files.exists(docsFile)) {
                    System.out.println("[SKIP] Файлы не найдены");
                    continue;
                }

                try (Options options = new Options().setCreateIfMissing(true);
                     RocksDB db = RocksDB.open(options, dbPath.toString())) {

                    // ЭТАП 1: ИНДЕКСАЦИЯ
                    long t1 = System.currentTimeMillis();
                    indexDocsToSSD(docsFile, db);
                    long tIndex = System.currentTimeMillis() - t1;
                    System.out.printf("  [TIMER] Индексация на SSD завершена за: %d сек\n", tIndex / 1000);

                    // ЭТАП 2: ОБРАБОТКА
                    long t2 = System.currentTimeMillis();
                    processSplitWithSSD(candidatesFile, outputFile, queryMap, db);
                    long tProc = System.currentTimeMillis() - t2;
                    System.out.printf("  [TIMER] Извлечение фичей завершено за: %d сек\n", tProc / 1000);

                } catch (RocksDBException e) {
                    System.err.println("Ошибка БД: " + e.getMessage());
                } finally {
                    deleteDirectory(dbPath.toFile());
                    System.gc();
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        System.out.printf("\n[DONE] Все сплиты готовы. Общее время: %d мин\n",
                TimeUnit.MILLISECONDS.toMinutes(System.currentTimeMillis() - globalStartTime));
    }

    private void indexDocsToSSD(Path docsFile, RocksDB db) throws IOException {
        System.out.println("  [1/2] Запись документов в RocksDB...");
        try (FileInputStream fis = new FileInputStream(docsFile.toFile());
             GZIPInputStream gis = new GZIPInputStream(fis);
             BufferedReader br = new BufferedReader(new InputStreamReader(gis, StandardCharsets.UTF_8))) {

            String line;
            long count = 0;
            long start = System.currentTimeMillis();

            while ((line = br.readLine()) != null) {
                try {
                    JSONObject docJson = new JSONObject(line);
                    String id = docJson.optString("docid", "");
                    if (!id.isEmpty()) {
                        db.put(id.getBytes(StandardCharsets.UTF_8), line.getBytes(StandardCharsets.UTF_8));
                        count++;
                    }
                    if (count % 100000 == 0) {
                        double sec = (System.currentTimeMillis() - start) / 1000.0;
                        System.out.printf("    ... заиндексировано %d доков (скорость: %.0f док/сек)\n", count, count / sec);
                    }
                } catch (Exception ignored) {}
            }
            System.out.println("    Всего документов в базе: " + count);
        }
    }

    private void processSplitWithSSD(Path candidatesFile, Path outputFile, Map<String, String> queryMap, RocksDB db) {
        System.out.println("  [2/2] Генерация признаков...");
        FactorManager factorManager = new FactorManager();

        long count = 0;
        long found = 0;
        long tStart = System.currentTimeMillis();

        // Таймеры для внутренней аналитики
        long timeDB = 0;
        long timeFactors = 0;

        try (BufferedReader reader = Files.newBufferedReader(candidatesFile, StandardCharsets.UTF_8);
             BufferedWriter writer = Files.newBufferedWriter(outputFile, StandardCharsets.UTF_8)) {

            String line;
            while ((line = reader.readLine()) != null) {
                count++;
                JSONObject candJson = new JSONObject(line);
                String qId = candJson.getString("query_id");
                String docId = candJson.getString("doc_no");

                // Чтение из БД
                long tDbStart = System.nanoTime();
                byte[] docBytes = db.get(docId.getBytes(StandardCharsets.UTF_8));
                timeDB += (System.nanoTime() - tDbStart);

                if (docBytes == null) continue;
                found++;

                // Экстракция
                long tFactStart = System.nanoTime();
                JSONObject docJson = new JSONObject(new String(docBytes, StandardCharsets.UTF_8));
                String queryText = queryMap.get(qId);

                if (queryText != null) {
                    JointUnorderedWindow f1 = new JointUnorderedWindow();
                    f1.prepare();

                    JointSmarterBigramTrigram f2 = new JointSmarterBigramTrigram();
                    f2.prepare();

                    float[] score1 = f1.calculateScore(queryText, docJson.optString("title"), docJson.optString("body"), docId);
                    float[] score2 = f2.calculateScore(queryText, docJson.optString("title"), docJson.optString("body"), docId);
                    float[] combined = new float[8];
                    combined[0] = score1[0];
                    combined[1] = score1[1];
                    combined[2] = score1[2];
                    combined[3] = score1[3];
                    combined[4] = score2[0];
                    combined[5] = score2[1];
                    combined[6] = score2[2];
                    combined[7] = score2[3];

                    JSONObject res = new JSONObject();
                    res.put("query_id", qId);
                    res.put("doc_id", docId);
                    res.put("features", combined);
                    writer.write(res.toString() + "\n");
                }
                timeFactors += (System.nanoTime() - tFactStart);

                if (count % 20000 == 0) {
                    double elapsed = (System.currentTimeMillis() - tStart) / 1000.0;
                    System.out.printf("    Обработано строк: %d | Найдено в БД: %d | Время: %.1f сек\n", count, found, elapsed);
                    System.out.printf("    (Среднее чтение БД: %.3f ms | Средний FactorManager: %.3f ms)\n",
                            (timeDB / (double)count) / 1e6, (timeFactors / (double)found) / 1e6);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // Вспомогательные методы (deleteDirectory, combineArrays, loadQueries) остаются без изменений
    private void deleteDirectory(File file) {
        File[] contents = file.listFiles();
        if (contents != null) {
            for (File f : contents) deleteDirectory(f);
        }
        file.delete();
    }

    private Map<String, String> loadQueries(String filePath) {
        Map<String, String> map = new HashMap<>();
        try (BufferedReader br = Files.newBufferedReader(Paths.get(filePath), StandardCharsets.UTF_8)) {
            String line;
            while ((line = br.readLine()) != null) {
                JSONObject json = new JSONObject(line);
                map.put(json.getString("DOCNO"), json.getString("text_raw"));
            }
        } catch (Exception e) { e.printStackTrace(); }
        return map;
    }

    private float[] combineArrays(float bm25, float tfidf, float[] joint, float[] q, float[] d) {
        float[] combined = new float[2 + joint.length + q.length + d.length];
        combined[0] = bm25; combined[1] = tfidf;
        int offset = 2;
        System.arraycopy(joint, 0, combined, offset, joint.length); offset += joint.length;
        System.arraycopy(q, 0, combined, offset, q.length); offset += q.length;
        System.arraycopy(d, 0, combined, offset, d.length);
        return combined;
    }
}