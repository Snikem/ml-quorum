package monoforest.impl;

import monoforest.impl.joint.factors.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

public class TestBM25Standalone {

    public static void main(String[] args) {

        FactorManager fm = new FactorManager();
//        fm.printAllNames();
        // Укажите точный путь к одному из созданных файлов для теста
        // Например, тестируем тот самый файл с Estrogen receptor
        String filePath = "/Users/snikem/proga/FactorFactory/delete.txt";

        String query = "grammar terms";
        String title = "";
        StringBuilder bodyBuilder = new StringBuilder();

        try {
            // Читаем все строки из файла
            List<String> lines = Files.readAllLines(Paths.get(filePath));

            for (String line : lines) {
                bodyBuilder.append(line).append("\n");

            }
        } catch (IOException e) {
            System.out.println("Ошибка при чтении файла: " + e.getMessage());
            return;
        }

        String body = bodyBuilder.toString();
        fm.printAllNames();

        // --- ВЫВОД ПРОЧИТАННЫХ ДАННЫХ ДЛЯ ПРОВЕРКИ ---
        System.out.println("=== ПРОЧИТАНО ИЗ ФАЙЛА ===");
        System.out.println("Query: [" + query + "]");
        System.out.println("Body length: " + body.length() + " символов\n");


        FactorManager factorManager = new FactorManager();

        System.out.println("=== РЕЗУЛЬТАТ РАСЧЕТА ===");
        JointUnorderedWindow cl = new JointUnorderedWindow();
        cl.prepare();
        float[] s =  cl.calculateScore(query,title,body, "msmarco_doc_57_965596910");

        System.out.println(Arrays.toString(s));

    }
}