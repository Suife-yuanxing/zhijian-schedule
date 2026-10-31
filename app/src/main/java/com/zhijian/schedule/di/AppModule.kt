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
import com.zhijian.schedule.core.jw.script.ScriptStore
import com.zhijian.schedule.core.net.ExpiredCertTolerance
import com.zhijian.schedule.core.net.JwAssetCache
import com.zhijian.schedule.core.net.ToleratedTls
import com.zhijian.schedule.core.net.WebViewCookieJar
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
    ): AppDatabase =
        Room
            .databaseBuilder(context, AppDatabase::class.java, "zhijian_schedule.db")
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
    fun provideDataStore(
        @ApplicationContext context: Context,
    ): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            produceFile = { context.preferencesDataStoreFile("settings") },
        )

    @Provides
    @Singleton
    fun provideJson(): Json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            prettyPrint = false
        }

    /**
     * 通用客户端。
     *
     * TLS 走 [ToleratedTls]：系统 TrustManager 原样保留，只在"本校门户证书过期"
     * 这一种失败上放行（见 [ExpiredCertTolerance]）——WebView 能过、抓取也必须能过，
     * 否则用户登进去却抓不到课表，报的还是同一个证书错误。
     */
    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        val tls = ToleratedTls.create()
        return OkHttpClient
            .Builder()
            .cookieJar(WebViewCookieJar())
            .sslSocketFactory(tls.socketFactory, tls.trustManager)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    /**
     * 下载专用客户端。通用客户端的 readTimeout=20s 对 46 MB 的安装包不够用；
     * 且下载不该共享 WebView 的 cookie jar。
     * readTimeout 是「单次读取的间隔上限」，不是整个下载时长。
     */
    @Provides
    @Singleton
    @DownloadClient
    fun provideDownloadClient(): OkHttpClient =
        OkHttpClient
            .Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .build()

    @Provides
    @Singleton
    fun provideScriptStore(
        @ApplicationContext context: Context,
    ): ScriptStore = ScriptStore(File(context.filesDir, "jw-scripts"))

    /**
     * 教务门户静态资源缓存：教务 WebView 的 shouldInterceptRequest 用。
     * 与抓取共用同一个 [OkHttpClient]——后台预取门户静态资源时顺带预热了
     * 抓取课表要用的连接池。
     */
    @Provides
    @Singleton
    fun provideJwAssetCache(
        client: OkHttpClient,
        @ApplicationContext context: Context,
    ): JwAssetCache = JwAssetCache(client, File(context.cacheDir, "jw-assets"))
}
