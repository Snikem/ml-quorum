package monoforest.impl.features.queryFeatures.tests;

import monoforest.impl.features.queryFeatures.QueryFrequencyFamily;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.file.*;
import static org.junit.Assert.*;

public class QueryFrequencyResourceTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private String previous;
    @Before public void saveProperty() {
        previous = System.getProperty(QueryFrequencyFamily.DATA_PATH_PROPERTY);
        System.clearProperty(QueryFrequencyFamily.DATA_PATH_PROPERTY);
    }
    @After public void restoreProperty() {
        if (previous == null) System.clearProperty(QueryFrequencyFamily.DATA_PATH_PROPERTY);
        else System.setProperty(QueryFrequencyFamily.DATA_PATH_PROPERTY, previous);
    }
    @Test public void loadsBundledCorpusWithoutExternalDisk() throws Exception {
        QueryFrequencyFamily.validateDefaultSource();
        QueryFrequencyFamily family = new QueryFrequencyFamily();
        family.prepare();
        assertTrue(family.calculateFeatureByName(QueryFrequencyFamily.MAX_LOG_FREQUENCY, "the").value > 0);
        assertEquals(0f, family.calculateFeatureByName(QueryFrequencyFamily.MEAN_LOG_FREQUENCY, "").value, 0);
    }
    @Test public void explicitTsvOverridesBundledCountsWithSameSemantics() throws Exception {
        Path tsv = temp.newFile("queries.tsv").toPath();
        Files.writeString(tsv, "q1\tcat cat dog\nq2\tCAT\n");
        System.setProperty(QueryFrequencyFamily.DATA_PATH_PROPERTY, tsv.toString());
        QueryFrequencyFamily.validateDefaultSource();
        QueryFrequencyFamily family = new QueryFrequencyFamily();
        family.prepare();
        assertEquals((float)Math.log(2), family.calculateFeatureByName(QueryFrequencyFamily.MIN_LOG_FREQUENCY, "cat dog").value, 0);
        assertEquals((float)Math.log(3), family.calculateFeatureByName(QueryFrequencyFamily.MAX_LOG_FREQUENCY, "cat dog").value, 0);
        assertEquals((float)Math.log(2.5), family.calculateFeatureByName(QueryFrequencyFamily.MEAN_LOG_FREQUENCY, "cat dog").value, 0);
    }
    @Test public void missingOverrideDoesNotSilentlyFallBackToAnotherCorpus() throws Exception {
        System.setProperty(QueryFrequencyFamily.DATA_PATH_PROPERTY, temp.getRoot().toPath().resolve("missing.tsv").toString());
        IOException error = assertThrows(IOException.class, QueryFrequencyFamily::validateDefaultSource);
        assertTrue(error.getMessage().contains("monoforest.queryFrequencyTsv"));
        assertThrows(UncheckedIOException.class, () -> new QueryFrequencyFamily().prepare());
    }
}
