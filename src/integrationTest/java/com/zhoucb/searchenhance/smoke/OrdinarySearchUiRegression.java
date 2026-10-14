package com.zhoucb.searchenhance.smoke;

import com.intellij.find.EditorSearchSession;
import com.intellij.find.FindManager;
import com.intellij.find.impl.FindPopupPanel;
import com.intellij.ide.DataManager;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys;
import com.intellij.openapi.actionSystem.impl.SimpleDataContext;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.EditorKind;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ex.ProjectManagerEx;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.WindowManager;
import com.intellij.openapi.wm.impl.IdeFrameImpl;
import com.intellij.openapi.wm.impl.ProjectFrameHelper;
import com.intellij.openapi.wm.impl.WindowManagerImpl;
import com.zhoucb.searchenhance.ui.SearchEnhanceDialog;

import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.Timer;
import java.awt.Component;
import java.awt.Container;
import java.awt.Window;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import kotlin.Unit;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlinx.coroutines.BuildersKt;

/**
 * Exercises actual modal ordinary-search buttons through registered actions.
 * Author: zhoucb50741
 * date: 2026-10-14
 */
final class OrdinarySearchUiRegression {
    /** Exact visible fallback command, intentionally located in the Swing tree. */
    private static final String ORDINARY_BUTTON = "普通搜索…";
    /** Timer cadence permits a real modal event loop without arbitrary sleeps. */
    private static final int POLL_MILLIS = 100;
    /** One action may not hold the integration process indefinitely. */
    private static final int TIMEOUT_MILLIS = 6000;
    /** Owner application project; never the user's existing IDEA project. */
    private final Project project;
    /** Small real file opened by IDEA's native file editor manager. */
    private final VirtualFile sample;
    /** Per-run reports shared only through sequential EDT dispatch. */
    private final List<String> report;
    /** A test-owned real Swing frame avoids unrelated full-project startup flows. */
    private JFrame frame;
    /** Real IDEA frame registration without opening project startup activities. */
    private ProjectFrameHelper frameHelper;
    /** Only editors created by this fixture are released by it. */
    private Editor ownedEditor;

    /** Params: project, sample, report - isolated platform fixture. Returns: a regression fixture. */
    private OrdinarySearchUiRegression(Project project, VirtualFile sample, List<String> report) {
        this.project = project;
        this.sample = sample;
        this.report = report;
    }

    /**
     * Params: directory, report, failures - run-local evidence destinations.
     * Returns: nothing; creates and closes only this test's own frame/editors.
     */
    static void run(Path directory, List<String> report, List<Throwable> failures) {
        Project project = null;
        AtomicReference<OrdinarySearchUiRegression> ownedFixture = new AtomicReference<>();
        try {
            Path fixtureDirectory = directory.resolve("ordinary-search-project");
            Files.createDirectories(fixtureDirectory);
            Path samplePath = fixtureDirectory.resolve("sample.txt");
            Files.writeString(samplePath, "alpha beta\nalpha only\nbeta alpha\n", StandardCharsets.UTF_8);
            project = ProjectManagerEx.getInstanceEx().newProject("Search Enhance UI Regression",
                    fixtureDirectory.toString(), false, false);
            require(project != null && !project.isDefault(), "A test-owned project must be created");
            VirtualFile sample = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(samplePath);
            require(sample != null, "Temporary sample file must be visible in IDEA's VFS");
            OrdinarySearchUiRegression regression = new OrdinarySearchUiRegression(project, sample, report);
            ownedFixture.set(regression);
            ApplicationManager.getApplication().invokeAndWait(() -> {
                IdeFrameImpl ideaFrame = new IdeFrameImpl();
                regression.frameHelper = new ProjectFrameHelper(ideaFrame);
                regression.frame = regression.frameHelper.init();
                regression.frame.setSize(1050, 700);
                DataManager.registerDataProvider(regression.frame.getRootPane(), id ->
                        CommonDataKeys.PROJECT.is(id) ? regression.project : null);
            });
            BuildersKt.<Unit>runBlocking(EmptyCoroutineContext.INSTANCE, (scope, continuation) ->
                    regression.frameHelper.setRawProject$intellij_platform_ide_impl(regression.project, continuation));
            BuildersKt.<Unit>runBlocking(EmptyCoroutineContext.INSTANCE, (scope, continuation) ->
                    ((WindowManagerImpl) WindowManager.getInstance()).assignFrame(
                            regression.frameHelper, regression.project, continuation));
            ApplicationManager.getApplication().invokeAndWait(() -> regression.frame.setVisible(true));
            String currentFileId = SearchEnhanceSmokeStarter.isLegacyBaseline()
                    ? "Find" : "SearchEnhance.FindInCurrentFile";
            regression.check("Current-file/editor ordinary-search button", currentFileId, true, false, failures);
            regression.check("Tools/editor ordinary-search button", "SearchEnhance.EditConditions", true,
                    false, failures);
            regression.check("FindInPath/editor ordinary-search button", "FindInPath", true, true, failures);
            regression.check("FindInPath/project-tree without editor", "FindInPath", false, true, failures);
            regression.check("Tools/project-tree without editor", "SearchEnhance.EditConditions", false,
                    true, failures);
        } catch (Throwable failure) {
            report.add("FAIL: ordinary-search UI fixture: " + failure);
            failures.add(failure);
        } finally {
            OrdinarySearchUiRegression regression = ownedFixture.get();
            if (regression != null) {
                ApplicationManager.getApplication().invokeAndWait(() -> {
                    regression.clearNativeUi();
                    regression.releaseEditor();
                    if (regression.frameHelper != null) {
                        ((WindowManagerImpl) WindowManager.getInstance()).releaseFrame(regression.frameHelper);
                    }
                    if (regression.frame != null) {
                        regression.frame.dispose();
                    }
                    if (!regression.project.isDisposed()) {
                        ApplicationManager.getApplication().runWriteAction(() -> Disposer.dispose(regression.project));
                    }
                });
            }
        }
    }

    /** Params: name, id, editorContext, projectSearch, failures - action case. Returns: its recorded result. */
    private void check(String name, String id, boolean editorContext, boolean projectSearch,
                       List<Throwable> failures) {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<EditorSearchSession> observedSession = new AtomicReference<>();
        AtomicReference<SearchEnhanceDialog> observedDialog = new AtomicReference<>();
        AtomicBoolean clicked = new AtomicBoolean();
        AtomicBoolean observedPopup = new AtomicBoolean();
        AtomicBoolean observedFindUi = new AtomicBoolean();
        AtomicBoolean completed = new AtomicBoolean();
        AtomicReference<Timer> pollReference = new AtomicReference<>();
        long started = System.nanoTime();
        try {
            ApplicationManager.getApplication().invokeAndWait(() -> {
                FileEditorManager files = FileEditorManager.getInstance(project);
                clearNativeUi();
                for (VirtualFile open : files.getOpenFiles()) {
                    files.closeFile(open);
                }
                releaseEditor();
                Editor editor = editorContext ? EditorFactory.getInstance().createEditor(
                        EditorFactory.getInstance().createDocument("alpha beta\nalpha only\nbeta alpha\n"),
                        project, EditorKind.MAIN_EDITOR) : null;
                ownedEditor = editor;
                frame.getContentPane().removeAll();
                if (editor != null) {
                    frame.getContentPane().add(editor.getComponent());
                }
                frame.getContentPane().validate();
                frame.toFront();
                require(!editorContext || editor != null, "Native file editor must open for " + name);
                require(editorContext || files.getSelectedTextEditor() == null,
                        "Project-tree case must have no selected editor");
                Component component = editor == null ? frame.getRootPane() : editor.getContentComponent();
                require(component != null && component.isShowing(), "Real project component must show");
                DataContext context = SimpleDataContext.builder()
                        .add(CommonDataKeys.PROJECT, project)
                        .add(CommonDataKeys.EDITOR, editor)
                        .add(CommonDataKeys.VIRTUAL_FILE, editor == null ? sample.getParent() : sample)
                        .add(PlatformCoreDataKeys.CONTEXT_COMPONENT, component).build();
                AnAction action = ActionManager.getInstance().getAction(id);
                require(action != null, "Registered action missing: " + id);
                Timer poll = new Timer(POLL_MILLIS, event -> {
                    try {
                        SearchEnhanceDialog dialog = findEnhancedDialog();
                        if (dialog != null) {
                            observedDialog.set(dialog);
                            if (!clicked.get()) {
                                JButton button = findButton(dialog.getContentPane(), ORDINARY_BUTTON);
                                require(button != null && button.isShowing() && button.isEnabled(),
                                        "Ordinary search button must be visible and enabled");
                                clicked.set(true);
                                report.add("CLICK: " + name + "; dialog.modal=" + dialog.isModal());
                                button.doClick();
                            }
                        }
                        if (clicked.get()) {
                            if (editor != null) {
                                observedSession.set(EditorSearchSession.get(editor));
                            }
                            observedPopup.set(findNativePopup() != null);
                            observedFindUi.set(nativeFindUi() instanceof FindPopupPanel);
                            boolean nativeVisible = projectSearch ? observedFindUi.get()
                                    : observedSession.get() != null
                                    && observedSession.get().getComponent().isShowing();
                            if (nativeVisible) {
                                SearchEnhanceDialog closed = observedDialog.get();
                                require(closed != null && !closed.isShowing(),
                                        "Enhanced dialog must close before ordinary search opens");
                                completed.set(true);
                            }
                        }
                        long elapsed = (System.nanoTime() - started) / 1_000_000;
                        if (!completed.get() && elapsed >= TIMEOUT_MILLIS) {
                            throw new AssertionError(name + " timed out: clicked=" + clicked.get()
                                    + ", editorSession=" + (observedSession.get() != null)
                                    + ", nativeFindUi=" + observedFindUi.get()
                                    + ", nativePopup=" + observedPopup.get());
                        }
                    } catch (Throwable problem) {
                        failure.compareAndSet(null, problem);
                        completed.set(true);
                    }
                    if (completed.get()) {
                        ((Timer) event.getSource()).stop();
                        SearchEnhanceDialog remaining = observedDialog.get();
                        if (remaining != null && remaining.isShowing()) {
                            remaining.close(DialogWrapper.CANCEL_EXIT_CODE);
                        }
                    }
                });
                pollReference.set(poll);
                poll.start();
                action.actionPerformed(AnActionEvent.createFromAnAction(action, null,
                        editorContext ? "EditorPopup" : "ProjectViewPopup", context));
            });
            // The modal action returns after closing. A second Swing nested loop
            // lets the production invokeLater fallback execute and be observed.
            while (!completed.get()) {
                if ((System.nanoTime() - started) / 1_000_000 >= TIMEOUT_MILLIS + POLL_MILLIS * 5L) {
                    throw new AssertionError("UI timer did not complete for " + name);
                }
                Thread.sleep(POLL_MILLIS);
            }
            if (failure.get() != null) {
                throw failure.get();
            }
            report.add("PASS: " + name + "; clicked=" + clicked.get()
                    + ", native=" + (projectSearch ? "FindPopupPanel" : "EditorSearchSession")
                    + ", enhancedDialogClosed=true");
            if (projectSearch && !observedPopup.get()) {
                report.add("LIMITATION: " + name + "; the native FindPopupPanel was constructed by the real"
                        + " action, but visibility was not verified in the isolated unopened-project fixture.");
            }
        } catch (Throwable problem) {
            report.add("FAIL: " + name + ": " + problem);
            failures.add(problem);
        } finally {
            ApplicationManager.getApplication().invokeAndWait(() -> {
                Timer poll = pollReference.get();
                if (poll != null) {
                    poll.stop();
                }
                clearNativeUi();
                SearchEnhanceDialog dialog = observedDialog.get();
                if (dialog != null && dialog.isShowing()) {
                    dialog.close(DialogWrapper.CANCEL_EXIT_CODE);
                }
            });
        }
    }

    /** Params: none. Returns: the native helper's actual FindUI object, never a test replacement. */
    private Object nativeFindUi() {
        Object helper = nativeHelper();
        return helper == null ? null : readField(helper, "myUI");
    }

    /** Params: none. Returns: the native project-find helper created by this fixture. */
    private Object nativeHelper() {
        return readField(FindManager.getInstance(project), "myHelper");
    }

    /** Params: none. Returns: nothing; clears only this sandbox fixture's prior native find UI. */
    private void clearNativeUi() {
        FindManager.getInstance(project).closeFindDialog();
        Object helper = nativeHelper();
        if (helper instanceof Disposable disposable && !Disposer.isDisposed(disposable)) {
            Disposer.dispose(disposable);
        }
    }

    /** Params: owner, name - pinned IDEA 233 native field. Returns: the observed platform object. */
    private static Object readField(Object owner, String name) {
        try {
            Field field = owner.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(owner);
        } catch (ReflectiveOperationException problem) {
            throw new IllegalStateException("Cannot inspect IDEA 233 native field " + name, problem);
        }
    }

    /** Params: none. Returns: nothing; releases only this fixture's own main editor. */
    private void releaseEditor() {
        if (ownedEditor != null && !ownedEditor.isDisposed()) {
            EditorSearchSession session = EditorSearchSession.get(ownedEditor);
            if (session != null) {
                session.close();
            }
            EditorFactory.getInstance().releaseEditor(ownedEditor);
            ownedEditor = null;
        }
    }

    /** Params: none. Returns: the real enhanced modal dialog, if displayed. */
    private static SearchEnhanceDialog findEnhancedDialog() {
        for (Window window : Window.getWindows()) {
            if (window.isShowing()) {
                DialogWrapper wrapper = DialogWrapper.findInstance(window);
                if (wrapper instanceof SearchEnhanceDialog dialog) {
                    return dialog;
                }
            }
        }
        return null;
    }

    /** Params: none. Returns: IDEA's visible native project search component. */
    private static FindPopupPanel findNativePopup() {
        for (Window window : Window.getWindows()) {
            FindPopupPanel popup = findPopup(window);
            if (popup != null && popup.isShowing()) {
                return popup;
            }
        }
        return null;
    }

    /** Params: component - current Swing subtree. Returns: a visible native find panel. */
    private static FindPopupPanel findPopup(Component component) {
        if (component instanceof FindPopupPanel popup && popup.isShowing()) {
            return popup;
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                FindPopupPanel found = findPopup(child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** Params: component, text - Swing subtree and exact button caption. Returns: that button. */
    private static JButton findButton(Component component, String text) {
        if (component instanceof JButton button && text.equals(button.getText())) {
            return button;
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                JButton found = findButton(child, text);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** Params: condition, message - UI assertion. Returns: nothing unless it fails. */
    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
