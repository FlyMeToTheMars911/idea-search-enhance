package com.zhoucb.searchenhance.smoke;

import com.intellij.find.FindManager;
import com.intellij.find.FindModel;
import com.intellij.find.FindSettings;
import com.intellij.find.impl.FindPopupPanel;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ConfigurableEP;
import com.zhoucb.searchenhance.settings.SearchEnhanceConfigurable;
import com.zhoucb.searchenhance.settings.SearchEnhancePreferences;
import com.zhoucb.searchenhance.ui.SearchEnhanceDialog;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.imageio.ImageIO;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.List;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Exercises configurable native-first entry and actual native/visual round trips.
 * Author: zhoucb50741
 * date: 2026-10-14
 */
final class NativeFirstUiRegression {
    /** Actual user-facing command, not a replacement test action. */
    private static final String ENHANCE_BUTTON = "增强搜索…";
    /** Ordinary native text includes punctuation that must remain unchanged. */
    private static final String NATIVE_INPUT = "native.user.input + [value]";
    /** One complete modal round trip has a bounded lifetime. */
    private static final int TIMEOUT_MILLIS = 15000;
    /** Swing observation interval. */
    private static final int POLL_MILLIS = 100;
    /** Timer stage: native window before the enhanced command. */
    private static final int NATIVE_STAGE = 0;
    /** Timer stage: real enhanced modal dialog. */
    private static final int ENHANCED_STAGE = 1;
    /** Timer stage: native window after the enhanced dialog closes. */
    private static final int RETURN_STAGE = 2;
    /** Existing isolated project and frame fixture. */
    private final OrdinarySearchUiRegression fixture;
    /** Current run's evidence lines. */
    private final List<String> report;
    /** Images are confined to this integration run's report directory. */
    private final Path reportDirectory;

    /** Params: fixture, report - real platform fixture and output. Returns: a native-first regression fixture. */
    private NativeFirstUiRegression(OrdinarySearchUiRegression fixture, Path reportDirectory, List<String> report) {
        this.fixture = fixture;
        this.reportDirectory = reportDirectory;
        this.report = report;
    }

    /** Params: fixture, report, failures - integration outputs. Returns: nothing; restores global preferences. */
    static void run(OrdinarySearchUiRegression fixture, Path reportDirectory,
                    List<String> report, List<Throwable> failures) {
        NativeFirstUiRegression regression = new NativeFirstUiRegression(fixture, reportDirectory, report);
        SearchEnhancePreferences preferences = SearchEnhancePreferences.getInstance();
        boolean nativeFirst = preferences.getState().nativeFirst;
        boolean showButton = preferences.getState().showNativeButton;
        try {
            regression.check("Settings defaults, reset, apply and global model", failures,
                    regression::checkSettings);
            regression.check("Default native-first -> enhance -> OK preserves native settings", failures,
                    () -> regression.roundTrip(Completion.APPLY));
            regression.check("Native-first -> enhance -> ordinary preserves latest native input", failures,
                    () -> regression.roundTrip(Completion.ORDINARY));
            regression.check("Native-first -> enhance -> cancel restores popup close state and input", failures,
                    () -> regression.roundTrip(Completion.CANCEL));
            regression.check("Repeated native opens and action invocations never duplicate enhancement button",
                    failures, regression::checkRepeatedOpens);
            regression.check("Hide-button preference leaves native Find in Files without enhancement button",
                    failures, regression::checkHiddenButton);
        } finally {
            preferences.getState().nativeFirst = nativeFirst;
            preferences.getState().showNativeButton = showButton;
            ui(fixture::clearNativeUi);
        }
    }

    /** Params: none. Returns: nothing; changes the actual settings controls and applies their model. */
    private void checkSettings() {
        ui(() -> {
            SearchEnhancePreferences preferences = SearchEnhancePreferences.getInstance();
            require(preferences.getState().nativeFirst && preferences.getState().showNativeButton,
                    "Fresh application defaults must be native-first with an enhancement button");
            ConfigurableEP<Configurable> registered = Configurable.APPLICATION_CONFIGURABLE.getExtensionList()
                    .stream().filter(extension -> "search.enhance.preferences".equals(extension.id))
                    .findFirst().orElseThrow(() -> new AssertionError("Settings extension was not registered"));
            require("tools".equals(registered.parentId), "Settings extension must be registered under Tools");
            Configurable created = registered.createConfigurable();
            require(created instanceof SearchEnhanceConfigurable, "Registered Settings page cannot be instantiated");
            SearchEnhanceConfigurable configurable = (SearchEnhanceConfigurable) created;
            report.add("Settings EP: id=" + registered.id + ", parentId=" + registered.parentId
                    + ", implementation=" + created.getClass().getName());
            try {
                require("search.enhance.preferences".equals(configurable.getId()), "Settings ID changed");
                Container settings = configurable.createComponent();
                require(settings != null, "Settings component did not initialize");
                configurable.reset();
                Dimension size = settings.getPreferredSize();
                settings.setSize(size);
                layout(settings);
                paint(settings, reportDirectory.resolve("settings-search-enhance.png"));
                require(!configurable.isModified(), "Reset settings must initially be unmodified");
                JComboBox<?> mode = (JComboBox<?>) read(configurable, "entryMode");
                JCheckBox showButton = (JCheckBox) read(configurable, "showNativeButton");
                require(mode.getSelectedIndex() == NATIVE_STAGE && showButton.isSelected(),
                        "Settings UI did not render fresh defaults");
                mode.setSelectedIndex(ENHANCED_STAGE);
                showButton.doClick();
                require(configurable.isModified(), "Changed settings controls must be modified");
                configurable.apply();
                require(!preferences.getState().nativeFirst && !preferences.getState().showNativeButton,
                        "Apply must update the application-wide model");
                require(!configurable.isModified(), "Applied settings must become unmodified");
                preferences.getState().nativeFirst = true;
                preferences.getState().showNativeButton = true;
                configurable.reset();
                require(mode.getSelectedIndex() == NATIVE_STAGE && showButton.isSelected(),
                        "Reset must read the latest global model");
            } finally {
                preferences.getState().nativeFirst = true;
                preferences.getState().showNativeButton = true;
                configurable.disposeUIResources();
            }
        });
    }

    /** Params: accept - whether to apply enhanced conditions or return through the ordinary button. Returns: nothing. */
    private void roundTrip(Completion completion) throws Exception {
        boolean accept = completion == Completion.APPLY;
        String fileMask = "*.search-enhance-" + Long.toUnsignedString(System.nanoTime()) + ".txt";
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean done = new AtomicBoolean();
        AtomicInteger stage = new AtomicInteger(NATIVE_STAGE);
        AtomicReference<Timer> timer = new AtomicReference<>();
        AtomicReference<String> expectedRegex = new AtomicReference<>();
        AtomicReference<String> expectedDirectory = new AtomicReference<>();
        AtomicReference<SearchEnhanceDialog> enhancedDialog = new AtomicReference<>();
        AtomicReference<FindPopupPanel> originalPopup = new AtomicReference<>();
        AtomicReference<FindModel> originalModel = new AtomicReference<>();
        AtomicBoolean originalCanClose = new AtomicBoolean();
        long started = System.nanoTime();
        try {
            ui(() -> {
                preferences(true, true);
                DataContext context = fixture.prepareContext(false);
                String directory = CommonDataKeys.VIRTUAL_FILE.getData(context).getPath();
                expectedDirectory.set(directory);
                FindModel seed = FindManager.getInstance(fixture.project()).getFindInProjectModel();
                FindSettings.getInstance().setFileMask("*.old-native-mask");
                seed.setStringToFind("initial native text");
                seed.setRegularExpressions(false);
                seed.setCaseSensitive(false);
                seed.setWholeWordsOnly(false);
                seed.setProjectScope(false);
                seed.setDirectoryName(directory);
                seed.setWithSubdirectories(false);
                seed.setFileFilter("*.java");
                Timer poll = new Timer(POLL_MILLIS, event -> {
                    try {
                        FindPopupPanel popup = OrdinarySearchUiRegression.findNativePopup();
                        SearchEnhanceDialog dialog = OrdinarySearchUiRegression.findEnhancedDialog();
                        if (stage.get() == NATIVE_STAGE && popup != null) {
                            require(dialog == null, "Default action must show native Find in Files first");
                            int buttons = countButtons(popup, ENHANCE_BUTTON);
                            require(buttons <= 1, "Native panel contains duplicate enhancement buttons");
                            if (buttons == 1) {
                                require(!Arrays.asList(FindSettings.getInstance().getRecentFileMasks()).contains(fileMask),
                                        "The edited mask must be new, not a history entry");
                                originalPopup.set(popup);
                                originalModel.set(popup.getHelper().getModel());
                                originalCanClose.set(popup.getCanClose().get());
                                checkLayout(popup);
                                if (accept) {
                                    paint(popup, reportDirectory.resolve("native-first-popup.png"));
                                }
                                changeNativeControls(popup, fileMask);
                                stage.set(ENHANCED_STAGE);
                                JButton button = OrdinarySearchUiRegression.findButton(popup, ENHANCE_BUTTON);
                                require(button.isShowing(), "Native enhancement command must be visible");
                                report.add("CLICK: native enhancement command; completion=" + completion);
                                SwingUtilities.invokeLater(button::doClick);
                            }
                        } else if (stage.get() == ENHANCED_STAGE && dialog != null) {
                            require(dialog.isModal(), "Enhanced conditions must use the actual modal dialog");
                            require(!originalPopup.get().getCanClose().get(),
                                    "Native popup must remain pinned while enhanced conditions are modal");
                            enhancedDialog.set(dialog);
                            if (accept) {
                                List<?> rows = (List<?>) read(dialog, "editors");
                                require(!rows.isEmpty(), "Enhanced editor must contain a condition row");
                                ((JTextArea) read(rows.get(0), "first")).setText("alpha");
                                require(dialog.isOKActionEnabled(), "Enhanced conditions must validate before OK");
                                expectedRegex.set(((JTextArea) read(dialog, "regexPreview")).getText());
                            }
                            String caption = completion == Completion.ORDINARY ? "普通搜索…" : "取消";
                            JButton button = accept ? dialog.getRootPane().getDefaultButton()
                                    : OrdinarySearchUiRegression.findButton(dialog.getContentPane(), caption);
                            require(button != null && button.isShowing(), "Enhanced completion button missing: " + completion);
                            stage.set(RETURN_STAGE);
                            SwingUtilities.invokeLater(button::doClick);
                        } else if (stage.get() == RETURN_STAGE && popup != null) {
                            require(!enhancedDialog.get().isShowing(), "Enhanced dialog must close before native return");
                            require(popup == originalPopup.get(), "Native popup identity must survive enhanced editing");
                            require(popup.getCanClose().get() == originalCanClose.get(),
                                    "Native popup close state was not restored");
                            FindModel model = popup.getHelper().getModel();
                            require(model == originalModel.get(), "Native model identity must remain valid for its OK handler");
                            FindModel saved = FindManager.getInstance(fixture.project()).getFindInProjectModel();
                            FindModel options = accept ? model : saved;
                            // Native saveSettings copies current controls to the
                            // global model. Its preview model may update later
                            // after Cancel, so inspect user controls and saved
                            // options rather than requiring eager preview work.
                            require(((AtomicBoolean) read(popup, "myCaseSensitiveState")).get(),
                                    "Native case control must remain enabled after the round trip");
                            require(options.isCaseSensitive(), "Native case preference must survive the round trip");
                            require(fileMask.equals(options.getFileFilter()) && fileMask.equals(popup.getFileTypeMask()),
                                    "Latest native file mask was lost");
                            require(samePath(expectedDirectory.get(), options.getDirectoryName()), "Native directory scope was lost");
                            require(!options.isWithSubdirectories(), "Native nonrecursive scope preference was lost");
                            require(countButtons(popup, ENHANCE_BUTTON) == 1, "Returned native UI must have one enhancement button");
                            if (accept) {
                                require(model.isRegularExpressions(), "Enhanced OK must enable native regexp mode");
                                require(expectedRegex.get().equals(popup.getStringToFind()), "Generated query did not reach native search");
                            } else {
                                require(!((AtomicBoolean) read(popup, "myRegexState")).get()
                                                && !saved.isRegularExpressions(),
                                        "Ordinary return must retain native regexp preference");
                                require(NATIVE_INPUT.equals(popup.getStringToFind()), "Ordinary return changed latest native input");
                            }
                            done.set(true);
                        }
                        if (!done.get() && elapsedMillis(started) >= TIMEOUT_MILLIS) {
                            throw new AssertionError("Native-first round trip timed out at stage " + stage.get());
                        }
                    } catch (Throwable problem) {
                        failure.compareAndSet(null, problem);
                        done.set(true);
                    }
                    if (done.get()) {
                        ((Timer) event.getSource()).stop();
                        SearchEnhanceDialog remaining = OrdinarySearchUiRegression.findEnhancedDialog();
                        if (remaining != null) {
                            remaining.close(DialogWrapper.CANCEL_EXIT_CODE);
                        }
                    }
                });
                timer.set(poll);
                poll.start();
                invokeProjectAction(context);
            });
            while (!done.get() && elapsedMillis(started) <= TIMEOUT_MILLIS + POLL_MILLIS * 5L) {
                Thread.sleep(POLL_MILLIS);
            }
            require(done.get(), "Native-first timer did not complete");
            if (failure.get() != null) {
                throw new AssertionError("Native-first round trip failed", failure.get());
            }
        } finally {
            ui(() -> {
                if (timer.get() != null) {
                    timer.get().stop();
                }
                fixture.clearNativeUi();
            });
        }
    }

    /** Params: popup - native search panel after insertion. Returns: nothing; checks important controls remain usable. */
    private static void checkLayout(FindPopupPanel popup) {
        Component row = (Component) popup.getClientProperty("SearchEnhance.NativeButtonRow");
        Component search = (Component) read(popup, "mySearchComponent");
        Component preview = (Component) read(popup, "myPreviewSplitter");
        Component nativeOk = (Component) read(popup, "myOKButton");
        require(row != null && row.isShowing() && row.getHeight() > 0, "Enhancement row must be visible");
        require(search.isShowing() && search.getWidth() > 200 && search.getHeight() > 0,
                "Native search input must remain usable");
        require(preview.isShowing() && preview.getWidth() > 200 && preview.getHeight() > 100,
                "Native result and preview area must remain usable");
        require(nativeOk.isShowing() && nativeOk.getWidth() > 0 && nativeOk.getHeight() > 0,
                "Native search result command must remain visible");
        Rectangle rowBounds = SwingUtilities.convertRectangle(row.getParent(), row.getBounds(), popup);
        Rectangle previewBounds = SwingUtilities.convertRectangle(preview.getParent(), preview.getBounds(), popup);
        Rectangle searchBounds = SwingUtilities.convertRectangle(search.getParent(), search.getBounds(), popup);
        require(!rowBounds.intersects(previewBounds) && !rowBounds.intersects(searchBounds),
                "Enhancement command must not overlap native search or results controls");
    }

    /** Params: container - settings tree before offscreen capture. Returns: nothing; assigns nested component bounds. */
    private static void layout(Container container) {
        container.doLayout();
        for (Component child : container.getComponents()) {
            if (child instanceof Container nested) { layout(nested); }
        }
    }

    /** Params: content, output - native Swing UI and run-local screenshot. Returns: nothing; stores visual evidence. */
    private static void paint(Container content, Path output) throws Exception {
        BufferedImage image = new BufferedImage(content.getWidth(), content.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try { content.printAll(graphics); } finally { graphics.dispose(); }
        ImageIO.write(image, "png", output.toFile());
    }

    /** Params: popup - actual native controls. Returns: nothing; edits unsaved user state before enhancement. */
    private static void changeNativeControls(FindPopupPanel popup, String fileMask) {
        ((JTextArea) read(popup, "mySearchComponent")).setText(NATIVE_INPUT);
        ((AtomicBoolean) read(popup, "myCaseSensitiveState")).set(true);
        ((AtomicBoolean) read(popup, "myRegexState")).set(false);
        Object header = read(popup, "header");
        ((JCheckBox) read(header, "cbFileFilter")).setSelected(true);
        ((JComboBox<?>) read(header, "fileMaskField")).setSelectedItem(fileMask);
        // The production enhancement command must capture these edits itself.
        // Calling saveSettings here would mask a missing native snapshot.
    }

    /** Params: none. Returns: nothing; verifies repeated real action invocations and closes. */
    private void checkRepeatedOpens() throws Exception {
        AtomicReference<DataContext> context = new AtomicReference<>();
        ui(() -> {
            preferences(true, true);
            context.set(fixture.prepareContext(false));
        });
        for (int round = 0; round < 3; round++) {
            ui(() -> invokeProjectAction(context.get()));
            awaitUi(() -> {
                FindPopupPanel popup = OrdinarySearchUiRegression.findNativePopup();
                return popup != null && countButtons(popup, ENHANCE_BUTTON) == 1;
            }, "Native search must show exactly one enhancement button after reopening");
            ui(() -> invokeProjectAction(context.get()));
            for (int observation = 0; observation < 4; observation++) {
                Thread.sleep(POLL_MILLIS);
                ui(() -> {
                    FindPopupPanel popup = OrdinarySearchUiRegression.findNativePopup();
                    require(popup != null && countButtons(popup, ENHANCE_BUTTON) == 1,
                            "Repeated invocation must not duplicate the enhancement command");
                });
            }
            ui(() -> FindManager.getInstance(fixture.project()).closeFindDialog());
            awaitUi(() -> OrdinarySearchUiRegression.findNativePopup() == null, "Native popup must close");
        }
    }

    /** Params: none. Returns: nothing; verifies the actual native window when its optional command is disabled. */
    private void checkHiddenButton() throws Exception {
        ui(() -> {
            preferences(true, false);
            invokeProjectAction(fixture.prepareContext(false));
        });
        awaitUi(() -> OrdinarySearchUiRegression.findNativePopup() != null, "Hidden-command mode must still show native search");
        for (int observation = 0; observation < 5; observation++) {
            Thread.sleep(POLL_MILLIS);
            ui(() -> require(countButtons(OrdinarySearchUiRegression.findNativePopup(), ENHANCE_BUTTON) == 0,
                    "Disabled enhancement command must not appear in native Find in Files"));
        }
    }

    /** Params: nativeFirst, showButton - global entry options. Returns: nothing; configures this sandbox only. */
    private static void preferences(boolean nativeFirst, boolean showButton) {
        SearchEnhancePreferences.getInstance().getState().nativeFirst = nativeFirst;
        SearchEnhancePreferences.getInstance().getState().showNativeButton = showButton;
    }

    /** Params: context - actual project tree context. Returns: nothing; runs the registered user action. */
    private static void invokeProjectAction(DataContext context) {
        AnAction action = ActionManager.getInstance().getAction("FindInPath");
        action.actionPerformed(AnActionEvent.createFromAnAction(action, null, "ProjectViewPopup", context));
    }

    /** Params: component, caption - actual UI subtree. Returns: the number of user-visible caption matches. */
    private static int countButtons(Component component, String caption) {
        if (component == null) {
            return 0;
        }
        int count = component instanceof JButton button && caption.equals(button.getText()) ? 1 : 0;
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                count += countButtons(child, caption);
            }
        }
        return count;
    }

    /** Params: condition, description - asynchronous native UI observation. Returns: nothing when it succeeds. */
    private static void awaitUi(CheckedBoolean condition, String description) throws Exception {
        long started = System.nanoTime();
        AtomicBoolean satisfied = new AtomicBoolean();
        while (elapsedMillis(started) < TIMEOUT_MILLIS) {
            ui(() -> satisfied.set(condition.get()));
            if (satisfied.get()) {
                return;
            }
            Thread.sleep(POLL_MILLIS);
        }
        throw new AssertionError(description);
    }

    /** Params: name, failures, operation - an independent UI scenario. Returns: its recorded outcome. */
    private void check(String name, List<Throwable> failures, CheckedOperation operation) {
        try {
            operation.run();
            report.add("PASS: " + name);
        } catch (Throwable failure) {
            failures.add(failure);
            report.add("FAIL: " + name + ": " + failure);
        } finally {
            ui(fixture::clearNativeUi);
        }
    }

    /** Params: operation - real Swing interaction. Returns: nothing after EDT completion. */
    private static void ui(CheckedOperation operation) {
        ApplicationManager.getApplication().invokeAndWait(() -> {
            try {
                operation.run();
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        });
    }

    /** Params: owner, name - pinned runtime control. Returns: the actual component or model field. */
    private static Object read(Object owner, String name) {
        return OrdinarySearchUiRegression.readField(owner, name);
    }

    /** Params: first, second - directory paths. Returns: equality independent of Windows separator spelling. */
    private static boolean samePath(String first, String second) {
        return first != null && second != null && first.replace('\\', '/').equalsIgnoreCase(second.replace('\\', '/'));
    }

    /** Params: started - monotonic start time. Returns: elapsed milliseconds. */
    private static long elapsedMillis(long started) { return (System.nanoTime() - started) / 1_000_000; }

    /** Params: condition, message - platform assertion. Returns: nothing unless the requirement fails. */
    private static void require(boolean condition, String message) {
        if (!condition) { throw new AssertionError(message); }
    }

    /** One bounded UI interaction. Author: zhoucb50741; date: 2026-10-14. */
    @FunctionalInterface
    private interface CheckedOperation {
        /** Params: none. Returns: nothing; propagates platform failures. */
        void run() throws Exception;
    }

    /** One asynchronous platform observation. Author: zhoucb50741; date: 2026-10-14. */
    @FunctionalInterface
    private interface CheckedBoolean {
        /** Params: none. Returns: whether the observed UI state is ready. */
        boolean get() throws Exception;
    }

    /** Actual modal completion routes. Author: zhoucb50741; date: 2026-10-14. */
    private enum Completion { APPLY, ORDINARY, CANCEL }
}
