package com.victor.kodiakmaps

import android.app.Application
import com.victor.kodiakmaps.data.Prefs
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import org.maplibre.android.MapLibre
import org.maplibre.android.module.http.HttpRequestUtil

class KodiakMapsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.load(this)
        com.victor.kodiakmaps.service.TrackService.channel(this)
        MapLibre.getInstance(this)
        // Be polite to the public USGS / OpenTopoMap tile servers: few parallel requests and an honest User-Agent.
        val dispatcher = Dispatcher().apply { maxRequests = 6; maxRequestsPerHost = 2 }
        HttpRequestUtil.setOkHttpClient(OkHttpClient.Builder().dispatcher(dispatcher).addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder()
                .header("User-Agent", "KodiakMaps/${BuildConfig.VERSION_NAME} (Android; personal offline topo; com.victor.kodiakmaps)").build())
        }.build())
    }
}
