package com.tangotori.app.di

import android.content.Context
import com.tangotori.app.data.cedict.CedictRepository
import com.tangotori.app.data.db.CedictAsset
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

    // ── CC-CEDICT ────────────────────────────────────────────────────────────

    /**
     * Opens cedict.db as a raw SQLiteDatabase (no Room) to avoid schema-
     * validation issues with the Python-generated pre-packaged asset. Returns
     * a repository that gracefully returns empty results when the asset is
     * absent — run `python build_cedict.py` at the project root to produce it.
     */
    @Provides
    @Singleton
    fun provideCedictRepository(@ApplicationContext ctx: Context): CedictRepository =
        CedictRepository(CedictAsset.open(ctx))
}
