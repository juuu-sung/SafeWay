package com.safeway.app

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.provider.Settings
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.kakaomobility.knsdk.KNLanguageType
import com.kakaomobility.knsdk.KNRouteAvoidOption
import com.kakaomobility.knsdk.KNRoutePriority
import com.kakaomobility.knsdk.KNSDK
import com.kakaomobility.knsdk.common.objects.KNError
import com.kakaomobility.knsdk.common.objects.KNPOI
import com.kakaomobility.knsdk.guidance.knguidance.KNGuideRouteChangeReason
import com.kakaomobility.knsdk.guidance.knguidance.KNGuidance
import com.kakaomobility.knsdk.guidance.knguidance.KNGuidance_CitsGuideDelegate
import com.kakaomobility.knsdk.guidance.knguidance.KNGuidance_GuideStateDelegate
import com.kakaomobility.knsdk.guidance.knguidance.KNGuidance_LocationGuideDelegate
import com.kakaomobility.knsdk.guidance.knguidance.KNGuidance_RouteGuideDelegate
import com.kakaomobility.knsdk.guidance.knguidance.KNGuidance_SafetyGuideDelegate
import com.kakaomobility.knsdk.guidance.knguidance.KNGuidance_VoiceGuideDelegate
import com.kakaomobility.knsdk.guidance.knguidance.citsguide.KNGuide_Cits
import com.kakaomobility.knsdk.guidance.knguidance.common.KNLocation
import com.kakaomobility.knsdk.guidance.knguidance.locationguide.KNGuide_Location
import com.kakaomobility.knsdk.guidance.knguidance.routeguide.KNGuide_Route
import com.kakaomobility.knsdk.guidance.knguidance.routeguide.objects.KNMultiRouteInfo
import com.kakaomobility.knsdk.guidance.knguidance.safetyguide.KNGuide_Safety
import com.kakaomobility.knsdk.guidance.knguidance.safetyguide.objects.KNSafety
import com.kakaomobility.knsdk.guidance.knguidance.voiceguide.KNGuide_Voice
import com.kakaomobility.knsdk.trip.kntrip.knroute.KNRoute
import com.kakaomobility.knsdk.ui.view.KNNaviView
import kotlin.math.roundToInt

class InlineKakaoNaviView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs),
    InlineNaviController,
    KNGuidance_GuideStateDelegate,
    KNGuidance_LocationGuideDelegate,
    KNGuidance_RouteGuideDelegate,
    KNGuidance_SafetyGuideDelegate,
    KNGuidance_VoiceGuideDelegate,
    KNGuidance_CitsGuideDelegate {

    private var naviView: KNNaviView? = null
    private val statusText = TextView(context)
    private var guideStarted = false

    init {
        setBackgroundResource(R.drawable.bg_card)
        statusText.gravity = Gravity.CENTER
        statusText.setPadding(dp(18), dp(18), dp(18), dp(18))
        statusText.setTextColor(ContextCompat.getColor(context, R.color.safeway_muted))
        statusText.textSize = 12f
        statusText.setTypeface(null, Typeface.BOLD)
        statusText.text = "길안내 시작을 누르면 이 칸에 카카오내비가 표시됩니다."
        addView(
            statusText,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        )
    }

    override fun startGuidance(
        originLat: Double,
        originLng: Double,
        destinationLat: Double,
        destinationLng: Double,
        destinationName: String
    ) {
        if (!hasLocationPermission()) {
            showStatus("위치 권한이 없어 카카오내비 길안내를 시작할 수 없습니다.")
            return
        }
        val appKey = BuildConfig.KAKAO_NATIVE_APP_KEY.trim()
        if (appKey.isEmpty()) {
            showStatus("local.properties에 KAKAO_NATIVE_APP_KEY를 먼저 설정해야 합니다.")
            return
        }

        stopGuidance()
        KNSDK.install(context.applicationContext as Application, "${context.filesDir.absolutePath}/knsdk")
        showStatus("카카오내비 SDK를 초기화하는 중입니다.")
        KNSDK.initializeWithAppKey(
            appKey,
            BuildConfig.VERSION_NAME,
            userKey(),
            "",
            KNLanguageType.KNLanguageType_KOREAN
        ) { error ->
            post {
                if (error != null) {
                    showStatus("카카오내비 SDK 초기화 실패: ${formatError(error)}")
                } else {
                    ensureNaviView()
                    requestRoute(originLat, originLng, destinationLat, destinationLng, destinationName)
                }
            }
        }
    }

    override fun onHostResume() {
        KNSDK.handleDidBecomeActive()
    }

    override fun onHostPause() {
        KNSDK.handleWillResignActive()
    }

    override fun onHostDestroy() {
        stopGuidance()
    }

    private fun ensureNaviView(): KNNaviView {
        val existing = naviView
        if (existing != null) {
            return existing
        }
        val created = KNNaviView(context)
        naviView = created
        addView(
            created,
            0,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        )
        return created
    }

    private fun requestRoute(
        originLat: Double,
        originLng: Double,
        destinationLat: Double,
        destinationLng: Double,
        destinationName: String
    ) {
        showStatus("카카오내비 경로를 요청하는 중입니다.")
        val start = toPoi("현재 위치", originLat, originLng)
        val goal = toPoi(destinationName.ifBlank { "도착지" }, destinationLat, destinationLng)
        KNSDK.makeTripWithStart(start, goal, null, null) { tripError, trip ->
            post {
                if (tripError != null || trip == null) {
                    showStatus("카카오내비 경로 생성 실패: ${formatError(tripError)}")
                    return@post
                }

                val routePriority = KNRoutePriority.KNRoutePriority_Recommand
                val avoidOptions = KNRouteAvoidOption.KNRouteAvoidOption_None.value
                trip.routeWithPriority(routePriority, avoidOptions) { routeError, _ ->
                    post {
                        if (routeError != null) {
                            showStatus("카카오내비 경로 요청 실패: ${formatError(routeError)}")
                            return@post
                        }

                        val guidance = KNSDK.sharedGuidance()
                        if (guidance == null) {
                            showStatus("카카오내비 안내 객체를 만들지 못했습니다.")
                            return@post
                        }
                        guidance.guideStateDelegate = this
                        guidance.locationGuideDelegate = this
                        guidance.routeGuideDelegate = this
                        guidance.safetyGuideDelegate = this
                        guidance.voiceGuideDelegate = this
                        guidance.citsGuideDelegate = this
                        ensureNaviView().initWithGuidance(guidance, trip, routePriority, avoidOptions)
                    }
                }
            }
        }
    }

    private fun toPoi(name: String, latitude: Double, longitude: Double): KNPOI {
        val katec = KNSDK.convertWGS84ToKATEC(longitude, latitude)
        return KakaoNaviPoiFactory.fromKatec(
            name,
            katec.x.roundToInt(),
            katec.y.roundToInt()
        )
    }

    private fun showStatus(message: String) {
        statusText.text = message
        statusText.visibility = View.VISIBLE
    }

    private fun hideStatus() {
        statusText.visibility = View.GONE
    }

    private fun stopGuidance() {
        if (guideStarted) {
            runCatching { KNSDK.sharedGuidance()?.stop() }
            naviView?.let { view -> runCatching { view.guideCancel() } }
            guideStarted = false
        }
    }

    private fun formatError(error: KNError?): String {
        if (error == null) {
            return "알 수 없는 오류"
        }
        val code = error.code.orEmpty()
        val msg = error.msg.orEmpty()
        return when {
            code.isNotBlank() && msg.isNotBlank() -> "$code $msg"
            code.isNotBlank() -> code
            msg.isNotBlank() -> msg
            else -> error.toString()
        }
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun userKey(): String {
        return Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?: "safeway-user"
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density + 0.5f).toInt()
    }

    override fun guidanceGuideStarted(guidance: KNGuidance) {
        guideStarted = true
        hideStatus()
        naviView?.guidanceGuideStarted(guidance)
    }

    override fun guidanceCheckingRouteChange(guidance: KNGuidance) {
        naviView?.guidanceCheckingRouteChange(guidance)
    }

    override fun guidanceRouteUnchanged(guidance: KNGuidance) {
        naviView?.guidanceRouteUnchanged(guidance)
    }

    override fun guidanceRouteUnchangedWithError(guidance: KNGuidance, error: KNError) {
        naviView?.guidanceRouteUnchangedWithError(guidance, error)
    }

    override fun guidanceOutOfRoute(guidance: KNGuidance) {
        naviView?.guidanceOutOfRoute(guidance)
    }

    override fun guidanceRouteChanged(
        guidance: KNGuidance,
        fromRoute: KNRoute,
        fromLocation: KNLocation,
        toRoute: KNRoute,
        toLocation: KNLocation,
        reason: KNGuideRouteChangeReason
    ) {
        naviView?.guidanceRouteChanged(guidance)
    }

    override fun guidanceGuideEnded(guidance: KNGuidance) {
        naviView?.guidanceGuideEnded(guidance, true)
        showStatus("카카오내비 길안내가 종료되었습니다.")
    }

    override fun guidanceDidUpdateRoutes(
        guidance: KNGuidance,
        routes: List<KNRoute>,
        multiRouteInfo: KNMultiRouteInfo?
    ) {
        naviView?.guidanceDidUpdateRoutes(guidance, routes, multiRouteInfo)
    }

    override fun guidanceDidUpdateIndoorRoute(guidance: KNGuidance, route: KNRoute?) {
        if (route != null) {
            naviView?.guidanceDidUpdateIndoorRoute(guidance, route)
        }
    }

    override fun guidanceDidUpdateLocation(guidance: KNGuidance, locationGuide: KNGuide_Location) {
        naviView?.guidanceDidUpdateLocation(guidance, locationGuide)
    }

    override fun guidanceDidUpdateRouteGuide(guidance: KNGuidance, routeGuide: KNGuide_Route) {
        naviView?.guidanceDidUpdateRouteGuide(guidance, routeGuide)
    }

    override fun guidanceDidUpdateSafetyGuide(guidance: KNGuidance, safetyGuide: KNGuide_Safety?) {
        naviView?.guidanceDidUpdateSafetyGuide(guidance, safetyGuide)
    }

    override fun guidanceDidUpdateAroundSafeties(guidance: KNGuidance, safeties: List<KNSafety>?) {
        naviView?.guidanceDidUpdateAroundSafeties(guidance, safeties)
    }

    override fun shouldPlayVoiceGuide(
        guidance: KNGuidance,
        voiceGuide: KNGuide_Voice,
        newData: MutableList<ByteArray>
    ): Boolean {
        return naviView?.shouldPlayVoiceGuide(guidance, voiceGuide, newData) ?: true
    }

    override fun willPlayVoiceGuide(guidance: KNGuidance, voiceGuide: KNGuide_Voice) {
        naviView?.willPlayVoiceGuide(guidance, voiceGuide)
    }

    override fun didFinishPlayVoiceGuide(guidance: KNGuidance, voiceGuide: KNGuide_Voice) {
        naviView?.didFinishPlayVoiceGuide(guidance, voiceGuide)
    }

    override fun didUpdateCitsGuide(guidance: KNGuidance, citsGuide: KNGuide_Cits) {
        naviView?.didUpdateCitsGuide(guidance, citsGuide)
    }
}
