package com.nutriops.app.di

import android.content.Context
import coil.ImageLoader
import coil.disk.DiskCache
import coil.intercept.Interceptor
import coil.memory.MemoryCache
import coil.request.CachePolicy
import coil.request.ImageResult
import coil.size.Dimension
import coil.size.Precision
import coil.size.Size
import com.nutriops.app.config.AppConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Coil Interceptor that caps every image decode to [AppConfig.IMAGE_MAX_DIMENSION_PX].
 * If a request already specifies a smaller size, that size is preserved.
 * This guarantees all image loads are downsampled before entering the memory cache.
 */
private class DownsampleInterceptor : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val maxPx = AppConfig.IMAGE_MAX_DIMENSION_PX
        val request = chain.request
        val size = chain.size

        val cappedSize = when {
            size is Size && size.width is Dimension.Pixels && size.height is Dimension.Pixels -> {
                val w = (size.width as Dimension.Pixels).px
                val h = (size.height as Dimension.Pixels).px
                if (w > maxPx || h > maxPx) Size(maxPx, maxPx) else size
            }
            // If size is Original or undefined, cap it
            else -> Size(maxPx, maxPx)
        }

        val newRequest = request.newBuilder()
            .size(cappedSize)
            .precision(Precision.INEXACT)
            .build()

        return chain.withSize(cappedSize).proceed(newRequest)
    }
}

@Module
@InstallIn(SingletonComponent::class)
object ImageLoaderModule {

    @Provides
    @Singleton
    fun provideImageLoader(@ApplicationContext context: Context): ImageLoader {
        return ImageLoader.Builder(context)
            .memoryCache {
                MemoryCache.Builder(context)
                    .maxSizeBytes(AppConfig.IMAGE_LRU_CACHE_MB * 1024 * 1024)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("image_cache"))
                    .maxSizeBytes(50L * 1024 * 1024)
                    .build()
            }
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .crossfade(true)
            .components {
                add(DownsampleInterceptor())
            }
            .build()
    }
}
