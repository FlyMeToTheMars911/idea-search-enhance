package com.zhoucb.searchenhance.settings;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.options.SearchableConfigurable;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Component;

/**
 * Settings / Tools 下的应用级搜索增强设置页。
 *
 * <p>Author: zhoucb50741</p>
 * <p>date: 2026-10-14</p>
 */
public final class SearchEnhanceConfigurable implements SearchableConfigurable {
    /** Settings 搜索和直接打开配置页时使用的稳定标识。 */
    public static final String ID = "search.enhance.preferences";
    /** 配置页显示名称。 */
    private static final String DISPLAY_NAME = "搜索增强";
    /** 原生日志入口；仅记录布尔偏好，不记录用户输入的查询条件。 */
    private static final Logger LOG = Logger.getInstance(SearchEnhanceConfigurable.class);
    /** 当前设置页组件，在关闭设置后释放。 */
    private JPanel panel;
    /** Ctrl+Shift+F 的默认入口选择。 */
    private JComboBox<EntryMode> entryMode;
    /** 原生 Find in Files 窗口中的增强搜索按钮可见性。 */
    private JBCheckBox showNativeButton;

    /**
     * Params: none.
     * Returns: 用于 Settings 搜索和定位的稳定页面标识。
     */
    @Override
    public @NotNull String getId() {
        return ID;
    }

    /**
     * Params: none.
     * Returns: Settings / Tools 中显示的配置页名称。
     */
    @Override
    public @NotNull String getDisplayName() {
        return DISPLAY_NAME;
    }

    /**
     * Params: none.
     * Returns: 从全局偏好初始化的设置页；多次调用复用当前页面组件。
     */
    @Override
    public @NotNull JComponent createComponent() {
        if (panel != null) {
            return panel;
        }
        panel = new JPanel(new BorderLayout());
        panel.setBorder(JBUI.Borders.empty(12));
        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));

        JPanel entryRow = new JPanel(new BorderLayout(JBUI.scale(12), 0));
        JBLabel entryLabel = new JBLabel("Ctrl+Shift+F 默认打开：");
        entryMode = new JComboBox<>(EntryMode.values());
        entryLabel.setLabelFor(entryMode);
        entryRow.add(entryLabel, BorderLayout.WEST);
        entryRow.add(entryMode, BorderLayout.CENTER);
        entryRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        entryRow.setMaximumSize(entryRow.getPreferredSize());
        content.add(entryRow);
        content.add(Box.createVerticalStrut(JBUI.scale(16)));

        showNativeButton = new JBCheckBox("在原生 Find in Files 窗口显示“增强搜索…”按钮");
        showNativeButton.setToolTipText("可在原生搜索窗口中随时切换到增强条件组合器。");
        showNativeButton.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(showNativeButton);
        content.add(Box.createVerticalStrut(JBUI.scale(16)));

        JBLabel explanation = new JBLabel("<html>先打开原生窗口：保留 IDEA 搜索习惯，按需点击“增强搜索…”组合条件。"
                + "<br>直接打开增强条件：按 Ctrl+Shift+F 即可组合“且、或、不包含”等条件。"
                + "<br><br>Ctrl+F 始终保留原生当前文件查找。"
                + "<br>这些设置适用于所有项目，在下次打开搜索窗口时生效。</html>");
        explanation.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(explanation);
        panel.add(content, BorderLayout.NORTH);
        reset();
        return panel;
    }

    /**
     * Params: none.
     * Returns: 当前页面内容是否不同于已保存的全局偏好。
     */
    @Override
    public boolean isModified() {
        if (entryMode == null || showNativeButton == null) {
            return false;
        }
        SearchEnhancePreferences.PreferencesState current = SearchEnhancePreferences.getInstance().getState();
        return (entryMode.getSelectedItem() == EntryMode.NATIVE_FIRST) != current.nativeFirst
                || showNativeButton.isSelected() != current.showNativeButton;
    }

    /**
     * Params: none.
     * Returns: nothing；将页面选项保存为应用级偏好。
     */
    @Override
    public void apply() {
        if (entryMode == null || showNativeButton == null) {
            return;
        }
        SearchEnhancePreferences.PreferencesState updated = new SearchEnhancePreferences.PreferencesState();
        updated.nativeFirst = entryMode.getSelectedItem() == EntryMode.NATIVE_FIRST;
        updated.showNativeButton = showNativeButton.isSelected();
        SearchEnhancePreferences.getInstance().loadState(updated);
        LOG.info("Search entry preferences applied: nativeFirst=" + updated.nativeFirst
                + ", showNativeButton=" + updated.showNativeButton);
    }

    /**
     * Params: none.
     * Returns: nothing；从已保存偏好还原页面，取消尚未应用的改动。
     */
    @Override
    public void reset() {
        if (entryMode == null || showNativeButton == null) {
            return;
        }
        SearchEnhancePreferences.PreferencesState current = SearchEnhancePreferences.getInstance().getState();
        entryMode.setSelectedItem(current.nativeFirst ? EntryMode.NATIVE_FIRST : EntryMode.ENHANCED_FIRST);
        showNativeButton.setSelected(current.showNativeButton);
    }

    /**
     * Params: none.
     * Returns: nothing；释放 Swing 组件引用，允许之后重新创建页面。
     */
    @Override
    public void disposeUIResources() {
        panel = null;
        entryMode = null;
        showNativeButton = null;
    }

    /**
     * 项目查找的两种入口方式，直接对应用户可见的设置。
     *
     * <p>Author: zhoucb50741</p>
     * <p>date: 2026-10-14</p>
     */
    private enum EntryMode {
        /** 先显示原生搜索，允许通过按钮切换到增强条件。 */
        NATIVE_FIRST("先打开 IDEA 原生搜索窗口"),
        /** 保留直接进入增强条件组合器的使用方式。 */
        ENHANCED_FIRST("直接打开增强条件窗口");

        /** 下拉框显示文字。 */
        private final String label;

        /**
         * Params: label - 模式在设置页中的名称。
         * Returns: 一个可显示的模式枚举项。
         */
        EntryMode(String label) {
            this.label = label;
        }

        /**
         * Params: none.
         * Returns: 当前入口模式的设置页文字。
         */
        @Override
        public String toString() {
            return label;
        }
    }
}
