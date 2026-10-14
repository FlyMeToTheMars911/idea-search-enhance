package com.zhoucb.searchenhance.query;

/**
 * Available visual condition templates. All values are interpreted as literal input.
 *
 * <p>Author: zhoucb50741</p>
 * <p>Date: 2026-10-14</p>
 */
public enum MatchType {
    /** Contains literal text. */
    TEXT,
    /** Contains any newline-separated alternative. */
    ANY_OF,
    /** Contains every newline-separated value in the specified order. */
    ORDERED,
    /** Starts with the literal value. */
    STARTS_WITH,
    /** Ends with the literal value. */
    ENDS_WITH,
    /** Contains two adjacent literal values. */
    EXACT_SEQUENCE,
    /** Contains two values separated only by optional horizontal whitespace. */
    WHITESPACE_BETWEEN,
    /** Contains a field assigned to one of the accepted values. */
    VALUE_EQUALS,
    /** Contains a double-equality comparison against an accepted value. */
    VALUE_DOUBLE_EQUALS,
    /** Contains a complete decimal run with optional length bounds. */
    DIGITS,
    /** Contains an identifier beginning with the literal prefix. */
    PREFIX,
    /** Contains a literal method name followed by an opening parenthesis. */
    METHOD_CALL,
    /** Contains source text inside a balanced double-quoted string. */
    STRING_CONTAINS,
    /** Contains a complete identifier without an immediately following member dot. */
    TOKEN_NO_MEMBER
}
