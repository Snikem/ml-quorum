package monoforest.impl;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.core.WhitespaceAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

public class MyTokenizer {

    private final Analyzer analyzer;

    public MyTokenizer() {
        // Инициализируем тем же анализатором, что и в процессе индексации
        this.analyzer = new WhitespaceAnalyzer();
    }

    // Опциональный конструктор, если захочется прокинуть другой анализатор снаружи
    public MyTokenizer(Analyzer analyzer) {
        this.analyzer = analyzer;
    }

    /**
     * Разбивает переданную строку на токены с помощью Lucene Analyzer.
     *
     * @param text Исходный текст
     * @return Список токенов (строк)
     */
    public List<String> tokenize(String text) {
        List<String> result = new ArrayList<>();

        if (text == null || text.trim().isEmpty()) {
            return result;
        }


        try (TokenStream tokenStream = analyzer.tokenStream("text", new StringReader(text))) {

            CharTermAttribute charTermAttribute = tokenStream.addAttribute(CharTermAttribute.class);

            tokenStream.reset();
            while (tokenStream.incrementToken()) {
                result.add(charTermAttribute.toString());
            }

            // Корректно завершаем работу потока
            tokenStream.end();

        } catch (IOException e) {
            System.err.println("Ошибка при токенизации строки: " + e.getMessage());
            e.printStackTrace();
        }

        return result;
    }

    // Пример использования
    public static void main(String[] args) {
        MyTokenizer tokenizer = new MyTokenizer();
        String sampleText = "Here is some text, including punctuation! And numbers: 12345.";

        List<String> tokens = tokenizer.tokenize(sampleText);

        System.out.println("Оригинал: " + sampleText);
        System.out.println("Токены: " + tokens);
    }
}