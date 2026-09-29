package monoforest.impl;


import org.json.JSONObject;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;

public class ParallelFeatureDumper {

    // === КОНСТАНТЫ ===
    private static final String DOCS_INPUT_DIR = "/Volumes/Ex_Volume/msmarco/msmarco_v2_doc/";
    private static final String QUERIES_FILE = "/Volumes/Ex_Volume/msmarcoProcces/input_data/dev/QuestionFields.jsonl";
    private static final String OUTPUT_BINARY_FILE = "/Volumes/Ex_Volume/msmarcoProcces/full_features.bin";

    // Настройки параллельности
    private static final int BATCH_SIZE = 50; // Документов в одной задаче
    private static final int MAX_PENDING_BATCHES = 40; // Очередь задач (чтобы не переполнить RAM)

    // === ВСПОМОГАТЕЛЬНЫЕ КЛАССЫ ===
    static class QueryInfo {
        String id;
        String text;

        public QueryInfo(String id, String text, float[] factors) {
            this.id = id;
            this.text = text;
        }
    }

    // Результат обработки одного батча документов
    static class BatchResult {
        List<DocumentResult> documents = new ArrayList<>();
    }

    static class DocumentResult {
        String queryId;
        String docId;
        float[] JointFactors;
    }

    // === MAIN ===
    public static void main(String[] args) {
        System.out.println("=== Starting PARALLEL Feature Generator ===");

        // 1. Инициализация менеджера (Lucene Index грузится тут)
        System.out.println("Initializing FactorManager...");
        FactorManager manager = new FactorManager();

        // 2. Загрузка запросов
        System.out.println("Loading queries from: " + QUERIES_FILE);
        List<QueryInfo> queries = loadQueriesFromJson(QUERIES_FILE, manager);
        System.out.println("Loaded " + queries.size() + " queries.");

        if (queries.isEmpty()) {
            System.err.println("Error: No queries loaded. Exiting.");
            return;
        }


        // 3. Запуск пула потоков
        int cores = Runtime.getRuntime().availableProcessors();
        ExecutorService executor = Executors.newFixedThreadPool(cores);
        System.out.println("🔥 Processing on " + cores + " cores.");

        // Очередь Future, чтобы сохранять порядок записи!
        LinkedList<Future<BatchResult>> pendingTasks = new LinkedList<>();

        long totalPairsProcessed = 0;
        long startTime = System.currentTimeMillis();

        try (DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(Paths.get(OUTPUT_BINARY_FILE)), 65536))) {

            // !!! ВАЖНО !!! Резервируем место под заголовок (4 байта)
            // В старом коде этого не было, и файл ломался при патчинге
            dos.writeInt(0);

            // Цикл по файлам
            for (int i = 0; i < 60; i++) {
                String fileName = String.format("msmarco_doc_%02d.gz", i);
                File file = new File(DOCS_INPUT_DIR, fileName);

                if (!file.exists()) {
                    System.out.println("Skipping missing file: " + fileName);
                    continue;
                }

                System.out.println("Processing file (" + (i + 1) + "/60): " + fileName);

                try (BufferedReader br = new BufferedReader(new InputStreamReader(
                        new GZIPInputStream(Files.newInputStream(file.toPath())), StandardCharsets.UTF_8))) {

                    String line;
                    List<String> currentBatchLines = new ArrayList<>(BATCH_SIZE);

                    while ((line = br.readLine()) != null) {
                        currentBatchLines.add(line);

                        // Если набрали батч - отправляем в работу
                        if (currentBatchLines.size() >= BATCH_SIZE) {
                            submitBatch(executor, manager, currentBatchLines, queries, pendingTasks);
                            currentBatchLines = new ArrayList<>(BATCH_SIZE);
                        }

                        // Если очередь переполнилась - ждем и пишем на диск (Backpressure)
                        while (pendingTasks.size() >= MAX_PENDING_BATCHES) {
                            totalPairsProcessed += writeOldestBatch(dos, pendingTasks, queries.stream().map(q -> q.id).collect(Collectors.toCollection(ArrayList::new)));
                            printProgress(totalPairsProcessed, startTime);
                        }
                    }

                    // Отправляем остатки файла
                    if (!currentBatchLines.isEmpty()) {
                        submitBatch(executor, manager, currentBatchLines, queries, pendingTasks);
                    }
                }
            }

            // Дописываем всё, что осталось в очереди после всех файлов
            System.out.println("⏳ Finishing remaining tasks...");
            while (!pendingTasks.isEmpty()) {
                totalPairsProcessed += writeOldestBatch(dos, pendingTasks, queries.stream().map(q -> q.id).collect(Collectors.toCollection(ArrayList::new)));
                printProgress(totalPairsProcessed, startTime);
            }

            dos.flush();

        } catch (Exception e) {
            e.printStackTrace();
            return;
        } finally {
            executor.shutdown();
        }

        // 4. Патчим заголовок (теперь безопасно)
        System.out.println("\nPatching header with total count: " + totalPairsProcessed);
        try (RandomAccessFile raf = new RandomAccessFile(OUTPUT_BINARY_FILE, "rw")) {
            raf.seek(0);
            raf.writeInt((int)totalPairsProcessed); // Пишем реальное число пар
            // Внимание: если пар > 2 млрд, int переполнится. Надеюсь, у вас < 2 млрд.
        } catch (IOException e) {
            e.printStackTrace();
        }

        long endTime = System.currentTimeMillis();
        System.out.println("✅ DONE in " + (endTime - startTime) / 1000 + " sec.");
    }

    // === WORKER LOGIC (В отдельном потоке) ===
    private static void submitBatch(ExecutorService executor,
                                    FactorManager manager,
                                    List<String> lines,
                                    List<QueryInfo> queries,
                                    LinkedList<Future<BatchResult>> pendingTasks) {

        // Копируем список строк, так как original очищается в main
        final List<String> batchLines = new ArrayList<>(lines);

        Callable<BatchResult> task = () -> {
            BatchResult result = new BatchResult();

            // Используем ThreadLocal FactorManager, если он не потокобезопасен (но IndexSearcher безопасен)
            // Предполагаем, что manager thread-safe (обычно да, если он только читает индекс)

            for (String line : batchLines) {
                try {
                    JSONObject docJson = new JSONObject(line);
                    String docId = docJson.getString("docid");
                    String title = docJson.optString("title", "");
                    String headings = docJson.optString("headings", "");
                    String body = docJson.optString("body", "");
                    ArrayList<float[]> jointVecs = manager.extractJointFactorsByQueries(queries.stream().map(q -> q.text).collect(Collectors.toCollection(ArrayList::new)), title, body, docId);

                    int queryIndex = 0;
                    for( float[] vec : jointVecs) {
                        int countNotZero = vec.length;
                        for(float elem : vec) {
                            if (Math.abs(elem) < 1e-4f) {
                                countNotZero--;
                            }
                        }
                        if (countNotZero>-1) {
                            DocumentResult docRes = new DocumentResult();
                            docRes.docId = docId;
                            docRes.queryId = String.valueOf(queryIndex);
                            docRes.JointFactors = vec;
                            result.documents.add(docRes);
                        }

                        queryIndex++;


                    }


                } catch (Exception e) {
                    // Логируем ошибку парсинга, но не роняем поток
                    // e.printStackTrace();
                }
            }
            result.documents.sort((d1, d2) -> Float.compare(d2.JointFactors[0], d1.JointFactors[0]));
            //System.out.println(Arrays.toString(result.documents.get(0).JointFactors));
            return result;
        };

        pendingTasks.add(executor.submit(task));
    }

    // === WRITER LOGIC (В главном потоке) ===
    private static long writeOldestBatch(DataOutputStream dos,
                                         LinkedList<Future<BatchResult>> pendingTasks,
                                         ArrayList<String> queryIds) throws Exception {

        Future<BatchResult> future = pendingTasks.removeFirst();
        BatchResult batch = future.get(); // Блокируемся, пока worker не закончит

        long pairsWritten = 0;

        for (DocumentResult doc : batch.documents) {
            // У документа может быть N векторов (по числу запросов)
            // Проверяем на всякий случай


            float[] vec = doc.JointFactors;

            // Формат записи (как в вашем старом коде):
            dos.writeUTF(doc.queryId); // Query ID
            dos.writeUTF(doc.docId);       // Doc ID

            for (float v : vec) {
                dos.writeFloat(v);
            }
            //System.out.println(Arrays.toString(vec));
            pairsWritten++;

        }
        return pairsWritten;
    }

    // === UTILS ===

    private static List<QueryInfo> loadQueriesFromJson(String filePath, FactorManager manager) {
        List<QueryInfo> list = new ArrayList<>();
        // Не создаем новый manager, используем переданный!
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

    private static void printProgress(long totalPairs, long startTime) {

        if (totalPairs % 45520000 == 0) { // Печатаем реже, т.к. пар очень много
            long elapsed = (System.currentTimeMillis() - startTime) / 1000;
            double speed = elapsed > 0 ? totalPairs / (double) elapsed : 0;
            System.out.printf("\rWritten Pairs: %d | Time: %ds | Speed: %.0f pairs/sec",
                    totalPairs / 4552, elapsed, speed);
        }
    }
}