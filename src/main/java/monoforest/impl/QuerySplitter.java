package monoforest.impl;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;
import java.util.stream.Collectors;

public class QuerySplitter {

    // Исходная папка со сплитами
    private static final String SPLITS_DIR = "/Volumes/Ex_Volume/msmarcoProcces/itog";
    // Папка, куда будут сохраняться файлы с query_id
    private static final String OUTPUT_DIR = "/Volumes/Ex_Volume/msmarcoProcces/final2/";

    public static void main(String[] args) throws Exception {
        long startTime = System.currentTimeMillis();
        System.out.println("Начинаем обработку...");

        processFiles();

        long endTime = System.currentTimeMillis();
        System.out.println("Успешно завершено за: " + (endTime - startTime) / 1000 + " сек.");
    }

    private static void processFiles() throws Exception {
        Path inputPath = Paths.get(SPLITS_DIR);
        Path outputPath = Paths.get(OUTPUT_DIR);

        // Создаем целевую директорию, если ее нет
        if (!Files.exists(outputPath)) {
            Files.createDirectories(outputPath);
        }

        // Получаем список файлов и сортируем их (опционально, для порядка в логах)
        try (Stream<Path> paths = Files.list(inputPath)) {
            List<Path> files = paths
                    .filter(f -> f.getFileName().toString().startsWith("features_") && f.toString().endsWith(".jsonl"))
                    .sorted()
                    .collect(Collectors.toList());

            for (Path filePath : files) {
                System.out.println("Читаем файл: " + filePath.getFileName());
                processSingleSplitFile(filePath, outputPath);
            }
        }
    }

    private static void processSingleSplitFile(Path filePath, Path outputPath) throws Exception {
        // Мапа для хранения строк текущего файла: <query_id, список строк>
        Map<String, List<String>> buffer = new HashMap<>();

        try (BufferedReader reader = Files.newBufferedReader(filePath)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) continue;

                String queryId = fastExtractQueryId(line);

                // Если query_id не найден, выбрасываем исключение и роняем скрипт
                if (queryId == null) {
                    throw new IllegalArgumentException("Критическая ошибка: не найден query_id в строке файла "
                            + filePath.getFileName() + " -> " + line);
                }

                // Добавляем строку в список нужного query_id
                buffer.computeIfAbsent(queryId, k -> new ArrayList<>()).add(line);
            }
        }

        // После полного прочтения файла, записываем данные из мапы на диск
        flushBuffer(buffer, outputPath);
    }

    // Быстрый поиск значения query_id без накладных расходов на парсинг всего JSON
    private static String fastExtractQueryId(String jsonLine) {
        String key = "\"query_id\":\"";
        int start = jsonLine.indexOf(key);
        if (start == -1) return null; // Ключ не найден

        start += key.length();
        int end = jsonLine.indexOf("\"", start);
        if (end == -1) return null; // Закрывающая кавычка не найдена

        return jsonLine.substring(start, end);
    }

    private static void flushBuffer(Map<String, List<String>> buffer, Path outputPath) throws Exception {
        System.out.println("  -> Запись результатов на диск (" + buffer.size() + " уникальных query_id)...");

        for (Map.Entry<String, List<String>> entry : buffer.entrySet()) {
            String queryId = entry.getKey();
            List<String> lines = entry.getValue();

            Path queryFile = outputPath.resolve(queryId + ".json");

            // Files.write умеет напрямую принимать Iterable (наш List<String>)
            // CREATE - создает файл, если его нет; APPEND - дописывает в конец, если есть.
            Files.write(queryFile, lines, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
    }
}