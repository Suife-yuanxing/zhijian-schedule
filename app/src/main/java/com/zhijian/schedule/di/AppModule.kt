package com.zhijian.schedule.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import com.zhijian.schedule.core.data.db.AppDatabase
import com.zhijian.schedule.core.data.db.CourseDao
import com.zhijian.schedule.core.data.db.CourseNoteDao
import com.zhijian.schedule.core.data.db.ExamDao
import com.zhijian.schedule.core.data.db.GradeDao
import com.zhijian.schedule.core.data.db.HolidayDao
import com.zhijian.schedule.core.data.db.ImportSnapshotDao
import com.zhijian.schedule.core.data.db.MIGRATION_1_2
import com.zhijian.schedule.core.data.db.MIGRATION_2_3
import com.zhijian.schedule.core.data.db.TimeModeDao
import com.zhijian.schedule.core.data.db.TimetableDao
import com.zhijian.schedule.core.net.WebViewCookieJar
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "zhijian_schedule.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
            .build()

    @Provides
    fun provideTimetableDao(db: AppDatabase): TimetableDao = db.timetableDao()

    @Provides
    fun provideTimeModeDao(db: AppDatabase): TimeModeDao = db.timeModeDao()

    @Provides
    fun provideCourseDao(db: AppDatabase): CourseDao = db.courseDao()

    @Provides
    fun provideImportSnapshotDao(db: AppDatabase): ImportSnapshotDao = db.importSnapshotDao()

    @Provides
    fun provideExamDao(db: AppDatabase): ExamDao = db.examDao()

    @Provides
    fun provideCourseNoteDao(db: AppDatabase): CourseNoteDao = db.courseNoteDao()

    @Provides
    fun provideGradeDao(db: AppDatabase): GradeDao = db.gradeDao()

    @Provides
    fun provideHolidayDao(db: AppDatabase): HolidayDao = db.holidayDao()

    @Provides
    @Singleton
    fun provideDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            produceFile = { context.preferencesDataStoreFile("settings") }
        )

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .cookieJar(WebViewCookieJar())
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /**
     * 下载专用客户端。通用客户端的 readTimeout=20s 对 46 MB 的安装包不够用；
     * 且下载不该共享 WebView 的 cookie jar。
     * readTimeout 是「单次读取的间隔上限」，不是整个下载时长。
     */
    @Provides
    @Singleton
    @DownloadClient
    fun provideDownloadClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .followRedirects(true)
        .build()
}
