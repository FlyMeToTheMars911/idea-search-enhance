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
 * 在原生 Find in Files 动作及其现有快捷键下打开项目条件组合器。
 *
 * @author zhoucb50741
 * @date 2026-10-14
 */
public final class ProjectSearchAction extends DumbAwareAction implements OverridingAction {
    /** 原生日志入口；不记录查询内容。 */
    private static final Logger LOG = Logger.getInstance(ProjectSearchAction.class);

    /**
     * 打开项目条件组合器，并保留调用处的搜索范围上下文。
     *
     * @param event 原生动作事件
     * @return 无
     */
    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        AnAction original = NativeActionSupport.original(this, true);
        Project project = event.getProject();
        if (project == null || project.isDisposed()) {
            original.actionPerformed(event);
            return;
        }
        Editor editor = event.getData(CommonDataKeys.EDITOR);
        if (editor != null && !NativeActionSupport.isMainEditor(editor)) {
            original.actionPerformed(event);
            return;
        }
        LOG.info("Opening enhanced project search conditions");
        new SearchEnhanceDialog(project, editor, true, event.getDataContext(),
                () -> original.actionPerformed(event)).show();
    }

    /**
     * 继承原生项目查找动作的范围和项目可用性判断。
     *
     * @param event 待更新的动作事件
     * @return 无
     */
    @Override
    public void update(@NotNull AnActionEvent event) {
        NativeActionSupport.original(this, true).update(event);
    }

    /**
     * 声明动作更新线程。
     *
     * @return EDT 更新线程
     */
    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.EDT;
    }
}
