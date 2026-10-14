package com.zhoucb.searchenhance.action;

import com.intellij.find.actions.FindInPathAction;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.actionSystem.impl.SimpleDataContext;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.actions.IncrementalFindAction;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.zhoucb.searchenhance.ide.NativeActionSupport;
import com.zhoucb.searchenhance.ui.SearchEnhanceDialog;
import org.jetbrains.annotations.NotNull;

/**
 * 为 Tools 菜单和 Find Action 提供独立的条件组合器入口。
 *
 * @author zhoucb50741
 * @date 2026-10-14
 */
public final class EditConditionsAction extends DumbAwareAction {
    /** 原生日志入口；不记录查询内容。 */
    private static final Logger LOG = Logger.getInstance(EditConditionsAction.class);

    /**
     * 优先编辑当前文件条件，没有主编辑器时编辑项目条件。
     *
     * @param event 独立动作事件
     * @return 无
     */
    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        if (project == null || project.isDisposed()) {
            return;
        }
        Editor editor = event.getData(CommonDataKeys.EDITOR);
        if (!NativeActionSupport.isMainEditor(editor)) {
            editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
        }
        boolean projectSearch = !NativeActionSupport.isMainEditor(editor);
        DataContext context = SimpleDataContext.getSimpleContext(CommonDataKeys.EDITOR, editor,
                event.getDataContext());
        LOG.info("Opening enhanced search conditions from the standalone action");
        new SearchEnhanceDialog(project, editor, projectSearch, context, () -> {
            if (projectSearch) {
                FindInPathAction action = new FindInPathAction();
                action.actionPerformed(AnActionEvent.createFromAnAction(action, event.getInputEvent(),
                        event.getPlace(), context));
            } else {
                IncrementalFindAction action = new IncrementalFindAction();
                action.actionPerformed(AnActionEvent.createFromAnAction(action, event.getInputEvent(),
                        event.getPlace(), context));
            }
        }).show();
    }

    /**
     * 仅在项目打开时启用独立入口。
     *
     * @param event 待更新的动作事件
     * @return 无
     */
    @Override
    public void update(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        event.getPresentation().setEnabledAndVisible(project != null && !project.isDisposed());
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
