package monoforest.impl;

import java.io.IOException;
import java.util.List;

public class DocumentMarco {
    private String doc_id; // Изменено на String, так как в MS MARCO ID строковые
    private String title;
    private String body;
    private List<String> tokensBody;
    private List<String> tokensTitle;

    public DocumentMarco() {
    }

    public DocumentMarco(String doc_id, String title, String body) {
        this.doc_id = doc_id;
        this.title = title;
        this.body = body;
        MyTokenizer myTokenizer = new MyTokenizer();
        this.tokensBody = myTokenizer.tokenize(body);

    }

    /**
     * Заполняет поля текущего объекта, делая запрос в индекс по doc_id.
     *
     * @param indexManager Экземпляр менеджера индексов
     * @param id           Идентификатор документа для поиска
     */
    public void loadFromIndexById(LuceneIndexManager indexManager, String id) throws IOException {
        DocumentMarco foundDoc = indexManager.getDocumentById(id);
        if (foundDoc != null) {
            this.doc_id = foundDoc.getDoc_id();
            this.title = foundDoc.getTitle();
            this.body = foundDoc.getBody();
            MyTokenizer myTokenizer = new MyTokenizer();
            this.tokensBody = myTokenizer.tokenize(body);
        } else {
            System.err.println("Документ с ID " + id + " не найден в индексе.");
        }
    }
    public void loadFromIndexById(String id) throws IOException {
        LuceneIndexManager indexManager = new LuceneIndexManager();
        indexManager.init();
        DocumentMarco foundDoc = indexManager.getDocumentById(id);
        if (foundDoc != null) {
            this.doc_id = foundDoc.getDoc_id();
            this.title = foundDoc.getTitle();
            this.body = foundDoc.getBody();
            MyTokenizer myTokenizer = new MyTokenizer();
            this.tokensBody = myTokenizer.tokenize(body);
        } else {
            System.err.println("Документ с ID " + id + " не найден в индексе.");
        }
    }

    /**
     * Токенизирует тело документа (body) и сохраняет результат в список tokens.
     *
     * @param tokenizer Твой класс MyTokenizer
     */
    public void tokenizeBody(MyTokenizer tokenizer) {
        if (this.body != null && !this.body.isEmpty()) {
            this.tokensBody = tokenizer.tokenize(this.body);
        }
    }

    public void tokenizeTitle(MyTokenizer tokenizer) {
        this.tokensTitle = tokenizer.tokenize(this.title);
    }


    // Геттеры и сеттеры
    public String getDoc_id() { return doc_id; }
    public void setDoc_id(String doc_id) { this.doc_id = doc_id; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }

    public List<String> getTitleTokens() {
        return tokensTitle;
    }
    public List<String> getTokensBody() { return tokensBody; }
    public void setTokensBody(List<String> tokensBody) { this.tokensBody = tokensBody; }

    @Override
    public String toString() {
        return "DocumentMarco{doc_id='" + doc_id + "', title='" + title + "'}";
    }
}
