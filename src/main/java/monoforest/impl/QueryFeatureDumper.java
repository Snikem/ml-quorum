package monoforest.impl;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Locale;

public class QueryFeatureDumper {

    // Пути к файлам (проверьте, чтобы они совпадали с вашими)
    private static final String QUERIES_FILE = "/Volumes/Ex_Volume/msmarcoProcces/input_data/dev/QuestionFields.jsonl";
    private static final String OUTPUT_FILE = "/Volumes/Ex_Volume/msmarcoProcces/query_features.tsv";

    public static void main(String[] args) {
        System.out.println("=== Starting Query Feature Generator ===");

        // Инициализируем менеджер факторов
        System.out.println("Initializing FactorManager...");
        FactorManager manager = new FactorManager();

        long startTime = System.currentTimeMillis();
        int processedCount = 0;

        // Открываем потоки для чтения и записи
        try (BufferedReader br = Files.newBufferedReader(Paths.get(QUERIES_FILE), StandardCharsets.UTF_8);
             BufferedWriter bw = Files.newBufferedWriter(Paths.get(OUTPUT_FILE), StandardCharsets.UTF_8)) {

            // Опционально: можно записать заголовок колонок в файл
            // bw.write("query_id\tfeatures\n");

            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                try {
                    // 1. Читаем и парсим JSON
                    JSONObject json = new JSONObject(line);
                    String queryId = json.optString("DOCNO");
                    String text = json.optString("text_raw");

                    if (!queryId.isEmpty() && !text.isEmpty()) {

                        // 2. Считаем ВСЕ факторы для запроса
                        float[] queryFactors = manager.extractQueryFactors(text);

                        // 3. Формируем вектор в виде строки "f1,f2,f3..."
                        StringBuilder vectorStr = new StringBuilder();
                        for (int i = 0; i < queryFactors.length; i++) {
                            // Строго Locale.US, чтобы были точки в дробях, а не запятые!
                            vectorStr.append(String.format(Locale.US, "%.4f", queryFactors[i]));

                            if (i < queryFactors.length - 1) {
                                vectorStr.append(","); // Разделитель внутри вектора
                            }
                        }

                        // 4. Записываем в файл в формате: ID [TAB] VECTOR
                        bw.write(queryId + "\t" + vectorStr.toString() + "\n");

                        processedCount++;
                        if (processedCount % 1000 == 0) {
                            System.out.print("\rProcessed queries: " + processedCount);
                        }
                    }
                } catch (Exception e) {
                    System.err.println("\nError parsing line: " + line);
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        }

        long endTime = System.currentTimeMillis();
        System.out.println("\n✅ Done! Processed " + processedCount + " queries in " + (endTime - startTime) + " ms.");
        System.out.println("Features saved to: " + OUTPUT_FILE);
    }
}
