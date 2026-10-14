package com.novelreader.core.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * 网络状态探测（C5 漫画预加载「仅 WiFi」判断用）。
 *
 * 单独抽出来是为了让「是否 WiFi」这个判断与 UI 解耦，便于后续单测；
 * 真实判断走 [ConnectivityManager]，判断失败（无权限 / 无网络）一律视为非 WiFi，
 * 也就是「仅在 WiFi 下预加载」时不会误在移动网络下预加载。
 */
object NetworkStatus {

    /**
     * 当前是否连接 WiFi。
     *
     * 无网络、网络类型未知、探测失败都返回 false（保守：不预加载）。
     */
    fun isWifi(context: Context): Boolean {
        return runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return false
            val net = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        }.getOrDefault(false)
    }
}
