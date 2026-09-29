package monoforest.impl;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public class DatasetOrchestrator2 {

    // === НАСТРОЙКИ ПУТЕЙ ===
    private static final String OUTPUT_FILE = "/Volumes/Ex_Volume/DatasetStream/totalSet/";

    // ВАЖНО: Укажите номер колонки (разделенной табуляцией), где лежит ID запроса. Нумерация с 0.
    private static final int QUERY_ID_INDEX = 1;

    public static void main(String[] args) {

        // Список уже существующих файлов с фичами
        List<String> inputFiles = Arrays.asList(
                OUTPUT_FILE + "features_part1.tsv",
                OUTPUT_FILE + "features_part2.tsv",
                OUTPUT_FILE + "features_part3.tsv"
        );

        String trainFile = OUTPUT_FILE + "train.tsv";
        String validFile = OUTPUT_FILE + "valid.tsv";

        try {
            combineGroupByQueryAndSplit(inputFiles, trainFile, validFile);
        } catch (IOException e) {
            System.err.println("Ошибка при сборке датасета:");
            e.printStackTrace();
        }
    }

    private static void combineGroupByQueryAndSplit(List<String> inputFiles, String trainOut, String validOut) throws IOException {
        // Структура для группировки: QueryID -> Список строк (кандидатов) для этого запроса
        Map<String, List<String>> queryGroups = new HashMap<>();

        System.out.println("=== Шаг 1: Чтение и группировка данных по Query ID ===");
        int totalLines = 0;

        // 1. Читаем файлы потоково и раскладываем по корзинам (запросам)
        for (String fileName : inputFiles) {
            Path path = Paths.get(fileName);
            if (!Files.exists(path)) {
                System.out.println("Файл не найден, пропускаем: " + fileName);
                continue;
            }
            System.out.println("Чтение файла: " + fileName);
            int counted = 0;
            try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.trim().isEmpty()) continue;

                    String[] columns = line.split("\t");
                    if (columns.length <= QUERY_ID_INDEX) continue; // Защита от битых/коротких строк

                    String queryId = columns[QUERY_ID_INDEX];
                    // Добавляем строку в список кандидатов для данного запроса
                    queryGroups.computeIfAbsent(queryId, k -> new ArrayList<>()).add(line);
                    if(fileName.equals(OUTPUT_FILE + "features_part3.tsv") && counted > 100000) {
                        break;
                    }
                    counted++;
                    totalLines++;
                }
            }
        }

        System.out.println("Всего прочитано строк: " + totalLines);
        System.out.println("Найдено уникальных запросов: " + queryGroups.size());

        if (queryGroups.isEmpty()) {
            System.out.println("Нет данных для обработки.");
            return;
        }

        System.out.println("\n=== Шаг 2: Перемешивание запросов и сплит ===");

        // 2. Достаем все уникальные ID запросов
        List<String> allQueryIds = new ArrayList<>(queryGroups.keySet());

        // ФИКСИРОВАННЫЙ SEED (42) гарантирует, что сплит всегда будет одинаковым при перезапусках
        Collections.shuffle(allQueryIds, new Random(42));

        // 3. Делим запросы на 95% Train и 5% Valid
        int splitIndex = (int) (allQueryIds.size() * 0.95);
        List<String> trainQueryIds = allQueryIds.subList(0, splitIndex);
        List<String> validQueryIds = allQueryIds.subList(splitIndex, allQueryIds.size());

        System.out.println("Запросов в Train (95%): " + trainQueryIds.size());
        System.out.println("Запросов в Valid (5%):  " + validQueryIds.size());

        System.out.println("\n=== Шаг 3: Запись итоговых файлов ===");

        // 4. Записываем данные в файлы (все документы одного запроса идут строго подряд!)
        writeGroupsToFile(trainQueryIds, queryGroups, trainOut);
        writeGroupsToFile(validQueryIds, queryGroups, validOut);

        System.out.println("\nУспех! Данные сгруппированы и разделены без утечек.");
    }

    private static void writeGroupsToFile(List<String> queryIds, Map<String, List<String>> queryGroups, String outputFile) throws IOException {
        System.out.println("Сохранение в: " + outputFile);
        try (BufferedWriter writer = Files.newBufferedWriter(Paths.get(outputFile), StandardCharsets.UTF_8)) {
            for (String qId : queryIds) {
                List<String> lines = queryGroups.get(qId);
                for (String line : lines) {
                    writer.write(line);
                    writer.newLine();
                }
            }
        }
    }
}
