package monoforest.util;

import org.junit.Test;
import static org.junit.Assert.*;

public class QueryTextUtilsTest {
    @Test public void preservesLegacyPunctuationAndBooleanCleanup() {
        assertNull(QueryTextUtils.removePunct(null));
        assertEquals("cat dog", QueryTextUtils.removePunct("cat!?dog"));
        assertEquals("cat dog and", QueryTextUtils.removeLuceneSpecialOps("cat AND dog OR NOT TO and"));
        assertEquals(" cat  dog", QueryTextUtils.removeLuceneSpecialOps(" cat  dog  "));
        assertEquals("cat dog", QueryTextUtils.removeLuceneSpecialOps("cat\tdog"));
    }
}
