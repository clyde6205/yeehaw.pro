package app.yehaw.browser.console

import android.app.Activity
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import com.google.ads.mediation.admob.AdMobAdapter
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.OnPaidEventListener
import com.google.android.gms.ads.RequestConfiguration
import java.lang.ref.WeakReference

/**
 * Lean single-banner AdMob integration, controlled live by the founder console (`admob_enabled`).
 *
 * Safety rails:
 *  - Ads never show while the remote flag is off or no verified config has arrived yet.
 *  - A release build that still carries Google's TEST app id / ad unit id shows no ads (and logs why).
 *  - In regions that require opt-in (EEA/UK/CH) ads stay off until [setAdsConsent] is true, and then use
 *    non-personalised requests. Add Google's UMP consent form before relying on ads in those regions.
 *  - Impressions, clicks and paid events (real revenue from AdMob's paid-event callback) are forwarded to the backend.
 */
object AdMonetizationManager {
    private const val TAG = "AdMonetization"
    private const val PREFS = "yeehaw_console"
    private const val KEY_ADS_CONSENT = "ads_consent"
    private const val TEST_ID_MARK = "3940256099942544"

    private var activityRef: WeakReference<Activity>? = null
    private var container: ViewGroup? = null
    private var ownsContainer = false
    private var adView: AdView? = null
    private var sdkReady = false

    private val onConfig: (RemoteConfig) -> Unit = { apply() }

    /** Call once from MainActivity.onCreate after the layout is inflated. [bannerContainer] should sit above your bottom bar. */
    fun init(activity: Activity, bannerContainer: ViewGroup? = null) {
        activityRef = WeakReference(activity)
        container = bannerContainer
        RemoteConfigManager.removeListener(onConfig)
        RemoteConfigManager.addListener(onConfig)
        apply()
    }

    fun setAdsConsent(activity: Activity, granted: Boolean) {
        activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE).edit().putBoolean(KEY_ADS_CONSENT, granted).apply()
        apply()
    }

    fun onResume() { adView?.resume() }
    fun onPause() { adView?.pause() }
    fun onDestroy() {
        RemoteConfigManager.removeListener(onConfig)
        removeBanner()
        activityRef = null
    }

    private fun isDebuggable(a: Activity) = (a.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    private fun manifestAppId(a: Activity): String? = try {
        a.packageManager.getApplicationInfo(a.packageName, PackageManager.GET_META_DATA)
            .metaData?.getString("com.google.android.gms.ads.APPLICATION_ID")
    } catch (_: Throwable) {
        null
    }

    private fun consentOk(a: Activity): Boolean =
        !TelemetryManager.isConsentRequired() ||
            a.getSharedPreferences(PREFS, Activity.MODE_PRIVATE).getBoolean(KEY_ADS_CONSENT, false)

    private fun allowed(a: Activity): Boolean {
        if (!RemoteConfigManager.current.admobEnabled) return false
        if (!consentOk(a)) return false
        val testIds = BackendConfig.BANNER_AD_UNIT_ID.contains(TEST_ID_MARK) || (manifestAppId(a)?.contains(TEST_ID_MARK) == true)
        if (testIds && !isDebuggable(a)) {
            Log.w(TAG, "Release build is using Google's TEST AdMob ids: ads disabled. Set real ids first.")
            return false
        }
        return true
    }

    private fun apply() {
        val a = activityRef?.get() ?: return
        if (a.isFinishing || a.isDestroyed) return
        if (allowed(a)) showBanner(a) else removeBanner()
    }

    private fun ensureSdk(a: Activity) {
        if (sdkReady) return
        MobileAds.setRequestConfiguration(
            RequestConfiguration.Builder().setMaxAdContentRating(RequestConfiguration.MAX_AD_CONTENT_RATING_T).build()
        )
        MobileAds.initialize(a.applicationContext) { }
        sdkReady = true
    }

    private fun showBanner(a: Activity) {
        if (adView != null) return
        ensureSdk(a)
        val host = container ?: FrameLayout(a).also { f ->
            (a.findViewById<ViewGroup>(android.R.id.content)).addView(
                f, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
            )
            container = f
            ownsContainer = true
        }
        val unit = BackendConfig.BANNER_AD_UNIT_ID
        val view = AdView(a).apply {
            adUnitId = unit
            setAdSize(AdSize.BANNER) // 320x50: the lightest standard format
            adListener = object : AdListener() {
                override fun onAdImpression() { TelemetryManager.sendAdEvent("impression", "banner", unit, "admob") }
                override fun onAdClicked() { TelemetryManager.sendAdEvent("click", "banner", unit, "admob") }
                override fun onAdFailedToLoad(error: LoadAdError) { Log.d(TAG, "ad failed: ${error.code}") }
            }
            onPaidEventListener = OnPaidEventListener { v ->
                TelemetryManager.sendAdEvent("paid", "banner", unit, "admob", v.valueMicros, v.currencyCode)
            }
        }
        host.addView(view)
        adView = view
        val request = AdRequest.Builder().apply {
            if (TelemetryManager.isConsentRequired()) {
                addNetworkExtrasBundle(AdMobAdapter::class.java, Bundle().apply { putString("npa", "1") })
            }
        }.build()
        view.loadAd(request)
    }

    private fun removeBanner() {
        val v = adView ?: return
        (v.parent as? ViewGroup)?.removeView(v)
        v.destroy()
        adView = null
        if (ownsContainer) {
            (container?.parent as? ViewGroup)?.removeView(container)
            container = null
            ownsContainer = false
        }
    }
}
