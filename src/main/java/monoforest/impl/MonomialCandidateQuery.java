package monoforest.impl;

import org.apache.lucene.index.*;
import org.apache.lucene.search.*;
import java.io.IOException;
import java.util.*;

/** Exact signed linear score over Boolean monomial conditions, with a strict cutoff.
 * Lucene receives a nonnegative shifted score; the cutoff is applied to the raw
 * double score BEFORE conversion to float. No BM25 clause score enters the sum.
 * This implementation prioritizes correctness; it does not implement block-max WAND.
 */
public final class MonomialCandidateQuery extends Query {
    private final List<Query> monomials;
    private final double[] coefficients;
    private final double intercept, threshold, lowerBound;
    private final Query candidates;

    public MonomialCandidateQuery(List<Query> monomials, double[] coefficients,
                                  double intercept, double threshold) {
        if (monomials.size() != coefficients.length || !Double.isFinite(intercept)
                || !Double.isFinite(threshold)) throw new IllegalArgumentException("Invalid linear model/cutoff");
        this.monomials = Collections.unmodifiableList(new ArrayList<>(monomials));
        this.coefficients = coefficients.clone();
        this.intercept = intercept;
        this.threshold = threshold;
        double lower = intercept, upper = intercept;
        List<Query> positives = new ArrayList<>();
        for (int i = 0; i < coefficients.length; i++) {
            Objects.requireNonNull(monomials.get(i), "monomial query");
            double coefficient = coefficients[i];
            if (!Double.isFinite(coefficient)) throw new IllegalArgumentException("Non-finite coefficient");
            if (coefficient < 0) lower += coefficient;
            else { upper += coefficient; if (coefficient > 0) positives.add(monomials.get(i)); }
        }
        if (!Double.isFinite(lower) || !Double.isFinite(upper) || upper - lower > Float.MAX_VALUE) {
            throw new IllegalArgumentException("Model score range exceeds Lucene float range");
        }
        lowerBound = lower;
        // With no active positive term score <= intercept. Otherwise preserve the
        // entire index, including documents that activate no monomials at all.
        candidates = threshold >= intercept ? disjunction(positives) : new MatchAllDocsQuery();
    }

    private static Query disjunction(List<Query> queries) {
        if (queries.isEmpty()) return new MatchNoDocsQuery();
        int limit = BooleanQuery.getMaxClauseCount();
        if (limit < 2 && queries.size() > 1) throw new IllegalStateException("Lucene clause limit must be >= 2");
        List<Query> level = queries;
        while (level.size() > 1) {
            List<Query> next = new ArrayList<>();
            for (int start = 0; start < level.size(); start += limit) {
                BooleanQuery.Builder builder = new BooleanQuery.Builder().setMinimumNumberShouldMatch(1);
                for (int i = start; i < Math.min(start + limit, level.size()); i++) {
                    builder.add(level.get(i), BooleanClause.Occur.SHOULD);
                }
                next.add(builder.build());
            }
            level = next;
        }
        return level.get(0);
    }

    public double getScoreShift() { return -lowerBound; }

    @Override
    public Weight createWeight(IndexSearcher searcher, ScoreMode mode, float boost) throws IOException {
        Weight candidateWeight = searcher.createWeight(searcher.rewrite(candidates), ScoreMode.COMPLETE_NO_SCORES, 1);
        List<Weight> weights = new ArrayList<>();
        for (Query monomial : monomials) {
            weights.add(searcher.createWeight(searcher.rewrite(monomial), ScoreMode.COMPLETE_NO_SCORES, 1));
        }
        return new Weight(this) {
            @Override public void extractTerms(Set<Term> terms) { for (Weight w : weights) w.extractTerms(terms); }
            @Override public boolean isCacheable(LeafReaderContext leaf) { return false; }
            @Override public Scorer scorer(LeafReaderContext leaf) throws IOException {
                Scorer candidateScorer = candidateWeight.scorer(leaf);
                if (candidateScorer == null) return null;
                DocIdSetIterator approximation = exactIterator(candidateScorer);
                DocIdSetIterator[] iterators = new DocIdSetIterator[weights.size()];
                for (int i = 0; i < iterators.length; i++) {
                    Scorer s = weights.get(i).scorer(leaf);
                    iterators[i] = s == null ? null : exactIterator(s);
                }
                return new LinearScorer(this, approximation, iterators, boost);
            }
            @Override public Explanation explain(LeafReaderContext leaf, int doc) throws IOException {
                double raw = intercept;
                List<Explanation> contributions = new ArrayList<>();
                contributions.add(Explanation.match(intercept, "intercept"));
                for (int i = 0; i < weights.size(); i++) {
                    Explanation condition = weights.get(i).explain(leaf, doc);
                    if (condition.isMatch()) {
                        raw += coefficients[i];
                        contributions.add(Explanation.match(coefficients[i], "monomial " + i, condition));
                    }
                }
                Explanation modelScore = Explanation.match(raw, "raw linear model score", contributions);
                if (!(raw > threshold)) return Explanation.noMatch("raw score <= threshold " + threshold, modelScore);
                return Explanation.match((float)Math.max(0, raw - lowerBound) * boost,
                        "shifted Lucene score; raw score > " + threshold, modelScore);
            }
        };
    }

    private static DocIdSetIterator exactIterator(Scorer scorer) {
        TwoPhaseIterator twoPhase = scorer.twoPhaseIterator();
        return twoPhase == null ? scorer.iterator() : TwoPhaseIterator.asDocIdSetIterator(twoPhase);
    }

    private final class LinearScorer extends Scorer {
        private final TwoPhaseIterator twoPhase;
        private final DocIdSetIterator iterator;
        private final float boost;
        private double raw;
        LinearScorer(Weight weight, DocIdSetIterator candidates, DocIdSetIterator[] conditions, float boost) {
            super(weight);
            this.boost = boost;
            twoPhase = new TwoPhaseIterator(candidates) {
                private int lastDoc = -1;
                private boolean lastMatch;
                @Override public boolean matches() throws IOException {
                    int doc = approximation().docID();
                    if (doc == lastDoc) return lastMatch;
                    raw = intercept;
                    for (int i = 0; i < conditions.length; i++) {
                        DocIdSetIterator condition = conditions[i];
                        if (condition == null || coefficients[i] == 0) continue;
                        if (condition.docID() < doc) condition.advance(doc);
                        if (condition.docID() == doc) raw += coefficients[i];
                    }
                    lastDoc = doc;
                    lastMatch = raw > threshold;
                    return lastMatch;
                }
                @Override public float matchCost() { return Math.max(1, 10f * conditions.length); }
            };
            iterator = TwoPhaseIterator.asDocIdSetIterator(twoPhase);
        }
        @Override public DocIdSetIterator iterator() { return iterator; }
        @Override public TwoPhaseIterator twoPhaseIterator() { return twoPhase; }
        @Override public int docID() { return twoPhase.approximation().docID(); }
        @Override public float score() { return (float)Math.max(0, raw - lowerBound) * boost; }
        @Override public float getMaxScore(int upTo) { return Float.POSITIVE_INFINITY; }
    }

    @Override public String toString(String field) {
        return "MonomialCandidateQuery(terms=" + monomials.size() + ", threshold=" + threshold
                + ", intercept=" + intercept + ", scoreShift=" + getScoreShift() + ")";
    }
    @Override public boolean equals(Object other) {
        if (!sameClassAs(other)) return false;
        MonomialCandidateQuery that = (MonomialCandidateQuery)other;
        return monomials.equals(that.monomials) && Arrays.equals(coefficients, that.coefficients)
                && Double.compare(intercept, that.intercept) == 0 && Double.compare(threshold, that.threshold) == 0;
    }
    @Override public int hashCode() { return Objects.hash(classHash(), monomials, Arrays.hashCode(coefficients), intercept, threshold); }
}
