package monoforest.impl;

import monoforest.impl.joint.factors.JointExactMatchCount;
import monoforest.impl.joint.factors.JointQueryTfSum;
import monoforest.impl.joint.factors.JointTitleMatchCount;
import org.json.JSONObject;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.zip.GZIPInputStream;

public class MemoryBasedFactorsEnricher {

    // === ПУТИ ===
    private static final String QUERIES_FILE = "/Volumes/Ex_Volume/msmarcoProcces/input_data/dev/QuestionFields.jsonl";
    private static final String SPLITS_DIR = "/Volumes/Ex_Volume/msmarcoProcces/bms/";
    private static final String DOCS_INPUT_DIR = "/Volumes/Ex_Volume/msmarco/msmarco_v2_doc/";
    private static final String ENRICHED_DIR = "/Volumes/Ex_Volume/msmarcoProcces/bms_enriched_jsonl/";

    // Легковесный класс для хранения текста документа в RAM
    static class DocData {
        final String title;
        final String fullText;

        DocData(String title, String fullText) {
            this.title = title;
            this.fullText = fullText;
        }
    }

    public static void main(String[] args) {
        System.out.println("=== Starting Memory-Based Candidate Enrichment (Load ALL Docs) ===");

        try {
            Files.createDirectories(Paths.get(ENRICHED_DIR));

            // 1. Загружаем запросы
            System.out.println("Loading queries into memory...");
            Map<String, String> queriesMap = loadQueries(QUERIES_FILE);

            // 2. Инициализируем факторы
            JointTitleMatchCount titleMatchFactor = new JointTitleMatchCount();
            JointQueryTfSum queryTfSumFactor = new JointQueryTfSum();
            JointExactMatchCount exactMatchFactor = new JointExactMatchCount();

            titleMatchFactor.prepare();
            queryTfSumFactor.prepare();
            exactMatchFactor.prepare();

            long globalStartTime = System.currentTimeMillis();
            long totalEnrichedGlobal = 0;

            // 3. Цикл по 60 чанкам
            for (int i = 0; i < 60; i++) {
                String splitFileName = String.format("split_%02d.jsonl", i);
                String docFileName = String.format("msmarco_doc_%02d.gz", i);
                String outFileName = String.format("enriched_%02d.jsonl", i);

                Path splitFile = Paths.get(SPLITS_DIR, splitFileName);
                File docFile = new File(DOCS_INPUT_DIR, docFileName);
                Path outFile = Paths.get(ENRICHED_DIR, outFileName);

                if (!Files.exists(splitFile) || !docFile.exists()) {
                    System.out.println("ASD");
                    continue;
                }

                System.out.println("\n--- Processing Chunk " + i + "/59 ---");
                long chunkStartTime = System.currentTimeMillis();

                // ШАГ А: Загружаем ВЕСЬ GZ архив в оперативную память
                System.out.println("Loading ALL documents from " + docFileName + " into RAM...");

                // Указываем начальный размер (примерно 500k-600k документов в файле), чтобы HashMap не тратил время на расширение
                Map<String, DocData> docMemoryMap = new HashMap<>(100_000);

                try (BufferedReader br = new BufferedReader(new InputStreamReader(
                        new GZIPInputStream(Files.newInputStream(docFile.toPath())), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        try {
                            JSONObject docJson = new JSONObject(line);
                            String docId = docJson.optString("docid");

                            if (docId != null && !docId.isEmpty()) {
                                String title = docJson.optString("title", "");
                                String body = docJson.optString("body", "");

                                // Сохраняем в память
                                docMemoryMap.put(docId, new DocData(title, body));
                            } else {
                                System.out.println("PIZDA");
                            }
                        } catch (Exception e) {
                            System.out.println("PIZDA2");
                        }
                    }
                }
                System.out.println("Loaded " + docMemoryMap.size() + " documents into memory.");

                int enrichedInChunk = 0;

                // ШАГ Б: Идем по сплиту кандидатов, берем тексты из памяти и обогащаем
                System.out.println("Enriching candidates from " + splitFileName + "...");
                try (BufferedReader br = Files.newBufferedReader(splitFile, StandardCharsets.UTF_8);
                     BufferedWriter writer = Files.newBufferedWriter(outFile, StandardCharsets.UTF_8)) {

                    String line;
                    long timeJsonParse = 0;
                    long timeMapLookup = 0;
                    long timeFactorCalc1 = 0;
                    long timeFactorCalc2 = 0;
                    long timeFactorCalc3 = 0;
                    long timeJsonWrite = 0;
                    while ((line = br.readLine()) != null) {
                        if (line.trim().isEmpty()) continue;

                        try {
                            long t0 = System.nanoTime();

                            JSONObject cand = new JSONObject(line);
                            String docId = cand.getString("doc_no");
                            String queryId = cand.getString("query_id");

                            long t1 = System.nanoTime();
                            timeJsonParse += (t1 - t0);

                            String queryText = queriesMap.get(queryId);
                            DocData docData = docMemoryMap.get(docId);

                            long t2 = System.nanoTime();
                            timeMapLookup += (t2 - t1);

                            if (queryText != null && docData != null) {
                                // Считаем факторы мгновенно из RAM
                                float tMatch = titleMatchFactor.calculateScore(queryText, docData.title, docData.fullText, docId)[0];
                                long t31 = System.nanoTime();
                                float qTfSum = queryTfSumFactor.calculateScore(queryText, docData.title, docData.fullText, docId)[0];
                                long t32 = System.nanoTime();
                                float exactMatch = exactMatchFactor.calculateScore(queryText, docData.title, docData.fullText, docId)[0];

                                long t33 = System.nanoTime();
                                timeFactorCalc1 += (t31 - t2);
                                timeFactorCalc2 += (t32 - t31);
                                timeFactorCalc3 += (t33 - t32);

                                cand.put("title_match", tMatch);
                                cand.put("query_tf_sum", qTfSum);
                                cand.put("exact_match", exactMatch);

                                // Важно: добавьте .toString(), иначе writer будет неявно конвертировать объект, что дольше
                                writer.write(cand.toString() + "\n");

                                enrichedInChunk++;
                                totalEnrichedGlobal++;

                                long t4 = System.nanoTime();
                                timeJsonWrite += (t4 - t33);
                            } else {
                                System.out.println("PIZDA$");
                            }
                            if (enrichedInChunk > 0 && enrichedInChunk % 100000 == 0) {
                                System.out.printf("\r[Профиль 100k] JSON Парсинг: %d ms | Поиск в RAM: %d ms | Расчет факторов1: %d ms | Расчет факторов2: %d ms | Расчет факторов3: %d ms | Запись: %d ms",
                                        timeJsonParse / 1_000_000,
                                        timeMapLookup / 1_000_000,
                                        timeFactorCalc1 / 1_000_000,
                                        timeFactorCalc2 / 1_000_000,
                                        timeFactorCalc3 / 1_000_000,
                                        timeJsonWrite / 1_000_000);

                                // Сбрасываем таймеры, чтобы следующий лог показал время только за новые 100k
                                timeJsonParse = 0;
                                timeMapLookup = 0;
                                timeFactorCalc1 = 0;
                                timeFactorCalc2 = 0;
                                timeFactorCalc3 = 0;
                                timeJsonWrite = 0;
                            }
                        } catch (Exception e) {
                            System.out.println("Pizda3");
                        }
                    }
                }

                // ШАГ В: Очищаем память перед следующим архивом (КРИТИЧЕСКИ ВАЖНО)
                docMemoryMap.clear();

                long chunkEndTime = System.currentTimeMillis();
                System.out.println("Chunk " + i + " done in " + (chunkEndTime - chunkStartTime) / 1000 + " sec. Enriched: " + enrichedInChunk);
            }

            System.out.println("\n✅ All 60 files processed successfully!");
            System.out.println("Total candidates enriched: " + totalEnrichedGlobal);
            System.out.println("Total time: " + (System.currentTimeMillis() - globalStartTime) / 1000 + " seconds.");

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static Map<String, String> loadQueries(String filePath) {
        Map<String, String> map = new HashMap<>();
        try (BufferedReader br = Files.newBufferedReader(Paths.get(filePath), StandardCharsets.UTF_8)) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                try {
                    JSONObject json = new JSONObject(line);
                    map.put(json.optString("DOCNO"), json.optString("text_raw"));
                } catch (Exception e) {}
            }
        } catch (IOException e) { e.printStackTrace(); }
        return map;
    }
}