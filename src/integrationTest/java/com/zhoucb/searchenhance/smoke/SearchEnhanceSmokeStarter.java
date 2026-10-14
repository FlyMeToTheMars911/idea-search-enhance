package com.zhoucb.searchenhance.smoke;

import com.intellij.find.EditorSearchSession;
import com.intellij.find.FindManager;
import com.intellij.find.FindModel;
import com.intellij.find.FindResult;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.actionSystem.KeyboardShortcut;
import com.intellij.openapi.actionSystem.Shortcut;
import com.intellij.openapi.actionSystem.impl.SimpleDataContext;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationInfo;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ApplicationStarter;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.EditorKind;
import com.intellij.openapi.keymap.Keymap;
import com.intellij.openapi.keymap.KeymapManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.ui.DialogWrapper;
import com.zhoucb.searchenhance.action.CurrentFileSearchAction;
import com.zhoucb.searchenhance.action.ProjectSearchAction;
import com.zhoucb.searchenhance.ide.NativeActionSupport;
import com.zhoucb.searchenhance.ide.NativeSearchBridge;
import com.zhoucb.searchenhance.query.ConditionGroup;
import com.zhoucb.searchenhance.query.MatchType;
import com.zhoucb.searchenhance.query.SearchCondition;
import com.zhoucb.searchenhance.query.SearchQuery;
import com.zhoucb.searchenhance.ui.SearchEnhanceDialog;
import org.jetbrains.annotations.NotNull;

import javax.imageio.ImageIO;
import javax.swing.JComboBox;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Runs integration checks inside a separately launched IDEA 233 application.
 * Author: zhoucb50741
 * date: 2026-10-14
 */
public final class SearchEnhanceSmokeStarter implements ApplicationStarter {
    /** Stable command selected by the test-only extension descriptor. */
    private static final String COMMAND = "searchEnhanceSmoke";
    /** Report output is confined to the independent test sandbox. */
    private static final String REPORT_PROPERTY = "search.enhance.smoke.report";
    /** Expected complete scenario count, checked before touching the UI. */
    private static final int SCENARIO_COUNT = 14;

    /** Params: none. Returns: the test command name. */
    @Override
    public String getCommandName() {
        return COMMAND;
    }

    /** Params: none. Returns: false because real Swing dialogs are initialized. */
    @Override
    public boolean isHeadless() {
        return false;
    }

    /** Params: none. Returns: pooled-thread startup; Swing work is explicitly dispatched. */
    @Override
    public int getRequiredModality() {
        return NOT_IN_EDT;
    }

    /**
     * Params: args - the isolated IDEA command arguments.
     * Returns: nothing; writes a report and exits only this test application.
     */
    @Override
    public void main(@NotNull List<String> args) {
        Application application = ApplicationManager.getApplication();
        Path reportDirectory = Path.of(System.getProperty(REPORT_PROPERTY));
        List<String> report = new ArrayList<>();
        List<Throwable> failures = new ArrayList<>();
        report.add("# Search Enhance / IDEA 233 integration smoke test");
        report.add("");
        report.add("Runtime: " + ApplicationInfo.getInstance().getBuild().asString());
        report.add("Config: " + PathManager.getConfigPath());
        report.add("System: " + PathManager.getSystemPath());
        report.add("Plugins: " + PathManager.getPluginsPath());
        report.add("Log: " + PathManager.getLogPath());
        report.add("UI dialogs are initialized and painted offscreen; no modal dialog is shown.");
        report.add("");
        try {
            Files.createDirectories(reportDirectory);
            application.invokeAndWait(() -> runChecks(reportDirectory, report, failures));
        } catch (Throwable failure) {
            failures.add(failure);
        }
        for (Throwable failure : failures) {
            StringWriter stack = new StringWriter();
            failure.printStackTrace(new PrintWriter(stack));
            report.add("\n```text\n" + stack + "\n```");
        }
        boolean passed = failures.isEmpty();
        report.add("\nResult: " + (passed ? "PASS" : "FAIL"));
        try {
            Files.write(reportDirectory.resolve("smoke-report.md"), report, StandardCharsets.UTF_8);
            Files.writeString(reportDirectory.resolve("smoke-result.txt"), passed ? "PASS" : "FAIL",
                    StandardCharsets.UTF_8);
        } catch (Throwable reportFailure) {
            reportFailure.printStackTrace();
        } finally {
            application.exit(true, true, false, passed ? 0 : 1);
        }
    }

    /** Params: reportDirectory, report, failures - test outputs. Returns: nothing. */
    private static void runChecks(Path reportDirectory, List<String> report, List<Throwable> failures) {
        Project project = ProjectManager.getInstance().getDefaultProject();
        check("XML action overrides and native defaults", report, failures, () -> checkActions(report));
        check("Native FindManager AND / OR / NOT, boundaries and both directions", report, failures,
                () -> checkFindManager(project, report));
        EditorFactory factory = EditorFactory.getInstance();
        Editor editor = factory.createEditor(factory.createDocument("alpha beta\nalpha only\nbeta alpha\n"),
                project, EditorKind.MAIN_EDITOR);
        try {
            DataContext context = SimpleDataContext.builder()
                    .add(CommonDataKeys.PROJECT, project).add(CommonDataKeys.EDITOR, editor).build();
            check("All 14 Swing scenario editors initialize, validate and paint", report, failures,
                    () -> checkDialog(project, editor, context, reportDirectory, report));
            check("Native editor session reuse and FindNext / FindPrevious models", report, failures,
                    () -> checkBridge(project, editor, context));
        } finally {
            EditorSearchSession session = EditorSearchSession.get(editor);
            if (session != null) {
                session.close();
            }
            factory.releaseEditor(editor);
        }
    }

    /** Params: report - native keymap diagnostics. Returns: nothing. */
    private static void checkActions(List<String> report) {
        ActionManager manager = ActionManager.getInstance();
        AnAction find = manager.getAction("Find");
        AnAction findInPath = manager.getAction("FindInPath");
        require(find instanceof CurrentFileSearchAction, "Find override was not registered: " + find);
        require(findInPath instanceof ProjectSearchAction, "FindInPath override was not registered: " + findInPath);
        require(NativeActionSupport.original((CurrentFileSearchAction) find, false) != find,
                "Find must retain an independent native action");
        require(NativeActionSupport.original((ProjectSearchAction) findInPath, true) != findInPath,
                "FindInPath must retain an independent native action");
        Keymap keymap = KeymapManager.getInstance().getKeymap(KeymapManager.DEFAULT_IDEA_KEYMAP);
        require(keymap != null, "The IDEA default keymap did not load");
        require(hasShortcut(keymap, "Find", "ctrl F"), "Default Find Ctrl+F binding missing");
        require(hasShortcut(keymap, "FindInPath", "ctrl shift F"), "Default FindInPath Ctrl+Shift+F missing");
        report.add("Default keymap: " + keymap.getName());
        report.add("Find shortcuts: " + Arrays.toString(keymap.getShortcuts("Find")));
        report.add("FindInPath shortcuts: " + Arrays.toString(keymap.getShortcuts("FindInPath")));
        require(manager.getAction("FindNext") != null && manager.getAction("FindPrevious") != null,
                "Original next / previous actions did not load");
    }

    /** Params: keymap, actionId, stroke - expected mapping. Returns: whether it exists. */
    private static boolean hasShortcut(Keymap keymap, String actionId, String stroke) {
        KeyStroke expected = KeyStroke.getKeyStroke(stroke);
        for (Shortcut shortcut : keymap.getShortcuts(actionId)) {
            if (shortcut instanceof KeyboardShortcut keyboard && expected.equals(keyboard.getFirstKeyStroke())
                    && keyboard.getSecondKeyStroke() == null) {
                return true;
            }
        }
        return false;
    }

    /** Params: project, report - native search context and observed ranges. Returns: nothing. */
    private static void checkFindManager(Project project, List<String> report) {
        FindManager manager = FindManager.getInstance(project);
        SearchQuery conjunction = query(true, new ConditionGroup(List.of("alpha", "beta"), List.of("ignored")));
        String source = "alpha beta\r\nalpha only\r\nalpha beta ignored\r\nbeta alpha\u2028alpha beta";
        List<String> expected = List.of("alpha beta", "beta alpha", "alpha beta");
        require(matches(manager, source, conjunction).equals(expected), "AND/NOT must stay inside CRLF/Unicode lines");
        SearchQuery disjunction = query(true, new ConditionGroup(List.of("ERROR"), List.of()),
                new ConditionGroup(List.of("WARN"), List.of("ignored")));
        require(matches(manager, "ERROR first\nWARN second\nWARN ignored\nINFO none", disjunction)
                        .equals(List.of("ERROR first", "WARN second")), "OR groups must honor their own NOT clauses");
        SearchQuery literals = query(true, new ConditionGroup(List.of("user.getName()", "[value]"), List.of()));
        require(matches(manager, "user.getName() [value]\nuserXgetName value", literals)
                .equals(List.of("user.getName() [value]")), "Input punctuation must remain literal");
        SearchQuery unicode = query(false, new ConditionGroup(List.of("ä"), List.of()));
        require(matches(manager, "Ä\nother", unicode).equals(List.of("Ä")), "Native Unicode case matching failed");
        SearchQuery boundary = query(true, new ConditionGroup(List.of(
                new SearchCondition(MatchType.TEXT, "taskId", "", false, true))));
        require(matches(manager, "taskId\ntaskIdBackup\n变量taskId\n(taskId)", boundary)
                        .equals(List.of("taskId", "(taskId)")), "Unicode identifier boundaries failed");
        FindModel reverse = model(conjunction);
        reverse.setForward(false);
        // IDEA 233 backward search accepts matches ending strictly before the
        // offset. Keep EOF matching covered above and provide a trailing line
        // separator here to use the native backward API's strict boundary.
        FindResult strictBoundary = manager.findString(source, source.length(), reverse);
        require(strictBoundary.isStringFound()
                        && strictBoundary.getStartOffset() == source.indexOf("beta alpha"),
                "Native backward search must exclude a match ending exactly at its offset");
        report.add("Backward strict boundary: offset=" + source.length() + ", result=["
                + strictBoundary.getStartOffset() + "," + strictBoundary.getEndOffset()
                + "); EOF match=[" + source.lastIndexOf("alpha beta") + "," + source.length() + ")");
        String reverseSource = source + "\n";
        FindResult last = manager.findString(reverseSource, reverseSource.length(), reverse);
        require(last.isStringFound() && last.getStartOffset() == source.lastIndexOf("alpha beta"),
                "Native backward search must find the last matching line");
        FindResult previous = manager.findString(reverseSource, last.getStartOffset(), reverse);
        require(previous.isStringFound() && source.substring(previous.getStartOffset(), previous.getEndOffset())
                .equals("beta alpha"), "Native previous search must move to the preceding line");
        report.add("Backward with trailing separator: offset=" + reverseSource.length() + ", last=["
                + last.getStartOffset() + "," + last.getEndOffset() + "), previous=["
                + previous.getStartOffset() + "," + previous.getEndOffset() + ")");
        require(matches(manager, "alpha\r\nbeta\u2028alpha beta", conjunction).equals(List.of("alpha beta")),
                "Conditions must never match across adjacent lines");
    }

    /** Params: manager, text, query - real native search input. Returns: matching full lines. */
    private static List<String> matches(FindManager manager, String text, SearchQuery query) {
        FindModel model = model(query);
        List<String> found = new ArrayList<>();
        int offset = 0;
        while (offset <= text.length()) {
            FindResult result = manager.findString(text, offset, model);
            if (!result.isStringFound()) {
                break;
            }
            require(result.getStartOffset() >= offset, "Forward native search regressed its offset");
            found.add(text.substring(result.getStartOffset(), result.getEndOffset()));
            offset = result.getEndOffset() > result.getStartOffset()
                    ? result.getEndOffset() : result.getStartOffset() + 1;
        }
        return found;
    }

    /** Params: query - visual query. Returns: a native regular-expression search model. */
    private static FindModel model(SearchQuery query) {
        FindModel model = new FindModel();
        model.setStringToFind(query.toSameLineRegex());
        model.setRegularExpressions(true);
        model.setCaseSensitive(query.isCaseSensitive());
        model.setWholeWordsOnly(false);
        return model;
    }

    /** Params: caseSensitive, groups - alternatives. Returns: the compiled query input. */
    private static SearchQuery query(boolean caseSensitive, ConditionGroup... groups) {
        return new SearchQuery(List.of(groups), caseSensitive);
    }

    /** Params: project, editor, context, reportDirectory, report - UI test context. Returns: nothing. */
    private static void checkDialog(Project project, Editor editor, DataContext context,
                                    Path reportDirectory, List<String> report) throws Exception {
        SearchEnhanceDialog dialog = new SearchEnhanceDialog(project, editor, false, context, () -> { });
        try {
            Container content = dialog.getContentPane();
            Dimension preferred = content.getPreferredSize();
            require(preferred.width > 0 && preferred.height > 0, "Dialog has no preferred size");
            content.setSize(preferred);
            layout(content);
            List<?> editors = (List<?>) field(dialog, "editors");
            require(!editors.isEmpty(), "Dialog did not create its initial condition editor");
            Object row = editors.get(0);
            JComboBox<?> selector = (JComboBox<?>) field(row, "scenario");
            JTextArea first = (JTextArea) field(row, "first");
            JTextArea second = (JTextArea) field(row, "second");
            JTextArea preview = (JTextArea) field(dialog, "regexPreview");
            require(selector.getItemCount() == SCENARIO_COUNT, "Expected 14 scenario templates");
            for (int index = 0; index < selector.getItemCount(); index++) {
                selector.setSelectedIndex(index);
                first.setText(index == MatchType.DIGITS.ordinal() ? "2" : "alpha");
                second.setText(index == MatchType.DIGITS.ordinal() ? "5" : "beta");
                require(dialog.isOKActionEnabled(), "Scenario did not validate: " + selector.getSelectedItem());
                require(!preview.getText().isBlank(), "Scenario generated an empty preview");
                layout(content);
                paint(content, reportDirectory.resolve(String.format("scenario-%02d.png", index + 1)));
                report.add("UI " + (index + 1) + ": " + selector.getSelectedItem());
            }
        } finally {
            dialog.close(DialogWrapper.CANCEL_EXIT_CODE);
        }
    }

    /** Params: project, editor, context - native editor integration. Returns: nothing. */
    private static void checkBridge(Project project, Editor editor, DataContext context) {
        SearchQuery firstQuery = query(true, new ConditionGroup(List.of("alpha", "beta"), List.of()));
        NativeSearchBridge.open(project, editor, false, context, firstQuery.toSameLineRegex(), true);
        EditorSearchSession firstSession = EditorSearchSession.get(editor);
        require(firstSession != null, "Native editor search header was not installed");
        require(firstSession.getFindModel().getStringToFind().equals(firstQuery.toSameLineRegex()),
                "Native editor header did not receive the generated query");
        SearchQuery secondQuery = query(false, new ConditionGroup(List.of("alpha"), List.of("beta")));
        NativeSearchBridge.open(project, editor, false, context, secondQuery.toSameLineRegex(), false);
        require(EditorSearchSession.get(editor) == firstSession, "Existing native sessions must be reused");
        FindManager manager = FindManager.getInstance(project);
        require(manager.findWasPerformed(), "Native find-was-performed state was not set");
        require(manager.getFindNextModel().getStringToFind().equals(secondQuery.toSameLineRegex()),
                "FindNext model did not receive the updated conditions");
        require(manager.getFindInFileModel().getStringToFind().equals(secondQuery.toSameLineRegex()),
                "Native find-in-file state was not updated");
        require(!manager.getFindNextModel().isCaseSensitive(), "Native case flag was not updated");
        FindResult result = manager.findString(editor.getDocument().getCharsSequence(), 0,
                manager.getFindNextModel());
        require(result.isStringFound() && editor.getDocument().getText()
                .substring(result.getStartOffset(), result.getEndOffset()).equals("alpha only"),
                "Native next model did not execute the new conditions");
    }

    /** Params: owner, name - test-inspected private UI state. Returns: that field value. */
    private static Object field(Object owner, String name) throws ReflectiveOperationException {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    /** Params: container - offscreen Swing tree. Returns: nothing; applies nested layout. */
    private static void layout(Container container) {
        container.doLayout();
        for (Component component : container.getComponents()) {
            if (component instanceof Container child) {
                layout(child);
            }
        }
    }

    /** Params: content, output - rendered Swing tree and PNG path. Returns: nothing. */
    private static void paint(Container content, Path output) throws Exception {
        BufferedImage image = new BufferedImage(content.getWidth(), content.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            content.printAll(graphics);
        } finally {
            graphics.dispose();
        }
        ImageIO.write(image, "png", output.toFile());
    }

    /** Params: name, report, failures, operation - one independent check. Returns: nothing. */
    private static void check(String name, List<String> report, List<Throwable> failures,
                              CheckedOperation operation) {
        try {
            operation.run();
            report.add("PASS: " + name);
        } catch (Throwable failure) {
            report.add("FAIL: " + name + ": " + failure);
            failures.add(failure);
        }
    }

    /** Params: condition, message - test assertion. Returns: nothing unless it fails. */
    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    /**
     * One integration check whose platform API calls may throw checked exceptions.
     * Author: zhoucb50741
     * date: 2026-10-14
     */
    @FunctionalInterface
    private interface CheckedOperation {
        /** Params: none. Returns: nothing; propagates a failed platform check. */
        void run() throws Exception;
    }
}
