package com.tangotori.app.di

import android.content.Context
import com.tangotori.app.data.db.JmdictDao
import com.tangotori.app.data.db.JmdictDatabase
import com.tangotori.app.data.sudachi.FirstLaunchDictionaryDownloader
import com.tangotori.app.data.sudachi.SudachiAssetInstaller
import com.tangotori.app.data.sudachi.SudachiTokenizer
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideSudachiInstaller(@ApplicationContext ctx: Context): SudachiAssetInstaller =
        SudachiAssetInstaller(ctx)

    @Provides
    @Singleton
    fun provideSudachi(
        @ApplicationContext ctx: Context,
        installer: SudachiAssetInstaller,
    ): SudachiTokenizer = SudachiTokenizer(ctx, installer)

    @Provides
    @Singleton
    fun provideDownloader(@ApplicationContext ctx: Context): FirstLaunchDictionaryDownloader =
        FirstLaunchDictionaryDownloader(ctx)

    @Provides
    @Singleton
    fun provideJmdictDb(@ApplicationContext ctx: Context): JmdictDatabase = JmdictDatabase.build(ctx)

    @Provides
    fun provideJmdictDao(db: JmdictDatabase): JmdictDao = db.jmdictDao()

    // CardTargetRepository and AnkiPreferences are @Singleton classes with
    // @Inject constructors, so Hilt provides them automatically; no extra
    // @Provides method needed.
}
