package com.zhoucb.searchenhance.settings;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import org.jetbrains.annotations.NotNull;

/**
 * 应用级搜索入口偏好，与每个项目独立保存的搜索条件草稿分开存储。
 *
 * <p>Author: zhoucb50741</p>
 * <p>date: 2026-10-14</p>
 */
@Service(Service.Level.APP)
@State(name = "SearchEnhancePreferences", storages = @Storage("SearchEnhancePreferences.xml"))
public final class SearchEnhancePreferences
        implements PersistentStateComponent<SearchEnhancePreferences.PreferencesState> {
    /** 当前应用级偏好；整体替换时对动作更新线程立即可见。 */
    private volatile PreferencesState state = new PreferencesState();

    /**
     * Params: none.
     * Returns: 当前 IDEA 应用的搜索入口偏好服务。
     */
    public static @NotNull SearchEnhancePreferences getInstance() {
        return ApplicationManager.getApplication().getService(SearchEnhancePreferences.class);
    }

    /**
     * Params: none.
     * Returns: 可持久化的全局搜索偏好。
     */
    @Override
    public @NotNull PreferencesState getState() {
        return state;
    }

    /**
     * Params: value - 从配置文件恢复或在设置页应用的新偏好。
     * Returns: nothing；原子替换偏好，不持有任何项目或编辑器引用。
     */
    @Override
    public void loadState(@NotNull PreferencesState value) {
        state = value;
    }

    /**
     * Params: none.
     * Returns: Ctrl+Shift+F 是否先打开 IDEA 原生 Find in Files。
     */
    public boolean isNativeFirst() {
        return state.nativeFirst;
    }

    /**
     * Params: none.
     * Returns: 原生 Find in Files 中是否显示增强搜索入口。
     */
    public boolean isShowNativeButton() {
        return state.showNativeButton;
    }

    /**
     * 全局偏好的 XML 序列化状态；新安装默认保留原生项目搜索入口。
     *
     * <p>Author: zhoucb50741</p>
     * <p>date: 2026-10-14</p>
     */
    public static final class PreferencesState {
        /** 是否先打开原生 Find in Files；关闭后直接打开增强条件组合器。 */
        public boolean nativeFirst = true;
        /** 是否在原生 Find in Files 中显示增强搜索按钮。 */
        public boolean showNativeButton = true;
    }
}
