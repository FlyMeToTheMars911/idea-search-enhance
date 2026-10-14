package com.zhoucb.searchenhance.query;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One immutable, optionally negated, literal search template. Multi-value fields
 * use line-separated values so punctuation such as commas remains literal. Blank
 * values are ignored while meaningful leading/trailing whitespace is preserved.
 *
 * <p>Author: zhoucb50741</p>
 * <p>Date: 2026-10-14</p>
 */
public final class SearchCondition {
    /** Matches one character confined to a Unicode line. */
    static final String LINE_CHAR = "[^\\r\\n\\u000B\\f\\u0085\\u2028\\u2029]";
    /** Matches the start of a line while treating CRLF as one separator. */
    static final String LINE_START =
            "(?:\\A|(?<=[\\n\\u000B\\f\\u0085\\u2028\\u2029])|(?<=\\r)(?!\\n))";
    /** Matches a Unicode line ending without consuming its separator. */
    static final String LINE_END = "(?=\\R|\\z)";
    /** Unicode identifier-like characters used by complete-word matching. */
    private static final String WORD_CHAR = "[\\p{L}\\p{N}\\p{M}\\p{Pc}]";
    /** Requires the preceding character to be outside a word. */
    private static final String WORD_START = "(?<!" + WORD_CHAR + ")";
    /** Requires the following character to be outside a word. */
    private static final String WORD_END = "(?!" + WORD_CHAR + ")";
    /** Allows same-line horizontal whitespace, excluding every line separator. */
    private static final String HORIZONTAL_SPACE = "[\\t\\p{Zs}]*";
    /** A valid expression that cannot match any input. */
    private static final String NEVER_MATCH = "(?!)";
    /** Minimum supported positive digit count. */
    private static final int MIN_DIGITS = 1;
    /** Practical upper bound to reject pathological counts before native search. */
    private static final int MAX_DIGITS = 1_000_000;
    /** Unicode line separator detector shared by input validation. */
    private static final Pattern LINE_SEPARATOR = Pattern.compile("\\R");
    /** Decimal-only syntax for numeric template bounds. */
    private static final Pattern UNSIGNED_INTEGER = Pattern.compile("[0-9]+");
    /** Complete source-string tokens, including escaped double quotes. */
    private static final Pattern STRING_FRAGMENT = Pattern.compile(
            "(?:[^\"\\\\\\r\\n\\u000B\\f\\u0085\\u2028\\u2029]|\\\\"
                    + LINE_CHAR + ")*+");

    /** Selected visual template. */
    private final MatchType type;
    /** First literal or newline-separated input, preserved verbatim. */
    private final String first;
    /** Second literal or newline-separated input, preserved verbatim. */
    private final String second;
    /** Whether the containing query must negate this condition. */
    private final boolean excluded;
    /** Whether literal terms require complete-word boundaries. */
    private final boolean wholeWord;
    /** Prevalidated positive regular expression. */
    private final String regex;

    /**
     * Creates and validates one template. DIGITS accepts a minimum and maximum
     * digit count; both empty means any positive count, equal bounds mean fixed
     * length, and one empty bound means unbounded on that side. A digit condition
     * matches a complete decimal run, avoiding partial matches of longer numbers.
     *
     * @param type selected template
     * @param first main input; ANY_OF/ORDERED use newline-separated values
     * @param second secondary input; VALUE_* use newline-separated accepted values
     * @param excluded true to require the condition to be absent
     * @param wholeWord true to delimit literal values with Unicode word boundaries
     * <p>Returns: a newly initialized immutable condition.</p>
     * @throws IllegalArgumentException for invalid DIGITS bounds
     */
    public SearchCondition(MatchType type, String first, String second,
                           boolean excluded, boolean wholeWord) {
        this.type = Objects.requireNonNull(type, "type");
        this.first = first == null ? "" : first;
        this.second = second == null ? "" : second;
        this.excluded = excluded;
        this.wholeWord = wholeWord;
        validateInputs();
        this.regex = buildRegex();
    }

    /**
     * Returns the selected template.
     * <p>Params: none.</p>
     * @return the condition type
     */
    public MatchType getType() {
        return type;
    }

    /**
     * Returns the main input.
     * <p>Params: none.</p>
     * @return literal source input, including meaningful whitespace
     */
    public String getFirst() {
        return first;
    }

    /**
     * Returns the secondary input.
     * <p>Params: none.</p>
     * @return literal source input, including meaningful whitespace
     */
    public String getSecond() {
        return second;
    }

    /**
     * Returns whether this condition is negated.
     * <p>Params: none.</p>
     * @return true for a NOT condition
     */
    public boolean isExcluded() {
        return excluded;
    }

    /**
     * Returns the literal word-boundary setting.
     * <p>Params: none.</p>
     * @return true for complete-word matching
     */
    public boolean isWholeWord() {
        return wholeWord;
    }

    /**
     * Checks whether required inputs were provided. DIGITS is active even with
     * empty fields because that represents any positive-length decimal number.
     * <p>Params: none.</p>
     * @return true when the condition should be ignored by its group
     */
    public boolean isEmpty() {
        switch (type) {
            case DIGITS:
                return false;
            case EXACT_SEQUENCE:
            case WHITESPACE_BETWEEN:
                return first.isBlank() && second.isBlank();
            case VALUE_EQUALS:
            case VALUE_DOUBLE_EQUALS:
                return first.isBlank() && splitValues(second).isEmpty();
            case ANY_OF:
            case ORDERED:
                return splitValues(first).isEmpty();
            default:
                return first.isBlank();
        }
    }

    /**
     * Returns the positive form of this condition. The containing query applies
     * negation using lookaheads. All user input is quoted, and every generated
     * wildcard/whitespace token is confined to one Unicode line.
     * <p>Params: none.</p>
     * @return the reusable expression for this template, or an impossible pattern for blank inputs
     */
    public String toRegex() {
        return regex;
    }

    /**
     * Rejects partly filled pairs and multiline literals instead of silently
     * omitting them and broadening an AND/NOT query. Intentionally blank pairs
     * remain editable and are ignored until filled.
     * <p>Params: none.</p>
     * <p>Returns: normally when the row is blank or complete.</p>
     */
    private void validateInputs() {
        switch (type) {
            case EXACT_SEQUENCE:
            case WHITESPACE_BETWEEN:
                if (first.isBlank() != second.isBlank()) {
                    throw new IllegalArgumentException("请完整填写此条件的左右两个输入框，或同时清空。");
                }
                break;
            case VALUE_EQUALS:
            case VALUE_DOUBLE_EQUALS:
                if (first.isBlank() != splitValues(second).isEmpty()) {
                    throw new IllegalArgumentException("请完整填写此条件的左右两个输入框，或同时清空。");
                }
                break;
            default:
                break;
        }
        if (isEmpty()) {
            return;
        }
        switch (type) {
            case ANY_OF:
            case ORDERED:
            case DIGITS:
                return;
            default:
                if (LINE_SEPARATOR.matcher(first).find()) {
                    throw new IllegalArgumentException("此场景只支持单行文本；多个值请使用“任意一个”或“按顺序出现”。");
                }
        }
        switch (type) {
            case STRING_CONTAINS:
                if (!STRING_FRAGMENT.matcher(first).matches()) {
                    throw new IllegalArgumentException("字符串内容请按源代码填写转义形式；查找引号或单个反斜杠也可使用“包含文本”。");
                }
                break;
            case EXACT_SEQUENCE:
            case WHITESPACE_BETWEEN:
                if (LINE_SEPARATOR.matcher(second).find()) {
                    throw new IllegalArgumentException("此场景右侧只能填写单行文本，搜索条件不跨行。");
                }
                break;
            default:
                break;
        }
    }

    /**
     * Converts the selected template into its positive regular expression.
     * <p>Params: none.</p>
     * @return the template's generated expression
     */
    private String buildRegex() {
        if (isEmpty()) {
            return NEVER_MATCH;
        }
        switch (type) {
            case ANY_OF:
                return alternatives(first);
            case ORDERED:
                return ordered(first);
            case STARTS_WITH:
                return LINE_START + literal(first);
            case ENDS_WITH:
                return literal(first) + LINE_END;
            case EXACT_SEQUENCE:
                return literal(first + second);
            case WHITESPACE_BETWEEN:
                return literal(first) + HORIZONTAL_SPACE + literal(second);
            case VALUE_EQUALS:
                return literal(first) + HORIZONTAL_SPACE + "=" + HORIZONTAL_SPACE + alternatives(second);
            case VALUE_DOUBLE_EQUALS:
                return literal(first) + HORIZONTAL_SPACE + "==" + HORIZONTAL_SPACE + alternatives(second);
            case DIGITS:
                return digitRegex();
            case PREFIX:
                return WORD_START + literalWithoutBoundary(first) + WORD_CHAR + "*";
            case METHOD_CALL:
                return literal(first) + HORIZONTAL_SPACE + "\\(";
            case STRING_CONTAINS:
                return stringContainsRegex();
            case TOKEN_NO_MEMBER:
                return WORD_START + literalWithoutBoundary(first) + "(?![\\p{L}\\p{N}\\p{M}\\p{Pc}.])";
            default:
                return literal(first);
        }
    }

    /**
     * Wraps a literal with the optional Unicode word boundaries.
     * @param value source literal
     * @return a safely quoted same-line expression
     */
    private String literal(String value) {
        String quoted = literalWithoutBoundary(value);
        return wholeWord ? WORD_START + quoted + WORD_END : quoted;
    }

    /**
     * Rejects cross-line terms while safely escaping all regex syntax.
     * @param value source literal
     * @return quoted text, or an impossible expression for a multiline term
     */
    private static String literalWithoutBoundary(String value) {
        return LINE_SEPARATOR.matcher(value).find() ? NEVER_MATCH : Pattern.quote(value);
    }

    /**
     * Builds a literal OR expression from newline-separated input.
     * @param input literal alternatives
     * @return a grouped OR expression
     */
    private String alternatives(String input) {
        List<String> expressions = new ArrayList<>();
        for (String value : splitValues(input)) {
            expressions.add(literal(value));
        }
        return expressions.isEmpty() ? NEVER_MATCH : "(?:" + String.join("|", expressions) + ")";
    }

    /**
     * Builds an ordered sequence with arbitrary same-line content between values.
     * @param input newline-separated literal sequence
     * @return an ordered expression
     */
    private String ordered(String input) {
        List<String> expressions = new ArrayList<>();
        for (String value : splitValues(input)) {
            expressions.add(literal(value));
        }
        if (expressions.isEmpty()) {
            return NEVER_MATCH;
        }
        StringBuilder result = new StringBuilder(LINE_START);
        for (String expression : expressions) {
            // Choosing the earliest completion of each literal leaves the most
            // room for later terms. Atomic groups avoid exponential retries on
            // lines containing many repeated early terms but no final term.
            result.append("(?>").append(LINE_CHAR).append("*?")
                    .append(expression).append(')');
        }
        return result.toString();
    }

    /**
     * Splits multiline template values while preserving literal spacing.
     * @param input source values separated by Unicode line separators
     * @return nonblank values in their original order
     */
    private static List<String> splitValues(String input) {
        List<String> result = new ArrayList<>();
        for (String value : LINE_SEPARATOR.split(input, -1)) {
            if (!value.isBlank()) {
                result.add(value);
            }
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * Builds a complete decimal run with optional minimum/maximum counts.
     * <p>Params: none.</p>
     * @return a bounded decimal expression
     */
    private String digitRegex() {
        Integer minimum = parseBound(first);
        Integer maximum = parseBound(second);
        if (minimum != null && maximum != null && minimum > maximum) {
            throw new IllegalArgumentException("Minimum digit count cannot exceed maximum digit count.");
        }
        int lower = minimum == null ? MIN_DIGITS : minimum;
        String upper = maximum == null ? "" : maximum.toString();
        String digits = "(?<![0-9])[0-9]{" + lower + "," + upper + "}(?![0-9])";
        return wholeWord ? WORD_START + digits + WORD_END : digits;
    }

    /**
     * Parses one optional count using an explicit practical upper bound.
     * @param input source bound, optionally blank
     * @return null for an omitted bound, otherwise a validated positive count
     */
    private static Integer parseBound(String input) {
        if (input.isBlank()) {
            return null;
        }
        String normalized = input.trim();
        if (!UNSIGNED_INTEGER.matcher(normalized).matches()) {
            throw new IllegalArgumentException("Digit count must be a positive integer.");
        }
        try {
            int value = Integer.parseInt(normalized);
            if (value < MIN_DIGITS || value > MAX_DIGITS) {
                throw new IllegalArgumentException("Digit count must be between 1 and " + MAX_DIGITS + ".");
            }
            return value;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Digit count is too large.", exception);
        }
    }

    /**
     * Finds a source-text substring inside a balanced double-quoted string on the
     * current line, recognizing escaped characters and earlier quoted strings.
     * This is a lexical template; it does not parse programming-language comments
     * or decode escape sequences in the search text.
     * <p>Params: none.</p>
     * @return a line-anchored quoted-string expression
     */
    private String stringContainsRegex() {
        String escaped = "\\\\" + LINE_CHAR;
        String plain = "[^\"\\\\\\r\\n\\u000B\\f\\u0085\\u2028\\u2029]";
        // Possessive loops consume deterministic source tokens. A preceding
        // string may be skipped only when it does not contain the search term,
        // so locating the first qualifying string requires no recursive rewind.
        String term = literal(first);
        String token = "(?:" + plain + "|" + escaped + ")";
        String withoutTerm = "(?:(?!" + term + ")" + token + ")*+";
        String completeBody = "(?:" + plain + "++|" + escaped + ")*+";
        String skippedString = "\"" + withoutTerm + "\"";
        String prefix = "(?:" + escaped + "|" + plain + "++|" + skippedString + ")*+";
        return LINE_START + prefix + "\"" + withoutTerm + term + completeBody + "\"";
    }
}
