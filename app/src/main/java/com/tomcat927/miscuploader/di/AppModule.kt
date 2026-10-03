package com.tomcat927.miscuploader.di

import android.content.Context
import androidx.room.Room
import com.tomcat927.miscuploader.data.db.AppDatabase
import com.tomcat927.miscuploader.data.db.UploadDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient

/**
 * 只提供无法用 @Inject 构造的基座(OkHttp / App 级协程域 / Room)。
 * PasswordCrypto / SettingsRepository / ConnectionManager / UploadRepository 均走各自的 @Inject 构造器。
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideAppScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "misc-uploader.db").build()

    @Provides
    @Singleton
    fun provideUploadDao(db: AppDatabase): UploadDao = db.uploadDao()
}
