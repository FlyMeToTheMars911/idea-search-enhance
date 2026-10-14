package com.zhoucb.searchenhance.ide;

import com.intellij.execution.impl.ConsoleViewUtil;
import com.intellij.find.actions.FindInPathAction;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.ActionStub;
import com.intellij.openapi.actionSystem.ActionStubBase;
import com.intellij.openapi.actionSystem.OverridingAction;
import com.intellij.openapi.actionSystem.impl.ActionManagerImpl;
import com.intellij.openapi.actionSystem.impl.ActionManagerImplKt;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorKind;
import com.intellij.openapi.editor.actions.IncrementalFindAction;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * 封装针对 IDEA 233 的原生动作回退和编辑器范围判断。
 *
 * @author zhoucb50741
 * @date 2026-10-14
 */
public final class NativeActionSupport {
    /** 原生动作解析日志；不记录搜索内容。 */
    private static final Logger LOG = Logger.getInstance(NativeActionSupport.class);
    /** 按增强动作缓存已实例化的原生动作，避免每次 update 重复创建。 */
    private static final Map<OverridingAction, AnAction> ORIGINALS = new WeakHashMap<>();
    /**
     * 禁止实例化工具类。
     *
     * @return 无
     */
    private NativeActionSupport() {
    }

    /**
     * 取得可执行的原生动作，将 233 平台的延迟加载占位对象解析为真实动作。
     *
     * @param overridingAction 当前覆盖动作
     * @param projectSearch 是否查找项目文件
     * @return 原生动作或对应平台动作的安全后备实例
     */
    public static @NotNull AnAction original(@NotNull OverridingAction overridingAction,
                                             boolean projectSearch) {
        synchronized (ORIGINALS) {
            AnAction cached = ORIGINALS.get(overridingAction);
            if (cached != null) {
                return cached;
            }
            AnAction base = null;
            ActionManager manager = ActionManager.getInstance();
            if (manager instanceof ActionManagerImpl implementation) {
                base = implementation.getBaseAction(overridingAction);
            }
            if (base instanceof ActionStub stub) {
                LOG.info("Resolving lazy native search action: id=" + stub.getId()
                        + ", class=" + stub.getClassName());
                try {
                    base = ActionManagerImplKt.convertStub(stub);
                } catch (RuntimeException exception) {
                    LOG.warn("Cannot resolve lazy native search action; using platform fallback", exception);
                    base = null;
                }
            }
            if (base == null || base == overridingAction || base instanceof ActionStubBase) {
                base = projectSearch ? new FindInPathAction() : new IncrementalFindAction();
            }
            ORIGINALS.put(overridingAction, base);
            LOG.info("Native search action ready: class=" + base.getClass().getName()
                    + ", projectSearch=" + projectSearch);
            return base;
        }
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
