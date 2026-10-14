package com.zhoucb.searchenhance.settings;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Stores the last condition draft in this project's local workspace.
 * Author: zhoucb50741
 * date: 2026-10-14
 */
@Service(Service.Level.PROJECT)
@State(name = "SearchEnhanceSettings", storages = @Storage("workspace.xml"))
public final class SearchSettings implements PersistentStateComponent<SearchSettings.SearchState> {
    /** Current project-local draft. */
    private SearchState state = new SearchState();

    /**
     * Params: project - owning project.
     * Returns: the project-local settings service.
     */
    public static SearchSettings getInstance(Project project) {
        return project.getService(SearchSettings.class);
    }

    /**
     * Params: none.
     * Returns: the serializable condition draft.
     */
    @Override
    public @NotNull SearchState getState() {
        return state;
    }

    /**
     * Params: value - restored or newly saved draft.
     * Returns: nothing.
     */
    @Override
    public void loadState(@NotNull SearchState value) {
        state = value;
    }

    /**
     * Serializable search preferences.
     * Author: zhoucb50741
     * date: 2026-10-14
     */
    public static final class SearchState {
        /** Whether case must match. */
        public boolean caseSensitive;
        /** Last selected alternative group. */
        public int selectedGroup;
        /** Alternative groups, combined by OR. */
        public List<GroupState> groups = new ArrayList<>();
    }

    /**
     * Serializable conjunction of conditions.
     * Author: zhoucb50741
     * date: 2026-10-14
     */
    public static final class GroupState {
        /** Conditions combined by AND or AND NOT. */
        public List<RowState> rows = new ArrayList<>();
    }

    /**
     * Serializable condition, including temporarily incomplete input.
     * Author: zhoucb50741
     * date: 2026-10-14
     */
    public static final class RowState {
        /** MatchType enum name. */
        public String type = "TEXT";
        /** Main literal or newline-separated alternatives. */
        public String first = "";
        /** Secondary literal, values or numeric limit. */
        public String second = "";
        /** Whether this condition excludes matching lines. */
        public boolean excluded;
        /** Whether to require identifier boundaries. */
        public boolean wholeWord = true;
    }
}
