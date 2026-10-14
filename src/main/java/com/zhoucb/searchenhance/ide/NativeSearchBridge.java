package com.zhoucb.searchenhance.ide;

import com.intellij.find.EditorSearchSession;
import com.intellij.find.FindManager;
import com.intellij.find.FindModel;
import com.intellij.find.FindUtil;
import com.intellij.find.findInProject.FindInProjectManager;
import com.intellij.find.impl.FindInProjectUtil;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 将条件表达式交给 IDEA 233 的原生查找界面和搜索引擎。
 *
 * @author zhoucb50741
 * @date 2026-10-14
 */
public final class NativeSearchBridge {
    /** 原生日志入口；不记录查询内容。 */
    private static final Logger LOG = Logger.getInstance(NativeSearchBridge.class);

    /**
     * 禁止实例化工具类。
     *
     * @return 无
     */
    private NativeSearchBridge() {
    }

    /**
     * 打开原生当前文件查找条或者 Find in Files，并保留原生导航和结果窗口。
     *
     * @param project 当前项目
     * @param editor 当前文件编辑器；项目搜索时允许为空
     * @param projectSearch 是否执行项目范围搜索
     * @param dataContext 调用动作的数据上下文，用于保留目录和范围
     * @param regex 已经转义和校验的条件正则表达式
     * @param caseSensitive 是否区分大小写
     * @return 无
     */
    public static void open(@NotNull Project project,
                            @Nullable Editor editor,
                            boolean projectSearch,
                            @NotNull DataContext dataContext,
                            @NotNull String regex,
                            boolean caseSensitive) {
        if (project.isDisposed()) {
            return;
        }
        FindManager findManager = FindManager.getInstance(project);
        FindModel model = (projectSearch
                ? findManager.getFindInProjectModel()
                : findManager.getFindInFileModel()).clone();
        if (projectSearch) {
            FindInProjectUtil.setScope(project, model, dataContext);
        }
        model.setStringToFind(regex);
        model.setRegularExpressions(true);
        model.setCaseSensitive(caseSensitive);
        model.setWholeWordsOnly(false);
        model.setReplaceState(false);
        model.setMultipleFiles(projectSearch);
        model.setGlobal(true);

        if (projectSearch) {
            NativeFindEnhancer.getInstance();
            LOG.info("Opening native Find in Files with a generated condition query");
            FindInProjectManager.getInstance(project).findInProject(dataContext, model);
            NativeFindEnhancer.getInstance().refreshVisiblePanels(project);
            return;
        }
        if (editor == null || editor.isDisposed()) {
            LOG.warn("Cannot open native editor search because the editor is unavailable");
            return;
        }
        EditorSearchSession session = EditorSearchSession.get(editor);
        if (session == null) {
            session = EditorSearchSession.start(editor, model, project);
        } else {
            session.getFindModel().copyFrom(model);
        }
        FindUtil.updateFindInFileModel(project, model, true);
        findManager.setFindWasPerformed();
        findManager.setFindNextModel(model.clone());
        LOG.info("Opening native editor search with a generated condition query");
        session.getComponent().requestFocusInTheSearchFieldAndSelectContent(project);
    }
}
