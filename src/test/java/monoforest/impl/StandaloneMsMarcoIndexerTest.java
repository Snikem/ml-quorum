package monoforest.impl;

import monoforest.Bm25Search;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.store.FSDirectory;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.zip.GZIPOutputStream;
import static org.junit.Assert.*;

public class StandaloneMsMarcoIndexerTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    @Test public void buildsIndexWithPositionsFeaturesAndSearchableDocuments() throws Exception {
        Path input = temp.newFolder("input").toPath();
        Path index = temp.newFolder("index").toPath();
        try (GZIPOutputStream gzip = new GZIPOutputStream(Files.newOutputStream(input.resolve("docs.gz")))) {
            gzip.write(("{\"docid\":\"d1\",\"title\":\"Cat\",\"body\":\"cat dog cat\"}\n"
                    + "{\"docid\":\"d2\",\"title\":\"Bird\",\"body\":\"bird\"}\n").getBytes(StandardCharsets.UTF_8));
        }
        String previousInput = System.getProperty("monoforest.input");
        String previousIndex = System.getProperty("monoforest.index");
        try {
            System.setProperty("monoforest.input", input.toString());
            System.setProperty("monoforest.index", index.toString());
            StandaloneMsMarcoIndexer.main(new String[0]);
        } finally {
            if (previousInput == null) System.clearProperty("monoforest.input"); else System.setProperty("monoforest.input", previousInput);
            if (previousIndex == null) System.clearProperty("monoforest.index"); else System.setProperty("monoforest.index", previousIndex);
        }
        try (FSDirectory dir = FSDirectory.open(index); DirectoryReader reader = DirectoryReader.open(dir)) {
            assertEquals(2, reader.numDocs());
            assertEquals("cat dog cat", reader.document(0).get("text"));
            assertTrue(reader.leaves().get(0).reader().terms("text").hasPositions());
            assertNotNull(reader.leaves().get(0).reader().getPointValues("doc_word_count"));
        }
        try (Bm25Search search = new Bm25Search(index, "text", 1.2f, 0.75f, false)) {
            assertEquals(java.util.List.of("d1"), search.search("cat AND OR", 10));
            assertTrue(search.search("OR AND", 10).isEmpty());
            assertEquals(java.util.List.of("d2"), search.search("bird!", 1));
        }
    }
}
