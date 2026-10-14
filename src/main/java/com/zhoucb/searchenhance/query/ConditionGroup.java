package com.zhoucb.searchenhance.query;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * An immutable group whose included terms are ANDed and excluded terms are negated.
 * Groups are ORed by {@link SearchQuery}. Null and blank terms are ignored; nonblank
 * terms retain their original leading and trailing whitespace.
 *
 * <p>Author: zhoucb50741</p>
 * <p>Date: 2026-10-14</p>
 */
public final class ConditionGroup {
    /** Literal inputs retained for the legacy two-list constructor. */
    private final List<String> includes;
    /** Negated inputs retained for the legacy two-list constructor. */
    private final List<String> excludes;
    /** Immutable visual conditions combined by AND and AND NOT. */
    private final List<SearchCondition> conditions;

    /**
     * Creates one condition group.
     *
     * @param includes literal terms that must all occur; the list must be nonnull
     * @param excludes literal terms that must all be absent; the list must be nonnull
     * <p>Returns: a newly initialized immutable condition group.</p>
     */
    public ConditionGroup(List<String> includes, List<String> excludes) {
        this.includes = normalize(includes);
        this.excludes = normalize(excludes);
        List<SearchCondition> converted = new ArrayList<>();
        for (String term : this.includes) {
            converted.add(new SearchCondition(MatchType.TEXT, term, "", false, false));
        }
        for (String term : this.excludes) {
            converted.add(new SearchCondition(MatchType.TEXT, term, "", true, false));
        }
        this.conditions = Collections.unmodifiableList(converted);
    }

    /**
     * Creates a group containing visual search templates.
     *
     * @param conditions conditions that must all be satisfied, including negations
     * <p>Returns: a newly initialized immutable condition group.</p>
     */
    public ConditionGroup(List<SearchCondition> conditions) {
        Objects.requireNonNull(conditions, "conditions");
        List<SearchCondition> copied = new ArrayList<>();
        List<String> required = new ArrayList<>();
        List<String> forbidden = new ArrayList<>();
        for (SearchCondition condition : conditions) {
            if (condition != null && !condition.isEmpty()) {
                copied.add(condition);
                if (condition.isExcluded()) {
                    forbidden.add(condition.getFirst());
                } else {
                    required.add(condition.getFirst());
                }
            }
        }
        this.conditions = Collections.unmodifiableList(copied);
        this.includes = Collections.unmodifiableList(required);
        this.excludes = Collections.unmodifiableList(forbidden);
    }

    /**
     * Returns the normalized visual conditions.
     * <p>Params: none.</p>
     *
     * @return an immutable list of positive and negated conditions
     */
    public List<SearchCondition> getConditions() {
        return conditions;
    }

    /**
     * Returns the required terms.
     * <p>Params: none.</p>
     *
     * @return an immutable list of literal terms
     */
    public List<String> getIncludes() {
        return includes;
    }

    /**
     * Returns the forbidden terms.
     * <p>Params: none.</p>
     *
     * @return an immutable list of literal terms
     */
    public List<String> getExcludes() {
        return excludes;
    }

    /**
     * Checks whether the group has any effective conditions.
     * <p>Params: none.</p>
     *
     * @return true when both normalized lists are empty
     */
    public boolean isEmpty() {
        return conditions.isEmpty();
    }

    /**
     * Removes null and blank input while copying the caller's list.
     *
     * @param terms source literal terms
     * @return an immutable list preserving meaningful whitespace and input order
     */
    private static List<String> normalize(List<String> terms) {
        Objects.requireNonNull(terms, "terms");
        List<String> result = new ArrayList<>();
        for (String term : terms) {
            if (term != null && !term.isBlank()) {
                result.add(term);
            }
        }
        return Collections.unmodifiableList(result);
    }
}
