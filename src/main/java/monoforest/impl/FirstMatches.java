package monoforest.impl;

import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.search.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** First N matches in Lucene document order, without scores or a total-hit scan. */
public final class FirstMatches {
    private FirstMatches() {}

    public static Result search(IndexSearcher searcher, Query query, int limit) throws IOException {
        if (limit <= 0) throw new IllegalArgumentException("limit must be positive");
        List<Integer> docs = new ArrayList<>();
        boolean stopped = false;
        try {
            searcher.search(query, new SimpleCollector() {
                private int base;
                @Override protected void doSetNextReader(LeafReaderContext context) { base = context.docBase; }
                @Override public ScoreMode scoreMode() { return ScoreMode.COMPLETE_NO_SCORES; }
                @Override public void collect(int doc) {
                    docs.add(base + doc);
                    // CollectionTerminatedException only stops the current segment.
                    // This private signal stops the entire sequential search at N.
                    if (docs.size() == limit) throw new LimitReached();
                }
            });
        } catch (LimitReached done) { stopped = true; }
        return new Result(docs, stopped);
    }

    private static final class LimitReached extends RuntimeException {
        private LimitReached() { super(null, null, false, false); }
    }

    public static final class Result {
        public final List<Integer> docIds;
        /** True means at least N matches; we deliberately do not check for an (N+1)th match. */
        public final boolean limitReached;
        private Result(List<Integer> docs, boolean stopped) {
            docIds = Collections.unmodifiableList(new ArrayList<>(docs));
            limitReached = stopped;
        }
    }
}
