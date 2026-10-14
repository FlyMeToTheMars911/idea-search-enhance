package com.zhoucb.searchenhance.ide;

import com.intellij.find.FindManager;
import com.intellij.find.FindModel;
import com.intellij.find.FindSettings;
import com.intellij.find.SearchTextArea;
import com.intellij.find.impl.FindPopupPanel;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys;
import com.intellij.openapi.actionSystem.impl.SimpleDataContext;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.util.ui.JBUI;
import com.zhoucb.searchenhance.settings.SearchEnhancePreferences;
import com.zhoucb.searchenhance.ui.SearchEnhanceDialog;

import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.AWTEvent;
import java.awt.Component;
import java.awt.Container;
import java.awt.FlowLayout;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.AWTEventListener;
import java.awt.event.HierarchyEvent;

/**
 * Adds an opt-in condition button to the actual IDEA 233 Find in Files panel.
 * Author: zhoucb50741
 * date: 2026-10-14
 */
@Service(Service.Level.APP)
public final class NativeFindEnhancer implements Disposable {
    /** Stable identity prevents duplicate rows when the native panel is reused. */
    private static final String BUTTON_ROW_KEY = "SearchEnhance.NativeButtonRow";
    /** Logs lifecycle and mode switches without recording search contents. */
    private static final Logger LOG = Logger.getInstance(NativeFindEnhancer.class);
    /** One cheap hierarchy listener; there is no periodic scanning timer. */
    private final AWTEventListener listener = this::onHierarchyChange;

    /** Params: none. Returns: an application-owned, disposable native UI observer. */
    public NativeFindEnhancer() {
        Toolkit.getDefaultToolkit().addAWTEventListener(listener, AWTEvent.HIERARCHY_EVENT_MASK);
    }

    /** Params: none. Returns: the application observer, initialized before native search opens. */
    public static NativeFindEnhancer getInstance() {
        return ApplicationManager.getApplication().getService(NativeFindEnhancer.class);
    }

    /** Params: event - native component lifecycle event. Returns: nothing. */
    private void onHierarchyChange(AWTEvent event) {
        if (event instanceof HierarchyEvent hierarchy
                && hierarchy.getComponent() instanceof FindPopupPanel panel
                && (hierarchy.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0) {
            SwingUtilities.invokeLater(() -> {
                if (panel.isShowing() && !panel.getProject().isDisposed()) {
                    synchronizeButton(panel);
                }
            });
        }
    }

    /** Params: project - action owner. Returns: nothing; refreshes reused visible panels as well. */
    public void refreshVisiblePanels(Project project) {
        for (Window window : Window.getWindows()) {
            if (window.isShowing()) {
                refreshTree(window, project);
            }
        }
    }

    /** Params: component, project - a visible native search tree. Returns: nothing. */
    private void refreshTree(Component component, Project project) {
        if (component instanceof FindPopupPanel panel && panel.getProject() == project) {
            synchronizeButton(panel);
            return;
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                refreshTree(child, project);
            }
        }
    }

    /** Params: panel - actual platform search panel. Returns: nothing; adds or removes our row. */
    private void synchronizeButton(FindPopupPanel panel) {
        Object previous = panel.getClientProperty(BUTTON_ROW_KEY);
        boolean enabled = SearchEnhancePreferences.getInstance().isShowNativeButton()
                && !panel.getHelper().isReplaceState();
        if (!enabled) {
            if (previous instanceof JPanel row) {
                panel.remove(row);
                panel.putClientProperty(BUTTON_ROW_KEY, null);
                panel.revalidate();
                panel.repaint();
            }
            return;
        }
        if (previous != null) {
            return;
        }
        JPanel row = new JPanel(new FlowLayout(FlowLayout.RIGHT, JBUI.scale(8), JBUI.scale(3)));
        JButton button = new JButton("增强搜索…");
        button.setToolTipText("用界面组合且、或、不包含等条件，完成后回到当前原生搜索窗口");
        button.addActionListener(event -> openConditions(panel));
        row.add(button);
        panel.putClientProperty(BUTTON_ROW_KEY, row);
        // IDEA 233 uses MigLayout with wrapped rows. Keep the native header,
        // options, preview and result controls intact and insert only our row.
        panel.add(row, "growx,pushx,wrap", Math.min(1, panel.getComponentCount()));
        Disposer.register(panel.getDisposable(), () -> {
            if (panel.getClientProperty(BUTTON_ROW_KEY) == row) {
                panel.remove(row);
                panel.putClientProperty(BUTTON_ROW_KEY, null);
            }
        });
        panel.revalidate();
        panel.repaint();
        LOG.info("Added enhanced search button to native Find in Files");
    }

    /** Params: panel - active native popup. Returns: nothing; edits conditions without closing it. */
    private void openConditions(FindPopupPanel panel) {
        Project project = panel.getProject();
        if (project.isDisposed() || !panel.isShowing()) {
            return;
        }
        panel.saveSettings();
        FindModel snapshot = FindManager.getInstance(project).getFindInProjectModel().clone();
        DataContext context = SimpleDataContext.builder()
                .add(CommonDataKeys.PROJECT, project)
                .add(PlatformCoreDataKeys.CONTEXT_COMPONENT, panel).build();
        boolean canClose = panel.getCanClose().getAndSet(false);
        LOG.info("Entering enhanced conditions from native Find in Files");
        try {
            new SearchEnhanceDialog(project, null, true, context, () -> focusSearch(panel),
                    snapshot, (regex, caseSensitive) -> {
                        // Preserve this model's identity: the native OK handler
                        // captures it when the platform opens the search popup.
                        FindModel target = panel.getHelper().getModel();
                        target.copyFrom(snapshot);
                        target.setStringToFind(regex);
                        target.setRegularExpressions(true);
                        target.setCaseSensitive(caseSensitive);
                        target.setWholeWordsOnly(false);
                        target.setReplaceState(false);
                        // Native initByModel restores mask text from global
                        // settings/history, so publish the just-edited value first.
                        FindSettings.getInstance().setFileMask(snapshot.getFileFilter());
                        panel.initByModel();
                        panel.saveSettings();
                        panel.scheduleResultsUpdate();
                        LOG.info("Applied enhanced conditions to existing native Find in Files model");
                    }).show();
        } finally {
            panel.getCanClose().set(canClose);
            focusSearch(panel);
        }
    }

    /** Params: component - active search tree. Returns: whether a search text area received focus. */
    private static boolean focusSearch(Component component) {
        if (component instanceof SearchTextArea area) {
            return area.getTextArea().requestFocusInWindow();
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                if (focusSearch(child)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Params: none. Returns: nothing; releases the application listener. */
    @Override
    public void dispose() {
        Toolkit.getDefaultToolkit().removeAWTEventListener(listener);
    }
}
