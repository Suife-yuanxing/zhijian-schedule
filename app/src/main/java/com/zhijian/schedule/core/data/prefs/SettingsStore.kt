package com.zhijian.schedule.core.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.zhijian.schedule.core.theme.ThemeIds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

data class AppSettings(
    val activeTableId: Long = 0L,
    val themeId: String = ThemeIds.PAPER,
    val darkMode: Int = 0,
    val showWeekend: Boolean = false,
    val reminderEnabled: Boolean = true,
    val reminderMinutes: Int = 15,
    val autoSyncEnabled: Boolean = false,
    val jwUrl: String = "",
    val jwSchool: String = "",
    val lastSyncTime: Long = 0L,
    val calendarSyncEnabled: Boolean = false,
    val capsuleEnabled: Boolean = false,
    val gradesFetchedAt: Long = 0L,
    val holidayFetchedAt: Long = 0L,
    /** 「镜像加速（实验）」开关；默认关闭 —— 实测镜像比直连还慢，只在直连彻底跑不通时当退路 */
    val mirrorsEnabled: Boolean = false,
)

@Singleton
class SettingsStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    private object Keys {
        val ACTIVE_TABLE = longPreferencesKey("active_table_id")
        val THEME = stringPreferencesKey("theme_id")
        val DARK_MODE = intPreferencesKey("dark_mode")
        val SHOW_WEEKEND = booleanPreferencesKey("show_weekend")
        val REMINDER_ENABLED = booleanPreferencesKey("reminder_enabled")
        val REMINDER_MINUTES = intPreferencesKey("reminder_minutes")
        val AUTO_SYNC = booleanPreferencesKey("auto_sync_enabled")
        val JW_URL = stringPreferencesKey("jw_url")
        val JW_SCHOOL = stringPreferencesKey("jw_school")
        val LAST_SYNC = longPreferencesKey("last_sync_time")
        val CALENDAR_SYNC = booleanPreferencesKey("calendar_sync_enabled")
        val CAPSULE = booleanPreferencesKey("capsule_enabled")
        val GRADES_FETCHED = longPreferencesKey("grades_fetched_at")
        val HOLIDAY_FETCHED = longPreferencesKey("holiday_fetched_at")
        val MIRRORS = booleanPreferencesKey("update_mirrors_enabled")
    }

    val settings: Flow<AppSettings> = dataStore.data.map { p ->
        AppSettings(
            activeTableId = p[Keys.ACTIVE_TABLE] ?: 0L,
            themeId = p[Keys.THEME] ?: ThemeIds.PAPER,
            darkMode = p[Keys.DARK_MODE] ?: 0,
            showWeekend = p[Keys.SHOW_WEEKEND] ?: false,
            reminderEnabled = p[Keys.REMINDER_ENABLED] ?: true,
            reminderMinutes = p[Keys.REMINDER_MINUTES] ?: 15,
            autoSyncEnabled = p[Keys.AUTO_SYNC] ?: false,
            jwUrl = p[Keys.JW_URL] ?: "",
            jwSchool = p[Keys.JW_SCHOOL] ?: "",
            lastSyncTime = p[Keys.LAST_SYNC] ?: 0L,
            calendarSyncEnabled = p[Keys.CALENDAR_SYNC] ?: false,
            capsuleEnabled = p[Keys.CAPSULE] ?: false,
            gradesFetchedAt = p[Keys.GRADES_FETCHED] ?: 0L,
            holidayFetchedAt = p[Keys.HOLIDAY_FETCHED] ?: 0L,
            mirrorsEnabled = p[Keys.MIRRORS] ?: false,
        )
    }

    suspend fun setActiveTable(id: Long) = dataStore.edit { it[Keys.ACTIVE_TABLE] = id }
    suspend fun setTheme(id: String) = dataStore.edit { it[Keys.THEME] = id }
    suspend fun setDarkMode(mode: Int) = dataStore.edit { it[Keys.DARK_MODE] = mode }
    suspend fun setShowWeekend(show: Boolean) = dataStore.edit { it[Keys.SHOW_WEEKEND] = show }
    suspend fun setReminder(enabled: Boolean, minutes: Int) = dataStore.edit {
        it[Keys.REMINDER_ENABLED] = enabled
        it[Keys.REMINDER_MINUTES] = minutes
    }
    suspend fun setAutoSync(enabled: Boolean) = dataStore.edit { it[Keys.AUTO_SYNC] = enabled }
    suspend fun setJw(url: String, school: String) = dataStore.edit {
        it[Keys.JW_URL] = url
        it[Keys.JW_SCHOOL] = school
    }
    suspend fun setLastSyncTime(time: Long) = dataStore.edit { it[Keys.LAST_SYNC] = time }
    suspend fun setCalendarSync(enabled: Boolean) = dataStore.edit { it[Keys.CALENDAR_SYNC] = enabled }
    suspend fun setCapsule(enabled: Boolean) = dataStore.edit { it[Keys.CAPSULE] = enabled }
    suspend fun setGradesFetchedAt(time: Long) = dataStore.edit { it[Keys.GRADES_FETCHED] = time }
    suspend fun setHolidayFetchedAt(time: Long) = dataStore.edit { it[Keys.HOLIDAY_FETCHED] = time }
    suspend fun setMirrorsEnabled(enabled: Boolean) = dataStore.edit { it[Keys.MIRRORS] = enabled }
}
