package monoforest.impl;

import org.apache.lucene.analysis.core.WhitespaceAnalyzer;
import org.apache.lucene.document.*;
import org.apache.lucene.index.*;
import org.apache.lucene.search.*;
import org.apache.lucene.store.*;
import org.junit.Test;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class FirstMatchesTest {
    @Test public void stopsGloballyAcrossSegmentsWithoutScoringAndSkipsDeletedDocs() throws Exception {
        try (Directory directory = new ByteBuffersDirectory(); WhitespaceAnalyzer analyzer = new WhitespaceAnalyzer()) {
            try (IndexWriter writer = new IndexWriter(directory,
                    new IndexWriterConfig(analyzer).setMergePolicy(NoMergePolicy.INSTANCE))) {
                for (int i = 0; i < 9; i++) {
                    Document document = new Document();
                    document.add(new StringField("id", "d" + i, Field.Store.YES));
                    writer.addDocument(document);
                    if (i % 3 == 2) writer.commit();
                }
                writer.deleteDocuments(new Term("id", "d1"));
            }
            try (DirectoryReader reader = DirectoryReader.open(directory)) {
                IndexSearcher searcher = new IndexSearcher(reader);
                assertEquals(3, reader.leaves().size());
                AtomicInteger visits = new AtomicInteger();
                Query query = countingQuery(visits);
                FirstMatches.Result first = FirstMatches.search(searcher, query, 3);
                assertEquals(Arrays.asList(0, 2, 3), first.docIds);
                assertTrue(first.limitReached);
                assertEquals(4, visits.get()); // 3 live hits plus one deleted document, no later segment.
                FirstMatches.Result all = FirstMatches.search(searcher, new MatchAllDocsQuery(), 20);
                assertEquals(8, all.docIds.size());
                assertFalse(all.limitReached);
                FirstMatches.Result empty = FirstMatches.search(searcher, new MatchNoDocsQuery(), 2);
                assertTrue(empty.docIds.isEmpty());
                assertFalse(empty.limitReached);
                assertThrows(IllegalArgumentException.class, () -> FirstMatches.search(searcher, query, 0));
            }
        }
    }

    private Query countingQuery(AtomicInteger visits) {
        return new Query() {
            @Override public Weight createWeight(IndexSearcher searcher, ScoreMode mode, float boost) {
                assertEquals(ScoreMode.COMPLETE_NO_SCORES, mode);
                return new Weight(this) {
                    @Override public void extractTerms(Set<Term> terms) {}
                    @Override public boolean isCacheable(LeafReaderContext context) { return false; }
                    @Override public Explanation explain(LeafReaderContext context, int doc) {
                        throw new AssertionError("explain must not be called");
                    }
                    @Override public Scorer scorer(LeafReaderContext context) {
                        DocIdSetIterator iterator = new DocIdSetIterator() {
                            private final DocIdSetIterator delegate = DocIdSetIterator.all(context.reader().maxDoc());
                            @Override public int docID() { return delegate.docID(); }
                            @Override public long cost() { return delegate.cost(); }
                            @Override public int nextDoc() throws IOException {
                                int doc = delegate.nextDoc();
                                if (doc != NO_MORE_DOCS) visits.incrementAndGet();
                                return doc;
                            }
                            @Override public int advance(int target) throws IOException {
                                int doc = delegate.advance(target);
                                if (doc != NO_MORE_DOCS) visits.incrementAndGet();
                                return doc;
                            }
                        };
                        return new Scorer(this) {
                            @Override public DocIdSetIterator iterator() { return iterator; }
                            @Override public int docID() { return iterator.docID(); }
                            @Override public float score() { throw new AssertionError("score must not be called"); }
                            @Override public float getMaxScore(int upTo) { return Float.POSITIVE_INFINITY; }
                        };
                    }
                };
            }
            @Override public String toString(String field) { return "CountingQuery"; }
            @Override public boolean equals(Object other) { return this == other; }
            @Override public int hashCode() { return System.identityHashCode(this); }
        };
    }
}
