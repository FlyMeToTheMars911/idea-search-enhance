package com.zhoucb.searchenhance.action;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.OverridingAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.zhoucb.searchenhance.ide.NativeActionSupport;
import com.zhoucb.searchenhance.ui.SearchEnhanceDialog;
import org.jetbrains.annotations.NotNull;

/**
 * 为当前文件提供独立条件搜索入口，不覆盖原生 Find 或 Ctrl+F。
 *
 * @author zhoucb50741
 * @date 2026-10-14
 */
public final class CurrentFileSearchAction extends DumbAwareAction implements OverridingAction {
    /** 原生日志入口；不记录查询内容。 */
    private static final Logger LOG = Logger.getInstance(CurrentFileSearchAction.class);

    /**
     * 从独立菜单入口打开组合器；非主编辑器上下文继续执行原生查找。
     *
     * @param event 原生动作事件
     * @return 无
     */
    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        AnAction original = NativeActionSupport.original(this, false);
        Project project = event.getProject();
        Editor editor = event.getData(CommonDataKeys.EDITOR);
        if (project == null || project.isDisposed() || !NativeActionSupport.isMainEditor(editor)) {
            original.actionPerformed(event);
            return;
        }
        LOG.info("Opening enhanced current-file search conditions");
        new SearchEnhanceDialog(project, editor, false, event.getDataContext(),
                () -> original.actionPerformed(event)).show();
    }

    /**
     * 继承原生查找动作在当前上下文中的可用性。
     *
     * @param event 待更新的动作事件
     * @return 无
     */
    @Override
    public void update(@NotNull AnActionEvent event) {
        NativeActionSupport.original(this, false).update(event);
    }

    /**
     * 声明原生编辑器动作和 Swing 上下文所需的更新线程。
     *
     * @return EDT 更新线程
     */
    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.EDT;
    }
}
