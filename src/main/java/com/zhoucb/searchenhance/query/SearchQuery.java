package com.zhoucb.searchenhance.query;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Compiles visual conditions without depending on the IntelliJ SDK. Every
 * condition in a group must hold on the same line; any matching group satisfies
 * the query. Empty groups are ignored. An entirely empty query cannot execute,
 * avoiding accidental full-project searches. Exclusion-only groups match lines
 * without their forbidden expressions, including empty lines.
 *
 * <p>Author: zhoucb50741</p>
 * <p>Date: 2026-10-14</p>
 */
public final class SearchQuery {
    /** Immutable alternatives, omitting intentionally blank groups. */
    private final List<ConditionGroup> groups;
    /** Case preference forwarded to the native search model. */
    private final boolean caseSensitive;

    /**
     * Creates an immutable normalized query.
     *
     * @param groups alternative groups; null and empty groups are ignored
     * @param caseSensitive whether matching distinguishes letter case
     * <p>Returns: a newly initialized immutable query.</p>
     */
    public SearchQuery(List<ConditionGroup> groups, boolean caseSensitive) {
        Objects.requireNonNull(groups, "groups");
        this.caseSensitive = caseSensitive;
        List<ConditionGroup> normalized = new ArrayList<>();
        for (ConditionGroup group : groups) {
            if (group != null && !group.isEmpty()) {
                normalized.add(group);
            }
        }
        this.groups = Collections.unmodifiableList(normalized);
    }

    /**
     * Returns normalized alternative groups.
     * <p>Params: none.</p>
     * @return immutable groups with empty alternatives removed
     */
    public List<ConditionGroup> getGroups() {
        return groups;
    }

    /**
     * Returns the case setting.
     * <p>Params: none.</p>
     * @return true for case-sensitive matching
     */
    public boolean isCaseSensitive() {
        return caseSensitive;
    }

    /**
     * Checks whether any effective conditions were provided.
     * <p>Params: none.</p>
     * @return true when the query cannot be executed
     */
    public boolean isEmpty() {
        return groups.isEmpty();
    }

    /**
     * Returns Java Pattern flags. Generated expressions never embed case flags.
     * <p>Params: none.</p>
     * @return Unicode-aware case flags when the case-sensitive option is disabled
     */
    public int getRegexFlags() {
        return caseSensitive ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
    }

    /**
     * Builds an expression matching complete qualifying lines. CRLF is one
     * separator; LF, CR, VT, FF, NEL, U+2028 and U+2029 also delimit lines. No
     * condition can cross a line separator, and separators are not consumed.
     * Empty lines may match exclusion-only conditions. Results deliberately span
     * the entire matching line so native usage results remain useful for AND/NOT.
     * <p>Params: none.</p>
     * @return a regex to compile using {@link #getRegexFlags()} or equivalent IDE flags
     * @throws IllegalStateException when no effective conditions were provided
     */
    public String toSameLineRegex() {
        if (isEmpty()) {
            throw new IllegalStateException("Add at least one nonblank search condition.");
        }
        List<String> alternatives = new ArrayList<>();
        for (ConditionGroup group : groups) {
            StringBuilder alternative = new StringBuilder();
            for (SearchCondition condition : group.getConditions()) {
                alternative.append(condition.isExcluded() ? "(?!" : "(?=")
                        .append(SearchCondition.LINE_CHAR).append("*?")
                        .append("(?:").append(condition.toRegex()).append("))");
            }
            alternatives.add(alternative.toString());
        }
        return SearchCondition.LINE_START + "(?:" + String.join("|", alternatives) + ")"
                + SearchCondition.LINE_CHAR + "*" + SearchCondition.LINE_END;
    }
}
