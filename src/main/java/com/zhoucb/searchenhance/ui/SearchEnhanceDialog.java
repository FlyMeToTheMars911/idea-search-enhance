package com.zhoucb.searchenhance.ui;

import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import com.zhoucb.searchenhance.ide.NativeSearchBridge;
import com.zhoucb.searchenhance.query.ConditionGroup;
import com.zhoucb.searchenhance.query.MatchType;
import com.zhoucb.searchenhance.query.SearchCondition;
import com.zhoucb.searchenhance.query.SearchQuery;
import com.zhoucb.searchenhance.settings.SearchSettings;
import com.zhoucb.searchenhance.settings.SearchSettings.GroupState;
import com.zhoucb.searchenhance.settings.SearchSettings.RowState;
import com.zhoucb.searchenhance.settings.SearchSettings.SearchState;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Visual builder for literal, same-line search conditions and alternatives.
 * Author: zhoucb50741
 * date: 2026-10-14
 */
public final class SearchEnhanceDialog extends DialogWrapper {
    /** Structured diagnostics omit all entered search text. */
    private static final Logger LOG = Logger.getInstance(SearchEnhanceDialog.class);
    /** Dialog minimum size in logical pixels. */
    private static final Dimension DIALOG_SIZE = new Dimension(1000, 660);
    /** Owning project. */
    private final Project project;
    /** Original editor, if the action originated from one. */
    private final Editor editor;
    /** Whether to continue in Find in Files. */
    private final boolean projectSearch;
    /** Original data used to retain the native directory/scope. */
    private final DataContext dataContext;
    /** Native action fallback. */
    private final Runnable nativeSearch;
    /** Mutable, dialog-local copy of the last search draft. */
    private final SearchState draft;
    /** Group selector. */
    private final JComboBox<String> groupSelector = new JComboBox<>();
    /** Literal case preference. */
    private final JBCheckBox caseSensitive = new JBCheckBox("区分大小写");
    /** Container for conjunction rows. */
    private final JPanel rowsPanel = new JPanel();
    /** Visible controls for the currently selected group. */
    private final List<ConditionEditor> editors = new ArrayList<>();
    /** Optional generated-expression preview. */
    private final JTextArea regexPreview = new JTextArea();
    /** Nonblocking validation hint. */
    private final JBLabel status = new JBLabel(" ");
    /** Prevents listeners from serializing partially rendered groups. */
    private boolean rendering;
    /** Current group index. */
    private int selectedGroup;

    /**
     * Params: project, editor, projectSearch, dataContext - native search context;
     * nativeSearch - action to invoke when ordinary search is requested.
     * Returns: a ready-to-show, project-modal dialog.
     */
    public SearchEnhanceDialog(@NotNull Project project, @Nullable Editor editor,
                               boolean projectSearch, @NotNull DataContext dataContext,
                               @NotNull Runnable nativeSearch) {
        super(project, true);
        this.project = project;
        this.editor = editor;
        this.projectSearch = projectSearch;
        this.dataContext = dataContext;
        this.nativeSearch = nativeSearch;
        this.draft = copyState(SearchSettings.getInstance(project).getState());
        if (draft.groups.isEmpty()) {
            GroupState initial = new GroupState();
            RowState row = new RowState();
            if (editor != null && editor.getSelectionModel().hasSelection()) {
                String selection = editor.getSelectionModel().getSelectedText();
                if (selection != null && !selection.contains("\n") && !selection.contains("\r")) {
                    row.first = selection;
                }
            }
            initial.rows.add(row);
            draft.groups.add(initial);
        }
        selectedGroup = Math.max(0, Math.min(draft.selectedGroup, draft.groups.size() - 1));
        setTitle(projectSearch ? "搜索增强 · 在文件中查找" : "搜索增强 · 当前文件");
        setOKButtonText(projectSearch ? "继续到原生 Find in Files" : "在当前文件搜索");
        setCancelButtonText("取消");
        init();
    }

    /**
     * Params: none.
     * Returns: the condition builder and optional read-only expression tab.
     */
    @Override
    protected @NotNull JComponent createCenterPanel() {
        JPanel root = new JPanel(new BorderLayout(JBUI.scale(8), JBUI.scale(10)));
        root.setPreferredSize(JBUI.size(DIALOG_SIZE.width, DIALOG_SIZE.height));
        root.setMinimumSize(JBUI.size(850, 470));
        String searchBinding = "SearchEnhance.Submit";
        root.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
                .put(KeyStroke.getKeyStroke("ctrl ENTER"), searchBinding);
        root.getActionMap().put(searchBinding, new AbstractAction() {
            /** Params: event - keyboard activation. Returns: nothing. */
            @Override public void actionPerformed(ActionEvent event) {
                if (getOKAction().isEnabled()) {
                    doOKAction();
                }
            }
        });
        root.add(new JBLabel("<html><b>按同一行判断：</b>组内所有条件都要满足；不同条件组之间是“或”。"
                + "<br>直接输入文本，特殊符号自动处理。完整词匹配可避免 taskIdBackup 等误匹配。</html>"), BorderLayout.NORTH);

        JPanel builder = new JPanel(new BorderLayout(JBUI.scale(6), JBUI.scale(8)));
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0));
        toolbar.add(new JBLabel("条件组"));
        groupSelector.addActionListener(event -> switchGroup());
        toolbar.add(groupSelector);
        toolbar.add(button("＋ 或分组", () -> addGroup()));
        toolbar.add(button("删除此组", () -> removeGroup()));
        caseSensitive.setSelected(draft.caseSensitive);
        caseSensitive.addActionListener(event -> refreshPreview());
        toolbar.add(caseSensitive);
        builder.add(toolbar, BorderLayout.NORTH);
        rowsPanel.setLayout(new BoxLayout(rowsPanel, BoxLayout.Y_AXIS));
        JPanel rowsContainer = new JPanel(new BorderLayout());
        rowsContainer.add(rowsPanel, BorderLayout.NORTH);
        builder.add(new JBScrollPane(rowsContainer), BorderLayout.CENTER);

        JPanel footer = new JPanel(new BorderLayout(JBUI.scale(6), JBUI.scale(8)));
        JPanel commands = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0));
        commands.add(button("＋ 且条件", () -> addCondition()));
        JComboBox<Preset> examples = new JComboBox<>(Preset.values());
        commands.add(examples);
        commands.add(button("载入示例（替换全部条件）", () -> applyPreset((Preset) examples.getSelectedItem())));
        footer.add(commands, BorderLayout.NORTH);
        footer.add(status, BorderLayout.SOUTH);
        builder.add(footer, BorderLayout.SOUTH);

        regexPreview.setEditable(false);
        regexPreview.setLineWrap(true);
        regexPreview.setWrapStyleWord(false);
        regexPreview.setFont(new Font(Font.MONOSPACED, Font.PLAIN, JBUI.scale(12)));
        regexPreview.setBorder(JBUI.Borders.empty(10));
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("条件组合", builder);
        tabs.addTab("生成表达式（只读，可复制）", new JBScrollPane(regexPreview));
        root.add(tabs, BorderLayout.CENTER);
        root.add(new JBLabel(projectSearch
                ? "Ctrl+Enter 继续；下一步在原生窗口选择范围、文件类型，回车打开 Find 结果窗口。"
                : "Ctrl+Enter 条件搜索；Ctrl+F 保持原生查找。可从 Tools 菜单再次修改条件。"), BorderLayout.SOUTH);
        renderGroups();
        return root;
    }

    /**
     * Params: none.
     * Returns: search, ordinary-search fallback and cancel actions.
     */
    @Override
    protected Action @NotNull [] createActions() {
        Action ordinary = new DialogWrapperAction("普通搜索…") {
            /** Params: event - button activation. Returns: nothing. */
            @Override
            protected void doAction(ActionEvent event) {
                saveDraft();
                LOG.info("Requesting ordinary native search: projectSearch=" + projectSearch);
                close(CANCEL_EXIT_CODE);
                ApplicationManager.getApplication().invokeLater(nativeSearch,
                        ModalityState.nonModal(), project.getDisposed());
            }
        };
        return new Action[]{getOKAction(), ordinary, getCancelAction()};
    }

    /** Params: none. Returns: the first literal field, when available. */
    @Override
    public @Nullable JComponent getPreferredFocusedComponent() {
        return editors.isEmpty() ? groupSelector : editors.get(0).first;
    }

    /** Params: none. Returns: the stable project-local dialog sizing key. */
    @Override
    protected @Nullable String getDimensionServiceKey() {
        return "SearchEnhance.ConditionDialog";
    }

    /** Params: none. Returns: nothing; validates and opens native search. */
    @Override
    protected void doOKAction() {
        try {
            SearchQuery query = buildQuery();
            if (query.isEmpty()) {
                setErrorText("请至少填写一个有效条件。");
                return;
            }
            String regex = query.toSameLineRegex();
            Pattern.compile(regex, query.getRegexFlags());
            saveDraft();
            LOG.info("Condition search accepted: groups=" + query.getGroups().size()
                    + ", projectSearch=" + projectSearch);
            super.doOKAction();
            ApplicationManager.getApplication().invokeLater(() -> NativeSearchBridge.open(
                    project, editor, projectSearch, dataContext, regex, query.isCaseSensitive()), project.getDisposed());
        } catch (IllegalArgumentException | IllegalStateException exception) {
            setErrorText(exception.getMessage());
        }
    }

    /** Params: none. Returns: nothing; preserves an incomplete editable draft. */
    @Override
    public void doCancelAction() {
        saveDraft();
        super.doCancelAction();
    }

    /** Params: text - button label; action - callback. Returns: a standard button. */
    private static JButton button(String text, Runnable action) {
        JButton result = new JButton(text);
        result.addActionListener(event -> action.run());
        return result;
    }

    /** Params: none. Returns: nothing; snapshots current visible rows. */
    private void captureRows() {
        if (rendering || draft.groups.isEmpty()) {
            return;
        }
        GroupState group = draft.groups.get(selectedGroup);
        group.rows.clear();
        for (ConditionEditor condition : editors) {
            group.rows.add(condition.snapshot());
        }
    }

    /** Params: none. Returns: nothing; selects the requested OR group. */
    private void switchGroup() {
        int next = groupSelector.getSelectedIndex();
        if (rendering || next < 0 || next == selectedGroup) {
            return;
        }
        captureRows();
        selectedGroup = next;
        renderRows();
    }

    /** Params: none. Returns: nothing; adds one alternative group. */
    private void addGroup() {
        captureRows();
        GroupState group = new GroupState();
        group.rows.add(new RowState());
        draft.groups.add(group);
        selectedGroup = draft.groups.size() - 1;
        renderGroups();
    }

    /** Params: none. Returns: nothing; removes or clears the selected group. */
    private void removeGroup() {
        if (draft.groups.size() > 1) {
            draft.groups.remove(selectedGroup);
            selectedGroup = Math.min(selectedGroup, draft.groups.size() - 1);
        } else {
            draft.groups.get(0).rows.clear();
            draft.groups.get(0).rows.add(new RowState());
        }
        renderGroups();
    }

    /** Params: none. Returns: nothing; appends an AND condition. */
    private void addCondition() {
        captureRows();
        draft.groups.get(selectedGroup).rows.add(new RowState());
        renderRows();
        if (!editors.isEmpty()) {
            editors.get(editors.size() - 1).first.requestFocusInWindow();
        }
    }

    /** Params: none. Returns: nothing; refreshes the group selector. */
    private void renderGroups() {
        rendering = true;
        groupSelector.removeAllItems();
        for (int index = 0; index < draft.groups.size(); index++) {
            groupSelector.addItem("第 " + (index + 1) + " 组");
        }
        groupSelector.setSelectedIndex(selectedGroup);
        rendering = false;
        renderRows();
    }

    /** Params: none. Returns: nothing; recreates controls for one group. */
    private void renderRows() {
        rendering = true;
        rowsPanel.removeAll();
        editors.clear();
        for (RowState row : draft.groups.get(selectedGroup).rows) {
            ConditionEditor condition = new ConditionEditor(row);
            editors.add(condition);
            rowsPanel.add(condition.panel);
        }
        rendering = false;
        rowsPanel.revalidate();
        rowsPanel.repaint();
        refreshPreview();
    }

    /** Params: none. Returns: the immutable query represented by the controls. */
    private SearchQuery buildQuery() {
        captureRows();
        List<ConditionGroup> groups = new ArrayList<>();
        for (GroupState group : draft.groups) {
            List<SearchCondition> conditions = new ArrayList<>();
            for (RowState row : group.rows) {
                conditions.add(new SearchCondition(MatchType.valueOf(row.type), row.first,
                        row.second, row.excluded, row.wholeWord));
            }
            groups.add(new ConditionGroup(conditions));
        }
        return new SearchQuery(groups, caseSensitive.isSelected());
    }

    /** Params: none. Returns: nothing; updates read-only preview and validation. */
    private void refreshPreview() {
        if (rendering) {
            return;
        }
        try {
            SearchQuery query = buildQuery();
            boolean valid = !query.isEmpty();
            regexPreview.setText(valid ? query.toSameLineRegex() : "填写条件后自动生成，无需编辑。");
            status.setText(valid ? "有效条件组：" + query.getGroups().size() + "；空白条件不参与匹配。"
                    : "请填写条件；每个多值字段中的不同值请分别占一行。");
            setErrorText(null);
            setOKActionEnabled(valid);
        } catch (IllegalArgumentException | IllegalStateException exception) {
            regexPreview.setText("");
            status.setText("条件尚未完成：" + exception.getMessage());
            setOKActionEnabled(false);
        }
    }

    /** Params: none. Returns: nothing; saves an independent project-local draft. */
    private void saveDraft() {
        captureRows();
        draft.caseSensitive = caseSensitive.isSelected();
        draft.selectedGroup = selectedGroup;
        SearchSettings.getInstance(project).loadState(copyState(draft));
    }

    /** Params: source - stored draft. Returns: an independent, validated copy. */
    private static SearchState copyState(SearchState source) {
        SearchState copy = new SearchState();
        copy.caseSensitive = source.caseSensitive;
        copy.selectedGroup = source.selectedGroup;
        if (source.groups == null) {
            return copy;
        }
        for (GroupState sourceGroup : source.groups) {
            if (sourceGroup == null) {
                continue;
            }
            GroupState group = new GroupState();
            if (sourceGroup.rows != null) {
                for (RowState sourceRow : sourceGroup.rows) {
                    if (sourceRow == null) {
                        continue;
                    }
                    RowState row = new RowState();
                    try {
                        row.type = MatchType.valueOf(sourceRow.type).name();
                    } catch (IllegalArgumentException | NullPointerException ignored) {
                        row.type = MatchType.TEXT.name();
                    }
                    row.first = sourceRow.first == null ? "" : sourceRow.first;
                    row.second = sourceRow.second == null ? "" : sourceRow.second;
                    row.excluded = sourceRow.excluded;
                    row.wholeWord = sourceRow.wholeWord;
                    group.rows.add(row);
                }
            }
            if (group.rows.isEmpty()) {
                group.rows.add(new RowState());
            }
            copy.groups.add(group);
        }
        return copy;
    }

    /** Params: preset - selected example. Returns: nothing; replaces the draft. */
    private void applyPreset(Preset preset) {
        if (preset == null) {
            return;
        }
        GroupState group = new GroupState();
        switch (preset) {
            case AND:
                group.rows.add(row(MatchType.TEXT, "taskId", "", false));
                group.rows.add(row(MatchType.TEXT, "status", "", false));
                group.rows.add(row(MatchType.TEXT, "success", "", false));
                break;
            case OR:
                group.rows.add(row(MatchType.ANY_OF, "ERROR\nWARN\nFATAL", "", false));
                break;
            case AND_NOT:
                group.rows.add(row(MatchType.TEXT, "success", "", false));
                group.rows.add(row(MatchType.TEXT, "retry", "", true));
                break;
            case AND_AND_NOT:
                group.rows.add(row(MatchType.TEXT, "taskId", "", false));
                group.rows.add(row(MatchType.TEXT, "ERROR", "", false));
                group.rows.add(row(MatchType.TEXT, "timeout", "", true));
                break;
            case OR_NOT:
                group.rows.add(row(MatchType.ANY_OF, "ERROR\nWARN", "", false));
                group.rows.add(row(MatchType.TEXT, "ignored", "", true));
                break;
            case MULTI_NOT:
                group.rows.add(row(MatchType.TEXT, "ERROR", "", false));
                group.rows.add(row(MatchType.ANY_OF, "timeout\nnetwork\nignored", "", true));
                break;
            case ORDER:
                group.rows.add(row(MatchType.ORDERED, "SELECT\nFROM", "", false));
                break;
            case VALUES:
                group.rows.add(row(MatchType.VALUE_DOUBLE_EQUALS, "status", "1\n2\n5", false));
                break;
            case ASSIGN:
                group.rows.add(row(MatchType.VALUE_EQUALS, "status", "SUCCESS", false));
                break;
            case DIGITS:
                group.rows.add(row(MatchType.DIGITS, "2", "5", false));
                break;
            case METHOD:
                group.rows.add(row(MatchType.METHOD_CALL, "service.execute", "", false));
                break;
            case STRING:
                group.rows.add(row(MatchType.STRING_CONTAINS, "success", "", false));
                break;
            case LITERAL:
                group.rows.add(row(MatchType.TEXT, "user.getName()", "", false));
                break;
            default:
                group.rows.add(new RowState());
        }
        draft.groups.clear();
        draft.groups.add(group);
        selectedGroup = 0;
        renderGroups();
    }

    /** Params: type, first, second, excluded - example values. Returns: one draft row. */
    private static RowState row(MatchType type, String first, String second, boolean excluded) {
        RowState row = new RowState();
        row.type = type.name();
        row.first = first;
        row.second = second;
        row.excluded = excluded;
        return row;
    }

    /**
     * A single editable condition card with literal input fields.
     * Author: zhoucb50741
     * date: 2026-10-14
     */
    private final class ConditionEditor {
        /** Condition card. */
        private final JPanel panel = new JPanel(new BorderLayout(JBUI.scale(6), JBUI.scale(5)));
        /** Positive/negative condition selector. */
        private final JComboBox<String> relation = new JComboBox<>(new String[]{"必须匹配", "不能匹配"});
        /** Selected scenario template. */
        private final JComboBox<Scenario> scenario = new JComboBox<>(Scenario.values());
        /** Main literal field. */
        private final JTextArea first = new JTextArea(2, 21);
        /** Secondary literal field. */
        private final JTextArea second = new JTextArea(2, 21);
        /** Identifier boundary preference. */
        private final JBCheckBox wholeWord = new JBCheckBox("完整词匹配");
        /** Scenario-specific input hints. */
        private final JBLabel hint = new JBLabel();
        /** Secondary field container, hidden for single-input templates. */
        private final JPanel secondPanel = new JPanel(new BorderLayout());

        /** Params: row - current draft. Returns: a ready-to-edit condition card. */
        private ConditionEditor(RowState row) {
            panel.setBorder(BorderFactory.createCompoundBorder(JBUI.Borders.empty(4),
                    BorderFactory.createTitledBorder("且满足以下条件")));
            JPanel options = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0));
            relation.setSelectedIndex(row.excluded ? 1 : 0);
            scenario.setSelectedItem(Scenario.fromType(MatchType.valueOf(row.type)));
            wholeWord.setSelected(row.wholeWord);
            wholeWord.setToolTipText("文本两侧不能紧邻字母、数字、下划线等标识符字符；关闭后允许子串匹配。");
            options.add(relation);
            options.add(scenario);
            options.add(wholeWord);
            options.add(button("删除", () -> {
                captureRows();
                int index = editors.indexOf(this);
                if (index >= 0) {
                    draft.groups.get(selectedGroup).rows.remove(index);
                    renderRows();
                }
            }));
            panel.add(options, BorderLayout.NORTH);
            first.setText(row.first);
            second.setText(row.second);
            first.setLineWrap(false);
            second.setLineWrap(false);
            JPanel inputs = new JPanel(new GridLayout(1, 2, JBUI.scale(8), 0));
            inputs.setBorder(JBUI.Borders.empty(0, 8, 0, 8));
            inputs.add(new JBScrollPane(first));
            secondPanel.add(new JBScrollPane(second), BorderLayout.CENTER);
            inputs.add(secondPanel);
            panel.add(inputs, BorderLayout.CENTER);
            hint.setBorder(JBUI.Borders.empty(0, 8, 6, 8));
            panel.add(hint, BorderLayout.SOUTH);
            DocumentListener listener = new DocumentListener() {
                /** Params: event - document change. Returns: nothing. */
                @Override public void insertUpdate(DocumentEvent event) { refreshPreview(); }
                /** Params: event - document change. Returns: nothing. */
                @Override public void removeUpdate(DocumentEvent event) { refreshPreview(); }
                /** Params: event - document change. Returns: nothing. */
                @Override public void changedUpdate(DocumentEvent event) { refreshPreview(); }
            };
            first.getDocument().addDocumentListener(listener);
            second.getDocument().addDocumentListener(listener);
            relation.addActionListener(event -> refreshPreview());
            wholeWord.addActionListener(event -> refreshPreview());
            scenario.addActionListener(event -> {
                updateHints();
                refreshPreview();
            });
            updateHints();
        }

        /** Params: none. Returns: nothing; explains the selected literal template. */
        private void updateHints() {
            Scenario selected = (Scenario) scenario.getSelectedItem();
            if (selected == null) {
                return;
            }
            hint.setText(selected.hint);
            first.setToolTipText(selected.hint);
            second.setToolTipText(selected.hint);
            secondPanel.setVisible(selected.twoFields);
            boolean fixedBoundary = selected.type == MatchType.PREFIX || selected.type == MatchType.TOKEN_NO_MEMBER;
            wholeWord.setEnabled(!fixedBoundary);
            wholeWord.setToolTipText(fixedBoundary ? "此模板固定检查标识符边界，复选框不改变其语义。"
                    : "文本两侧不能紧邻字母、数字、下划线等标识符字符；关闭后允许子串匹配。");
            panel.revalidate();
        }

        /** Params: none. Returns: an independent serializable snapshot. */
        private RowState snapshot() {
            Scenario selected = (Scenario) scenario.getSelectedItem();
            return conditionRow(selected == null ? MatchType.TEXT : selected.type);
        }

        /** Params: type - selected template. Returns: one row with current values. */
        private RowState conditionRow(MatchType type) {
            RowState row = new RowState();
            row.type = type.name();
            row.first = first.getText();
            row.second = second.getText();
            row.excluded = relation.getSelectedIndex() > 0;
            row.wholeWord = wholeWord.isSelected();
            return row;
        }
    }

    /**
     * Labels and field guidance for all supported templates.
     * Author: zhoucb50741
     * date: 2026-10-14
     */
    private enum Scenario {
        TEXT(MatchType.TEXT, "包含文本", false, "输入要查找的文本；例如 taskId。标点和特殊符号直接输入。"),
        ANY(MatchType.ANY_OF, "任意一个（或）", false, "每行一个备选值；例如 ERROR、WARN、FATAL 分别占一行。"),
        ORDER(MatchType.ORDERED, "按顺序出现", false, "每行一个文本，按从上到下的顺序出现；例如 SELECT、FROM。"),
        START(MatchType.STARTS_WITH, "以文本开头", false, "严格从行首开始，不自动忽略前导空格。"),
        END(MatchType.ENDS_WITH, "以文本结尾", false, "严格在行尾结束，不自动忽略尾部空格。"),
        NEXT(MatchType.EXACT_SEQUENCE, "A 后紧接 B", true, "左侧 A，右侧 B；不允许间隔，完整词边界作用于拼接后的整体。"),
        SPACE(MatchType.WHITESPACE_BETWEEN, "A、B 之间仅空白", true, "左侧 A，右侧 B；仅水平空白。hello/world 匹配 helloworld 时需关闭完整词。"),
        ASSIGN(MatchType.VALUE_EQUALS, "字段 = 指定值", true, "左侧字段名，右侧每行一个允许值；等号两侧允许水平空白。"),
        COMPARE(MatchType.VALUE_DOUBLE_EQUALS, "字段 == 指定值", true, "左侧字段名，右侧每行一个允许值；例如 status 和 1、2、5。"),
        NUMBER(MatchType.DIGITS, "连续数字", true, "左侧最少位数，右侧最多位数；都空为任意位，相同为固定长度。"),
        PREFIX(MatchType.PREFIX, "指定前缀的变量", false, "输入前缀，例如 task；匹配 taskId、taskName 等完整标识符。"),
        METHOD(MatchType.METHOD_CALL, "方法调用形式", false, "输入 execute 或 service.execute；允许方法名与左括号之间有空白。"),
        STRING(MatchType.STRING_CONTAINS, "双引号字符串包含", false, "输入字符串中的内容，例如 success；识别转义引号，限定同一行。"),
        MEMBER(MatchType.TOKEN_NO_MEMBER, "标识符，排除 .成员", false, "例如 user：排除 user.name、user.id；严格检查紧随其后的点号。");

        /** Engine template. */
        private final MatchType type;
        /** User-facing name. */
        private final String label;
        /** Whether both input fields apply. */
        private final boolean twoFields;
        /** Literal input explanation. */
        private final String hint;

        /** Params: type, label, twoFields, hint - display metadata. Returns: one option. */
        Scenario(MatchType type, String label, boolean twoFields, String hint) {
            this.type = type;
            this.label = label;
            this.twoFields = twoFields;
            this.hint = hint;
        }

        /** Params: type - engine template. Returns: its visual option. */
        private static Scenario fromType(MatchType type) {
            for (Scenario value : values()) {
                if (value.type == type) {
                    return value;
                }
            }
            return TEXT;
        }

        /** Params: none. Returns: the displayed template name. */
        @Override public String toString() { return label; }
    }

    /**
     * Editable examples taken from the user's regular-expression reference.
     * Author: zhoucb50741
     * date: 2026-10-14
     */
    private enum Preset {
        BLANK("空白条件"), AND("A 且 B 且 C"), OR("A 或 B 或 C"),
        AND_NOT("A 且不包含 B"), AND_AND_NOT("A 且 B 且不包含 C"),
        OR_NOT("A 或 B，且不包含 C"), MULTI_NOT("A，排除 B/C/D"),
        ORDER("A 必须在 B 前"), VALUES("status == 1/2/5"), ASSIGN("status = SUCCESS"),
        DIGITS("2～5 位数字"), METHOD("service.execute 调用形式"),
        STRING("字符串包含 success"), LITERAL("特殊符号 user.getName()");

        /** Displayed preset name. */
        private final String label;

        /** Params: label - displayed name. Returns: one preset. */
        Preset(String label) { this.label = label; }

        /** Params: none. Returns: the displayed preset name. */
        @Override public String toString() { return label; }
    }
}
