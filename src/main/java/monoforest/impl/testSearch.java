package monoforest.impl;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import monoforest.Bm25Search;
import monoforest.MonoforestSearch;
import java.io.BufferedReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Compare monomial retrieval and BM25 on MergedData.jsonl without FlexNeuART. */
public class testSearch {
    public static class MergedRecord {
        @JsonProperty("DOCNO") public String docNo;
        @JsonProperty("text_raw") public String textRaw;
        @JsonProperty("relevantIdDoc") public List<String> relevantIdDoc;
    }
    public static int testSearchMonoforest(String text, String queryId, String relevantId, int topK,
                                           MonoforestSearch search) throws Exception {
        int rank = 0;
        for (JsonNode hit : search.search(text, topK).path("candidates")) {
            if (hit.path("doc_id").asText().equals(relevantId)) return rank + 1;
            rank++;
        }
        return 3000;
    }
    public static int testSearchLucene(String text, String queryId, String relevantId, int topK,
                                       Bm25Search search) throws Exception {
        int position = search.search(text, topK).indexOf(relevantId);
        return position < 0 ? 3000 : position + 1;
    }
    public static void main(String[] args) throws Exception {
        Path queries = Path.of(System.getProperty("monoforest.queries",
                "/Volumes/Ex_Volume/msmarcoProcces/input_data/dev/MergedData.jsonl"));
        Path baselineIndex = Path.of(System.getProperty("monoforest.bm25Index",
                "/Volumes/Ex_Volume/msmarcoProcces/lucene_index"));
        Path config = Path.of(System.getProperty("monoforest.bm25Config",
                "/Volumes/Ex_Volume/msmarcoProcces/exper_desc.best/bm25_params.json"));
        ObjectMapper json = new ObjectMapper();
        JsonNode settings = Files.exists(config) ? json.readTree(config.toFile()) : json.createObjectNode();
        int topK = Integer.getInteger("monoforest.topK", 5000);
        int limit = Integer.getInteger("monoforest.queryLimit", 500);
        double threshold = Double.parseDouble(System.getProperty("monoforest.threshold", "0.0"));
        try (MonoforestSearch monoforest = new MonoforestSearch(AppConfig.getIndexDir(), AppConfig.getModelPath(),
                     threshold, AppConfig.getTextField(), AppConfig.getTitleField());
             Bm25Search bm25 = new Bm25Search(baselineIndex, settings.path("indexFieldName").asText("text"),
                     (float)settings.path("k1").asDouble(1.2), (float)settings.path("b").asDouble(0.75),
                     settings.path("exactMatch").asBoolean(false));
             BufferedReader input = Files.newBufferedReader(queries)) {
            String line;
            int count = 0;
            while ((line = input.readLine()) != null && count++ < limit) {
                if (line.trim().isEmpty()) continue;
                MergedRecord record = json.readValue(line, MergedRecord.class);
                for (String id : record.relevantIdDoc) {
                    int baselineRank = testSearchLucene(record.textRaw, record.docNo, id, topK, bm25);
                    int monomialRank = testSearchMonoforest(record.textRaw, record.docNo, id, topK, monoforest);
                    System.out.printf("query=%s doc=%s BM25=%d Monoforest=%d difference=%d%n",
                            record.docNo, id, baselineRank, monomialRank, monomialRank - baselineRank);
                }
            }
        }
    }
}
