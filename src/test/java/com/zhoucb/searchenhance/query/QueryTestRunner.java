package com.zhoucb.searchenhance.query;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dependency-free regression tests based on the user's 0-24 regex reference.
 *
 * <p>Author: zhoucb50741</p>
 * <p>Date: 2026-10-14</p>
 */
public final class QueryTestRunner {
    /** Total checked semantic expectations, including deterministic random cases. */
    private static int assertions;

    /**
     * Runs semantic, boundary, literal-escaping, and randomized regression checks.
     * @param args unused command-line arguments
     * <p>Returns: normally on success; throws AssertionError on a failed check.</p>
     */
    public static void main(String[] args) {
        literalAndBooleanCases();
        templateCases();
        unicodeAndLineCases();
        normalizationAndValidationCases();
        randomizedBooleanCases();
        runWithTimeout(QueryTestRunner::longLineCases);
        System.out.println("Query tests passed: " + assertions + " assertions.");
    }

    /**
     * Verifies reference sections 0-7 and 21-24.
     * <p>Params: none.</p>
     * <p>Returns: normally when all checks pass.</p>
     */
    private static void literalAndBooleanCases() {
        SearchCondition task = condition(MatchType.TEXT, "taskId", "", false, true);
        assertLines(query(task), "taskId\ntaskId = 123\nget(taskId)\n\"taskId\"\nmytaskId\ntaskId123\ntaskId_backup",
                "taskId", "taskId = 123", "get(taskId)", "\"taskId\"");

        SearchQuery and = query(task,
                condition(MatchType.TEXT, "status", "", false, true),
                condition(MatchType.TEXT, "success", "", false, true));
        assertLines(and, "taskId = 123, status = success\nstatus success taskId\nsuccess: taskId=123 status=1\n"
                        + "taskIdBackup status success\ntaskId statusCode success\ntaskId status successful",
                "taskId = 123, status = success", "status success taskId", "success: taskId=123 status=1");

        SearchQuery or = query(condition(MatchType.ANY_OF, "ERROR\nWARN\nFATAL", "", false, true));
        assertLines(or, "ERROR\nWARN\nFATAL\n2026 ERROR request failed\nERROR_CODE\nWARNING\nFATAL_ERROR",
                "ERROR", "WARN", "FATAL", "2026 ERROR request failed");

        assertLines(query(condition(MatchType.TEXT, "success", "", false, true),
                        condition(MatchType.TEXT, "retry", "", true, true)),
                "request success\ntask success id=123\nrequest success retry\nretry success\nsuccessful\nsuccess retryCount",
                "request success", "task success id=123", "success retryCount");

        assertLines(query(task, condition(MatchType.TEXT, "ERROR", "", false, true),
                        condition(MatchType.TEXT, "timeout", "", true, true)),
                "taskId ERROR\ntaskId ERROR timeout\nERROR\ntaskId ERROR timeoutCount", "taskId ERROR", "taskId ERROR timeoutCount");

        assertLines(query(condition(MatchType.ANY_OF, "ERROR\nWARN", "", false, true),
                        condition(MatchType.ANY_OF, "timeout\nnetwork\nignored", "", true, true)),
                "ERROR ok\nWARN ok\nERROR timeout\nWARN network\nERROR ignored\nERROR ignoredFlag", "ERROR ok", "WARN ok", "ERROR ignoredFlag");

        SearchQuery groups = new SearchQuery(Arrays.asList(
                new ConditionGroup(Arrays.asList(task, condition(MatchType.TEXT, "ERROR", "", false, true))),
                new ConditionGroup(Collections.singletonList(condition(MatchType.TEXT, "WARN", "", false, true)))), true);
        assertLines(groups, "taskId ERROR\nWARN\ntaskId\nERROR", "taskId ERROR", "WARN");

        for (String term : Arrays.asList("user.getName()", "a+b", "list[0]", ".*+?()[]{}^$|\\", "a\\Eb")) {
            assertLines(query(condition(MatchType.TEXT, term, "", false, false)), term + "\nother", term);
        }
        assertLines(query(condition(MatchType.TEXT, " A ", "", false, false)), " A \nA\nXA ", " A ");
        assertLines(new SearchQuery(Collections.singletonList(new ConditionGroup(Collections.singletonList(
                condition(MatchType.TEXT, "é", "", false, true)))), false), "É\né", "É", "é");
        assertLines(new SearchQuery(Collections.singletonList(new ConditionGroup(Collections.singletonList(
                condition(MatchType.TEXT, "é", "", false, true)))), true), "É\né", "é");
    }

    /**
     * Verifies reference sections 8-20 including positive and negative examples.
     * <p>Params: none.</p>
     * <p>Returns: normally when all checks pass.</p>
     */
    private static void templateCases() {
        assertLines(query(condition(MatchType.ORDERED, "SELECT\nFROM", "", false, true)),
                "SELECT value FROM table\nFROM SELECT\nSELECTED FROM", "SELECT value FROM table");
        assertLines(query(condition(MatchType.ORDERED, "B\nA", "", false, true)), "A B\nB A", "B A");
        assertLines(query(condition(MatchType.STARTS_WITH, "ERROR", "", false, true)),
                "ERROR request failed\n2026 ERROR request failed\nERROR_CODE", "ERROR request failed");
        assertLines(query(condition(MatchType.ENDS_WITH, "success", "", false, true)),
                "request success\nsuccess later\nrequest successful\nsuccess ", "request success");
        assertLines(query(condition(MatchType.EXACT_SEQUENCE, "hello", "world", false, false)),
                "helloworld\nhello world", "helloworld");
        assertLines(query(condition(MatchType.VALUE_EQUALS, "status", "SUCCESS", false, true)),
                "status=SUCCESS\nstatus = SUCCESS\nstatus= SUCCESS\nstatus =SUCCESS\nstatus==SUCCESS\nstatus=SUCCESSFUL",
                "status=SUCCESS", "status = SUCCESS", "status= SUCCESS", "status =SUCCESS");
        assertLines(query(condition(MatchType.ORDERED, "taskId\nsuccess", "", false, false)),
                "taskId=123 request finished success\nsuccess taskId=123", "taskId=123 request finished success");
        assertLines(query(condition(MatchType.WHITESPACE_BETWEEN, "hello", "world", false, false)),
                "helloworld\nhello world\nhello\tworld\nhello     world\nhello-world",
                "helloworld", "hello world", "hello\tworld", "hello     world");
        assertLines(query(condition(MatchType.VALUE_DOUBLE_EQUALS, "status", "1\n2\n5", false, true)),
                "status == 1\nstatus==2\nstatus ==5\nstatus == 12\nstatus = 1\nstatus===1",
                "status == 1", "status==2", "status ==5");
        assertLines(query(condition(MatchType.DIGITS, "", "", false, false)), "123\n9999\n20260924\nnone", "123", "9999", "20260924");
        assertLines(query(condition(MatchType.DIGITS, "6", "6", false, false)),
                "123456\n12345\n1234567\nx=123456;", "123456", "x=123456;");
        assertLines(query(condition(MatchType.DIGITS, "2", "5", false, false)), "1\n12\n12345\n123456", "12", "12345");
        assertLines(query(condition(MatchType.DIGITS, "3", "", false, false)), "12\n123\n123456", "123", "123456");
        assertLines(query(condition(MatchType.DIGITS, "", "2", false, false)), "1\n12\n123", "1", "12");
        assertLines(query(condition(MatchType.DIGITS, "2", "2", false, true)), "x12\n_12\n12x\n12", "12");
        assertLines(query(condition(MatchType.PREFIX, "task", "", false, true)),
                "taskId\ntaskName\ntaskStatus\nmytaskId\ntask中文\n中文task", "taskId", "taskName", "taskStatus", "task中文");
        assertLines(query(condition(MatchType.METHOD_CALL, "execute", "", false, true)),
                "execute()\nexecute (a)\nmyexecute()\nexecuteLater()", "execute()", "execute (a)");
        assertLines(query(condition(MatchType.METHOD_CALL, "service.execute", "", false, true)),
                "service.execute()\nserviceXexecute()\nmyservice.execute()", "service.execute()");
        assertLines(query(condition(MatchType.STRING_CONTAINS, "success", "", false, false)),
                "\"request success\"\n\"success\"\n\"task execute success!\"\nsuccess\n\"other\" + success + \"tail\"\n\"other\" + \"success\"\n\"a \\\" quoted success\"",
                "\"request success\"", "\"success\"", "\"task execute success!\"", "\"other\" + \"success\"", "\"a \\\" quoted success\"");
        assertLines(query(condition(MatchType.STRING_CONTAINS, "\\\"success\\\"", "", false, false)),
                "\"\\\"success\\\"\"\n\"other\" + success + \"tail\"", "\"\\\"success\\\"\"");
        assertLines(query(condition(MatchType.TOKEN_NO_MEMBER, "user", "", false, false)),
                "user\nget(user)\nuser.name\nuser.id\nmyuser\nuser_backup", "user", "get(user)");
        assertLines(query(condition(MatchType.ANY_OF, "a,b\nc", "", false, false)), "a,b\na\nb\nc", "a,b", "c");
    }

    /**
     * Verifies strict Unicode line splitting, CRLF, and complete-word boundaries.
     * <p>Params: none.</p>
     * <p>Returns: normally when all checks pass.</p>
     */
    private static void unicodeAndLineCases() {
        SearchQuery and = query(condition(MatchType.TEXT, "A", "", false, false),
                condition(MatchType.TEXT, "B", "", false, false));
        for (String separator : Arrays.asList("\r\n", "\n", "\r", "\u000B", "\f", "\u0085", "\u2028", "\u2029")) {
            assertLines(and, "A" + separator + "B" + separator + "A B", "A B");
            assertLines(query(condition(MatchType.WHITESPACE_BETWEEN, "A", "B", false, false)), "A" + separator + "B");
            assertLines(query(condition(MatchType.TEXT, "A", "", false, false),
                    condition(MatchType.TEXT, "B", "", true, false)), "A" + separator + "B", "A");
        }
        assertLines(query(condition(MatchType.TEXT, "taskId", "", false, true)),
                "taskId\n中文taskId\ntaskId中文\ntaskId\u0301\n(taskId)", "taskId", "(taskId)");
        assertLines(query(condition(MatchType.TEXT, "成功", "", false, true)),
                "成功\n请求成功\n成功状态\n(成功)", "成功", "(成功)");
        expectThrows(IllegalArgumentException.class, () -> condition(MatchType.TEXT, "A\nB", "", false, false));
        expectThrows(IllegalArgumentException.class, () -> condition(MatchType.TEXT, "A\nB", "", true, false));
        assertLines(query(condition(MatchType.TEXT, "forbidden", "", true, false)), "ok\r\nforbidden", "ok");
        assertLines(query(condition(MatchType.TEXT, "forbidden", "", true, false)), "", "");
        assertLines(query(condition(MatchType.STARTS_WITH, "A", "", false, false),
                condition(MatchType.ENDS_WITH, "B", "", false, false)), "AB\r\nA\r\nB", "AB");
    }

    /**
     * Verifies immutability and empty/invalid input semantics.
     * <p>Params: none.</p>
     * <p>Returns: normally when all checks pass.</p>
     */
    private static void normalizationAndValidationCases() {
        List<SearchCondition> source = new ArrayList<>();
        source.add(condition(MatchType.TEXT, "A", "", false, false));
        ConditionGroup group = new ConditionGroup(source);
        source.clear();
        assertLines(new SearchQuery(Collections.singletonList(group), true), "A", "A");
        expectThrows(UnsupportedOperationException.class, () -> group.getConditions().clear());
        List<ConditionGroup> groups = new ArrayList<>();
        groups.add(group);
        SearchQuery immutable = new SearchQuery(groups, true);
        groups.clear();
        assertLines(immutable, "A", "A");
        expectThrows(UnsupportedOperationException.class, () -> immutable.getGroups().clear());
        SearchQuery empty = new SearchQuery(Arrays.asList(null,
                new ConditionGroup(Arrays.asList(null, condition(MatchType.TEXT, " \t", "", false, false)))), true);
        check(empty.isEmpty(), "An entirely blank query must be empty.");
        expectThrows(IllegalStateException.class, empty::toSameLineRegex);
        assertLines(new SearchQuery(Arrays.asList(new ConditionGroup(Collections.emptyList()), group), true), "A\nB", "A");
        for (String[] bounds : Arrays.asList(new String[]{"0", ""}, new String[]{"-1", ""}, new String[]{"1.5", ""},
                new String[]{"5", "2"}, new String[]{"99999999999999999", ""}, new String[]{"1", "1000001"})) {
            expectThrows(IllegalArgumentException.class, () -> condition(MatchType.DIGITS, bounds[0], bounds[1], false, false));
        }
        for (MatchType type : Arrays.asList(MatchType.EXACT_SEQUENCE, MatchType.WHITESPACE_BETWEEN,
                MatchType.VALUE_EQUALS, MatchType.VALUE_DOUBLE_EQUALS)) {
            expectThrows(IllegalArgumentException.class, () -> condition(type, "A", "", false, true));
            expectThrows(IllegalArgumentException.class, () -> condition(type, "", "B", true, true));
            check(condition(type, " ", "\t", false, true).isEmpty(), "Entirely blank pair must remain editable.");
        }
        expectThrows(IllegalArgumentException.class, () -> condition(MatchType.STRING_CONTAINS, "\"success\"", "", false, false));
        expectThrows(IllegalArgumentException.class, () -> condition(MatchType.STRING_CONTAINS, "\\", "", false, false));
    }

    /**
     * Checks generated Boolean expressions against an independent truth function.
     * <p>Params: none.</p>
     * <p>Returns: normally when all deterministic random cases pass.</p>
     */
    private static void randomizedBooleanCases() {
        Random random = new Random(20261014L);
        SearchQuery query = new SearchQuery(Arrays.asList(
                new ConditionGroup(Arrays.asList(condition(MatchType.TEXT, "A", "", false, false),
                        condition(MatchType.TEXT, "B", "", false, false), condition(MatchType.TEXT, "C", "", true, false))),
                new ConditionGroup(Arrays.asList(condition(MatchType.TEXT, "D", "", false, false),
                        condition(MatchType.TEXT, "E", "", true, false)))), true);
        for (int iteration = 0; iteration < 300; iteration++) {
            StringBuilder text = new StringBuilder();
            List<String> expected = new ArrayList<>();
            for (int line = 0; line < 6; line++) {
                StringBuilder value = new StringBuilder("line" + line + ':');
                for (char letter = 'A'; letter <= 'E'; letter++) {
                    if (random.nextBoolean()) {
                        value.append(letter);
                    }
                }
                String row = value.toString();
                if ((row.contains("A") && row.contains("B") && !row.contains("C"))
                        || (row.contains("D") && !row.contains("E"))) {
                    expected.add(row);
                }
                if (line > 0) {
                    text.append(line % 2 == 0 ? "\r\n" : "\u2028");
                }
                text.append(row);
            }
            assertLines(query, text.toString(), expected.toArray(new String[0]));
        }
    }

    /**
     * Checks that ordinary long source lines do not overflow the regex stack.
     * <p>Params: none.</p>
     * <p>Returns: normally for safe long-line matching.</p>
     */
    private static void longLineCases() {
        String longText = "x".repeat(20000);
        assertLines(query(condition(MatchType.TEXT, "needle", "", false, false)), longText + "needle", longText + "needle");
        assertLines(query(condition(MatchType.STRING_CONTAINS, "needle", "", false, false)),
                "\"" + longText + "needle\"", "\"" + longText + "needle\"");
        String escapedText = "\\a".repeat(8000);
        assertLines(query(condition(MatchType.STRING_CONTAINS, "needle", "", false, false)),
                "\"" + escapedText + "needle\"", "\"" + escapedText + "needle\"");
        String quotedText = "\"other\" + ".repeat(4000) + "\"needle\"";
        assertLines(query(condition(MatchType.STRING_CONTAINS, "needle", "", false, false)), quotedText, quotedText);
        assertLines(query(condition(MatchType.STRING_CONTAINS, "needle", "", false, false)),
                "\"" + escapedText + "\"");
        assertLines(query(condition(MatchType.STRING_CONTAINS, "needle", "", false, false)),
                "\"other\" + ".repeat(4000));
        assertLines(query(condition(MatchType.ORDERED, "A\nA\nA\nA\nB", "", false, true)), "A ".repeat(15000));
        String orderedText = "A ".repeat(15000) + "B";
        assertLines(query(condition(MatchType.ORDERED, "A\nA\nA\nA\nB", "", false, true)), orderedText, orderedText);
    }

    /**
     * Bounds adversarial regex tests so a regression cannot hang the build. The
     * worker is a daemon because Java Pattern does not honor thread interruption.
     * @param operation performance-sensitive assertions
     * <p>Returns: normally when all assertions finish within five seconds.</p>
     */
    private static void runWithTimeout(Runnable operation) {
        ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "query-performance-test");
            thread.setDaemon(true);
            return thread;
        });
        Future<?> result = worker.submit(operation);
        try {
            result.get(5, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Performance checks interrupted.", exception);
        } catch (ExecutionException exception) {
            throw new AssertionError("Performance checks failed.", exception.getCause());
        } catch (TimeoutException exception) {
            throw new AssertionError("Regex regression exceeded the five-second timeout.", exception);
        } finally {
            result.cancel(true);
            worker.shutdownNow();
        }
    }

    /**
     * Constructs a condition with concise test syntax.
     * @param type selected template
     * @param first main input
     * @param second secondary input
     * @param excluded negation option
     * @param wholeWord complete-word option
     * @return a condition ready to compose
     */
    private static SearchCondition condition(MatchType type, String first, String second, boolean excluded, boolean wholeWord) {
        return new SearchCondition(type, first, second, excluded, wholeWord);
    }

    /**
     * Creates one alternative containing all provided conditions.
     * @param conditions conditions ANDed together
     * @return a case-sensitive query
     */
    private static SearchQuery query(SearchCondition... conditions) {
        return new SearchQuery(Collections.singletonList(new ConditionGroup(Arrays.asList(conditions))), true);
    }

    /**
     * Compares complete native-regex matches to independently expected lines.
     * @param query generated query
     * @param input source text
     * @param expected expected matching lines in source order
     * <p>Returns: normally when actual and expected matches agree.</p>
     */
    private static void assertLines(SearchQuery query, String input, String... expected) {
        Matcher matcher = Pattern.compile(query.toSameLineRegex(), query.getRegexFlags()).matcher(input);
        List<String> actual = new ArrayList<>();
        while (matcher.find()) {
            actual.add(matcher.group());
        }
        check(actual.equals(Arrays.asList(expected)), "Expected " + Arrays.toString(expected) + ", actual " + actual
                + "; regex=" + query.toSameLineRegex() + "; input=" + input);
    }

    /**
     * Asserts that the operation rejects invalid state.
     * @param type expected exception type
     * @param operation operation under test
     * <p>Returns: normally if the expected exception was thrown.</p>
     */
    private static void expectThrows(Class<? extends Throwable> type, Runnable operation) {
        try {
            operation.run();
        } catch (Throwable exception) {
            check(type.isInstance(exception), "Unexpected exception: " + exception);
            return;
        }
        throw new AssertionError("Expected exception " + type.getName());
    }

    /**
     * Records one assertion and raises a failure when its predicate is false.
     * @param result predicate result
     * @param message failure details
     * <p>Returns: normally for a passing predicate.</p>
     */
    private static void check(boolean result, String message) {
        assertions++;
        if (!result) {
            throw new AssertionError(message);
        }
    }
}
