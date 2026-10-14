package com.zhoucb.searchenhance.ide;

import com.intellij.execution.impl.ConsoleViewUtil;
import com.intellij.find.actions.FindInPathAction;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.OverridingAction;
import com.intellij.openapi.actionSystem.impl.ActionManagerImpl;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorKind;
import com.intellij.openapi.editor.actions.IncrementalFindAction;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 封装针对 IDEA 233 的原生动作回退和编辑器范围判断。
 *
 * @author zhoucb50741
 * @date 2026-10-14
 */
public final class NativeActionSupport {
    /**
     * 禁止实例化工具类。
     *
     * @return 无
     */
    private NativeActionSupport() {
    }

    /**
     * 取得被覆盖的原生动作，保留已有动作扩展和用户键位。
     *
     * @param overridingAction 当前覆盖动作
     * @param projectSearch 是否查找项目文件
     * @return 原生动作或对应平台动作的安全后备实例
     */
    public static @NotNull AnAction original(@NotNull OverridingAction overridingAction,
                                             boolean projectSearch) {
        ActionManager manager = ActionManager.getInstance();
        if (manager instanceof ActionManagerImpl implementation) {
            AnAction base = implementation.getBaseAction(overridingAction);
            if (base != null) {
                return base;
            }
        }
        return projectSearch ? new FindInPathAction() : new IncrementalFindAction();
    }

    /**
     * 判断编辑器是否适合显示文件条件组合器；控制台和输入框交给原生动作。
     *
     * @param editor 动作上下文中的编辑器
     * @return 是否为有效的主编辑器
     */
    public static boolean isMainEditor(@Nullable Editor editor) {
        return editor != null
                && !editor.isDisposed()
                && !editor.isOneLineMode()
                && editor.getEditorKind() == EditorKind.MAIN_EDITOR
                && !ConsoleViewUtil.isConsoleViewEditor(editor);
    }
}
