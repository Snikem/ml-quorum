package monoforest.impl;

import monoforest.impl.document.factors.*;
import monoforest.impl.document.DocumentFactor;
import monoforest.impl.joint.JointFactor;
import monoforest.impl.joint.factors.*;
import monoforest.impl.query.QueryFactor;
import monoforest.impl.query.factors.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;


public class FactorManager {

    // Три отдельных списка
    private List<QueryFactor> queryFactors = new ArrayList<>();
    private List<DocumentFactor> docFactors = new ArrayList<>();
    private List<JointFactor> jointFactors = new ArrayList<>();

    // === ПРОФАЙЛЕР: Статические переменные для накопления статистики ===
    private static final java.util.Map<String, Long> factorTotalTimeNs = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.atomic.AtomicInteger callsCount = new java.util.concurrent.atomic.AtomicInteger(0);
    private static final int REPORT_INTERVAL = 1000; // Выводить отчет каждые 100 документов

    private int totalFeatureCount = 0;

    public FactorManager() {
        // === РЕГИСТРАЦИЯ ФАКТОРОВ ===

        addQueryFactor(new QueryQuantityWords());
        addDocFactor(new DocumentQuantityWords());

        addQueryFactor(new QueryQuantityVowels());
        addDocFactor(new DocumentQuantityVowels());

        addQueryFactor(new QueryQuantitySpecialCharacters());
        addDocFactor(new DocumentQuantitySpecialCharacters());

        addDocFactor(new DocumentQuantityParagraphs());

        addQueryFactor(new QueryQuantityNumbers());
        addDocFactor(new DocumentQuantityNumbers());

        addQueryFactor(new QueryQuantityNouns());

        addDocFactor(new DocumentQuantityNonDictionaryWords());
        addQueryFactor(new QueryQuantityNonDictionaryWords());

        addDocFactor(new DocumentQuantityKByte());

        addQueryFactor(new QueryQuantityConsonants());

        addDocFactor(new DocumentNonDictionaryRatio());

        addDocFactor(new DocumentHasUrls());

        addQueryFactor(new QueryFrequencyWords());

        addJointFactor(new JointRankBM25());

        addJointFactor(new JointBM25AndTFIDF());

        addJointFactor(new JointTitleMatchCount());

        addJointFactor(new JointQueryTfSum());

        addJointFactor(new JointExactMatchCount());

        //addJointFactor(new JointBigramTrigram());

        addJointFactor(new JointUnorderedWindow());


    }

    public int getQueryFactorCount() {
        return queryFactors.stream().mapToInt(QueryFactor::getFeatureQty).sum();
    }

    public int getDocFactorCount() {
        return docFactors.stream().mapToInt(DocumentFactor::getFeatureQty).sum();
    }

    public int getJointFactorCount() {
        return jointFactors.stream().mapToInt(JointFactor::getFeatureQty).sum();
    }

    public float[] extractDocFactors(String title, String document, String docId) {
        float[] features = new float[getDocFactorCount()];
        int currentIdx = 0;

        for (DocumentFactor f : docFactors) {
            float[] res = f.calculateScore(title, document, docId);
            System.arraycopy(res, 0, features, currentIdx, res.length);
            currentIdx += res.length;
        }
        return features;
    }

    public float[] extractQueryFactors(String queryText) {
        float[] features = new float[getQueryFactorCount()];
        int currentIdx = 0;

        for (QueryFactor f : queryFactors) {
            float[] res = f.calculateScore(queryText);
            System.arraycopy(res, 0, features, currentIdx, res.length);
            currentIdx += res.length;
        }
        return features;
    }

    public float[] extractJointFactors(String queryText, String title, String document, String docId) {
        float[] features = new float[getJointFactorCount()];
        int currentIdx = 0;

        for (JointFactor f : jointFactors) {
            float[] res = f.calculateScore(queryText, title, document, docId);
            System.arraycopy(res, 0, features, currentIdx, res.length);
            currentIdx += res.length;
        }
        return features;
    }

    public ArrayList<float[]> extractJointFactorsByQueries(ArrayList<String> queries, String title, String document, String docId) {
        int numQueries = queries.size();
        int totalJointFeatures = getJointFactorCount();

        // 1. Сразу выделяем память
        ArrayList<float[]> allFeatures = new ArrayList<>(numQueries);
        for (int i = 0; i < numQueries; i++) {
            allFeatures.add(new float[totalJointFeatures]);
        }

        int currentIdx = 0;

        // 2. Считаем факторы
        for (JointFactor f : jointFactors) {
            long startTime = System.nanoTime(); // ⏱️ СТАРТ

            ArrayList<float[]> batchRes = f.calculateForQueries(queries, title, document, docId);

            long duration = System.nanoTime() - startTime; // ⏱️ СТОП
            factorTotalTimeNs.merge(f.getName(), duration, Long::sum);

            // Копируем
            if (batchRes.size() == numQueries) {
                int dim = batchRes.get(0).length;
                for (int i = 0; i < numQueries; i++) {
                    System.arraycopy(batchRes.get(i), 0, allFeatures.get(i), currentIdx, dim);
                }
                currentIdx += dim;
            }


        }
        int currentCalls = callsCount.incrementAndGet();
        if (currentCalls % REPORT_INTERVAL == 0) {
            printFactorPerformanceReport(currentCalls);
        }
        return allFeatures;
    }

    private void printFactorPerformanceReport(int totalDocsProcessed) {
        System.out.println("\n=== ⏱️ JOINT FACTOR PERFORMANCE REPORT (Docs processed: " + totalDocsProcessed + ") ===");
        System.out.printf("%-35s | %-15s | %-15s | %s%n", "Factor Name", "Total Time (s)", "Avg per Doc (ms)", "% of Work");
        System.out.println("--------------------------------------------------------------------------------------");

        long grandTotalNs = factorTotalTimeNs.values().stream().mapToLong(time -> time).sum();

        // Сортируем факторы по времени (от самых медленных к быстрым)
        factorTotalTimeNs.entrySet().stream()
                .sorted((e1, e2) -> e2.getValue().compareTo(e1.getValue())) // Сортировка убыванию
                .forEach(entry -> {
                    String name = entry.getKey();
                    long totalNs = entry.getValue();

                    double totalSec = totalNs / 1_000_000_000.0;
                    double avgMs = (totalNs / (double) totalDocsProcessed) / 1_000_000.0;
                    double percent = (grandTotalNs > 0) ? (100.0 * totalNs / grandTotalNs) : 0;

                    System.out.printf("%-35s | %-15.4f | %-15.4f | %.1f%%%n",
                            name, totalSec, avgMs, percent);
                });

        System.out.println("======================================================================================\n");
    }

    public ArrayList<float[]> mergeFactorsBatch(ArrayList<float[]> jointList, ArrayList<float[]> queryList, float[] docFeatures) {
        int numQueries = jointList.size();
        ArrayList<float[]> result = new ArrayList<>(numQueries);

        // Предвычисляем общую длину вектора
        int totalLen = jointList.get(0).length + queryList.get(0).length + docFeatures.length;

        for (int i = 0; i < numQueries; i++) {
            float[] joint = jointList.get(i);
            float[] query = queryList.get(i);

            float[] all = new float[totalLen];

            // Быстрое копирование блоков памяти
            System.arraycopy(joint, 0, all, 0, joint.length);
            System.arraycopy(query, 0, all, joint.length, query.length);
            System.arraycopy(docFeatures, 0, all, joint.length + query.length, docFeatures.length);

            result.add(all);
        }
        return result;
    }

    public float[] mergeFactors(float[] joint, float[] query, float[] doc) {
        float[] all = new float[joint.length + query.length + doc.length];
        System.arraycopy(joint, 0, all, 0, joint.length);
        System.arraycopy(query, 0, all, joint.length, query.length);
        System.arraycopy(doc, 0, all, joint.length + query.length, doc.length);
        return all;
    }

    // Методы добавления
    private void addQueryFactor(QueryFactor f) {
        queryFactors.add(f);
        f.prepare();
        totalFeatureCount += f.getFeatureQty();
    }

    private void addDocFactor(DocumentFactor f) {
        docFactors.add(f);
        f.prepare();
        totalFeatureCount += f.getFeatureQty();
    }

    private void addJointFactor(JointFactor f) {
        jointFactors.add(f);
        f.prepare();
        totalFeatureCount += f.getFeatureQty();
    }

    public int getTotalFeatureCount() {
        return totalFeatureCount;
    }


    public void printAllNames() {
        for (JointFactor f : jointFactors) {
            System.out.println("Name: " + f.getName() + "     " + f.getDescription() + "   количество возвразаемых числе " + f.getFeatureQty() );
        }

        for (QueryFactor f : queryFactors) {
            System.out.println("Name: " + f.getName() + "     " + f.getDescription() + "   количество возвразаемых числе " + f.getFeatureQty() );
        }

        for (DocumentFactor f : docFactors) {
            System.out.println("Name: " + f.getName() + "     " + f.getDescription() + "   количество возвразаемых числе " + f.getFeatureQty() );
        }
    }
    // Главный метод расчета
    public float[] extractAll(String queryText, String title, String document, String doc_id) {
        float[] allFeatures = new float[totalFeatureCount];
        int currentIdx = 0;

        for (JointFactor f : jointFactors) {
            float[] res = f.calculateScore(queryText, title, document, doc_id);
            System.arraycopy(res, 0, allFeatures, currentIdx, res.length);
            currentIdx += res.length;
        }

        for (QueryFactor f : queryFactors) {
            float[] res = f.calculateScore(queryText);
            System.arraycopy(res, 0, allFeatures, currentIdx, res.length);
            currentIdx += res.length;
        }
        //TODO не понятно что делать с массивом состоящим только из float
        //TODO: надо делать индексацию и брать данные из нее
        for (DocumentFactor f : docFactors) {
            float[] res = f.calculateScore(title, document, doc_id);
            System.arraycopy(res, 0, allFeatures, currentIdx, res.length);
            currentIdx += res.length;
        }


        return allFeatures;
    }


    public ArrayList<String> getVectorsName() {
        ArrayList<String> names = new ArrayList<>();

        // 1. Joint Factors (Совместные факторы)
        for (JointFactor f : jointFactors) {
            int qty = f.getFeatureQty();
            String baseName = f.getName();

            if (qty == 1) {
                names.add(baseName);
            } else {
                // Если фактор возвращает массив, добавляем индекс
                for (int i = 0; i < qty; i++) {
                    names.add(baseName + "_" + i);
                }
            }
        }

        // 2. Query Factors (Факторы запроса)
        for (QueryFactor f : queryFactors) {
            int qty = f.getFeatureQty();
            String baseName = f.getName();

            if (qty == 1) {
                names.add(baseName);
            } else {
                for (int i = 0; i < qty; i++) {
                    names.add(baseName + "_" + i);
                }
            }
        }

        // 3. Document Factors (Факторы документа)
        for (DocumentFactor f : docFactors) {
            int qty = f.getFeatureQty();
            String baseName = f.getName();

            if (qty == 1) {
                names.add(baseName);
            } else {
                for (int i = 0; i < qty; i++) {
                    names.add(baseName + "_" + i);
                }
            }
        }

        return names;
    }
}