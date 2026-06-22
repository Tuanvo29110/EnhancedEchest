package com.enhancedechest.model;

/**
 * Per-player UI/behaviour preferences, persisted one row per player in {@code player_settings}.
 *
 * <p>This is the general settings container: it is loaded whole and saved whole, so callers never
 * deal with individual columns. <b>To add a new setting:</b>
 * <ol>
 *   <li>add a component to this record (and to {@link #defaults()} / a {@code withX} helper);</li>
 *   <li>add the matching column to the {@code player_settings} DDL in all three storage dialects
 *       (SqliteStorage / MysqlStorage / PostgresStorage) — for an existing install, ship an
 *       {@code ALTER TABLE ... ADD COLUMN ... DEFAULT ...} (portable across all four engines);</li>
 *   <li>map the column in {@code AbstractSqlStorage.loadSettings}/{@code saveSettings}.</li>
 * </ol>
 * Defaults live in {@link #defaults()} so a player with no row behaves identically to a fresh one.
 *
 * @param editMode whether the management list opens in edit mode (clicking a chest opens its detail
 *                 dialog rather than the chest itself); remembered across sessions
 */
public record PlayerSettings(boolean editMode) {

    /** Settings for a player who has never saved any — every field at its default. */
    public static PlayerSettings defaults() {
        return new PlayerSettings(false);
    }

    /** Returns a copy with {@code editMode} changed, leaving every other setting untouched. */
    public PlayerSettings withEditMode(boolean editMode) {
        return new PlayerSettings(editMode);
    }
}
