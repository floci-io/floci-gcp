package io.floci.gcp.core.common;

import java.util.Optional;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs a user-supplied java.util.regex pattern under a step budget. GCP evaluates these patterns
 * with RE2, which cannot backtrack; java.util.regex can, so a pattern such as {@code (a+){10}$}
 * would otherwise stall the thread running it. A match that reads past its budget, or recurses
 * deeply enough to overflow the stack, ends with {@link BudgetExceeded}.
 */
public final class BoundedRegex {
    private static final long MATCH_STEP_BUDGET = 1_000_000;
    private static final long MATCH_STEPS_PER_CHAR = 100;

    private BoundedRegex() {
    }

    /** The first match of {@code pattern} in {@code text}, or empty when there is none. */
    public static Optional<MatchResult> find(Pattern pattern, CharSequence text) {
        Matcher matcher = pattern.matcher(new BoundedCharSequence(text));
        try {
            return matcher.find() ? Optional.of(matcher.toMatchResult()) : Optional.empty();
        } catch (StepBudgetExceeded e) {
            throw new BudgetExceeded("exceeded its matching budget over " + text.length() + " characters");
        } catch (StackOverflowError e) {
            throw new BudgetExceeded("recursed too deeply over " + text.length() + " characters");
        }
    }

    /** The pattern could not be decided within its budget; the reason names why. */
    public static final class BudgetExceeded extends RuntimeException {
        BudgetExceeded(String reason) {
            super(reason, null, false, false);
        }
    }

    private static final class BoundedCharSequence implements CharSequence {
        private final CharSequence delegate;
        private final long budget;
        private final long[] steps;

        BoundedCharSequence(CharSequence delegate) {
            this(delegate, MATCH_STEP_BUDGET + MATCH_STEPS_PER_CHAR * delegate.length(), new long[1]);
        }

        private BoundedCharSequence(CharSequence delegate, long budget, long[] steps) {
            this.delegate = delegate;
            this.budget = budget;
            this.steps = steps;
        }

        @Override
        public int length() {
            return delegate.length();
        }

        @Override
        public char charAt(int index) {
            if (++steps[0] > budget) {
                throw new StepBudgetExceeded();
            }
            return delegate.charAt(index);
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return new BoundedCharSequence(delegate.subSequence(start, end), budget, steps);
        }

        @Override
        public String toString() {
            return delegate.toString();
        }
    }

    private static final class StepBudgetExceeded extends RuntimeException {
        StepBudgetExceeded() {
            super(null, null, false, false);
        }
    }
}
