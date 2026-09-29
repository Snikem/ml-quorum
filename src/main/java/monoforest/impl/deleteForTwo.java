package monoforest.impl;


import monoforest.impl.joint.factors.JointSmarterBigramTrigram;
import monoforest.impl.joint.factors.JointUnorderedWindow;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.search.similarities.ClassicSimilarity;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.document.Document;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

public class deleteForTwo {
    private static final String INDEX_PATH = "/Volumes/Ex_Volume/msmarcoProcces/lucene_index";
    private static final String TEXT_FIELD = "text";
    private static final String ID_FIELD = "DOCNO";

    private final IndexReader reader;
    private final IndexSearcher bm25Searcher;
    private final IndexSearcher tfidfSearcher;
    private final Analyzer analyzer;
    private final QueryParser parser;

    public deleteForTwo() throws Exception {
        FSDirectory dir = FSDirectory.open(Paths.get(INDEX_PATH));
        this.reader = DirectoryReader.open(dir);

        // Поисковик для BM25
        this.bm25Searcher = new IndexSearcher(reader);
        this.bm25Searcher.setSimilarity(new BM25Similarity(1.2f, 0.75f));

        // Поисковик для TF-IDF
        this.tfidfSearcher = new IndexSearcher(reader);
        this.tfidfSearcher.setSimilarity(new ClassicSimilarity());

        this.analyzer = new StandardAnalyzer();
        this.parser = new QueryParser(TEXT_FIELD, analyzer);
    }

    public static void main(String[] args) throws Exception {
        String dir = "/Volumes/Ex_Volume/DatasetStream/totalSet/";
        Path inputPath1 = Paths.get(dir+"features_part1.tsv");
        Path inputPath2 = Paths.get(dir+"features_part2.tsv");
        Path inputPath3 = Paths.get(dir+"features_part3.tsv");
        deleteForTwo d = new deleteForTwo();
        d.calculateFile(inputPath1,dir);
        d.calculateFile(inputPath2,dir);
        d.calculateFile(inputPath3,dir);
    }

    public void calculateFile(Path inputFile, String dir){
            Path outputFile = Paths.get(inputFile.toString().substring(0, inputFile.toString().length() - 4) + "_end.tsv");
            JointUnorderedWindow f1 = new JointUnorderedWindow();
            f1.prepare();

            JointSmarterBigramTrigram f2 = new JointSmarterBigramTrigram();
            f2.prepare();

        try (BufferedReader reader = Files.newBufferedReader(inputFile, StandardCharsets.UTF_8);
                 BufferedWriter writer = Files.newBufferedWriter(outputFile, StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.trim().isEmpty()) continue;
                    String[] columns = line.split("\t");

                    String query = columns[1];
                    String docId = columns[2];

                    Term term = new Term(ID_FIELD, docId);
                    Query idQuery = new TermQuery(term);

                    try {
                        // 2. Ищем документ. Нам нужен максимум 1 результат, т.к. DOCNO уникален
                        TopDocs hits = bm25Searcher.search(idQuery, 1);

                        // 3. Проверяем, найден ли документ
                        // Внимание: если у вас старая версия Lucene (до 8.x), используйте просто hits.totalHits == 0
                        if (hits.totalHits.value == 0) {
                            System.err.println("Критическая ошибка: Документ с DOCNO '" + docId + "' не найден в индексе!");
                            System.exit(1); // Завершаем программу с кодом ошибки
                        }

                        // 4. Достаем внутренний ID документа в Lucene
                        int luceneDocId = hits.scoreDocs[0].doc;

                        // 5. Получаем сам документ из индекса
                        Document doc = this.reader.document(luceneDocId);

                        // 6. Вытаскиваем текст документа
                        String text = doc.get(TEXT_FIELD);

                        float[] score1 = f1.calculateScore(query, "", text, docId);
                        float[] score2 = f2.calculateScore(query, "", text, docId);
                        float[] combined = new float[8];
                        combined[0] = score1[0];
                        combined[1] = score1[1];
                        combined[2] = score1[2];
                        combined[3] = score1[3];
                        combined[4] = score2[0];
                        combined[5] = score2[1];
                        combined[6] = score2[2];
                        combined[7] = score2[3];
                        StringBuilder sb = new StringBuilder(line);
                        for (float val : combined) {
                            sb.append("\t").append(val);
                        }

                        // 5. Записываем в файл
                        writer.write(sb.toString());
                        writer.newLine();

                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }

            } catch (IOException e) {
                throw new RuntimeException(e);
            }
    }
}