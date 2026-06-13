package com.tangotori.app.data.billing

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.tangotori.app.BuildConfig
import com.tangotori.app.data.compound.DeviceIdProvider
import com.tangotori.app.data.settings.AppPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One-time "usage boost" purchases via Google Play Billing.
 *
 * Products (must exist as ACTIVE in-app products in Play Console):
 * - [PRODUCT_8X]  — 8x daily limit, $5
 * - [PRODUCT_20X] — 20x daily limit, $10
 * - [PRODUCT_UPGRADE_20X] — 8x → 20x upgrade, $5. Play has no native
 *   "pay the difference" for one-time products, so the upgrade is its own
 *   product, surfaced in the UI only to 8x owners.
 *
 * Entitlement → tier: owning 20x or the upgrade ⇒ 20; owning only 8x ⇒ 8;
 * else 1 (free). The resolved tier is cached in SharedPreferences so request
 * plumbing (UsageBudgetManager) can read it synchronously before Play connects.
 */
@Singleton
class BillingRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val deviceIdProvider: DeviceIdProvider,
    private val appPrefs: AppPreferences,
) : PurchasesUpdatedListener {

    // Repository-lifetime scope for the fire-and-forget server verification.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val prefs by lazy {
        context.getSharedPreferences("tango_tori", Context.MODE_PRIVATE)
    }

    private val _tier = MutableStateFlow(1)
    /** Current usage tier: 1 (free), 8, or 20. */
    val tier: StateFlow<Int> = _tier.asStateFlow()

    private val _products = MutableStateFlow<Map<String, ProductDetails>>(emptyMap())
    /** productId → details (for live price strings). Empty until Play responds. */
    val products: StateFlow<Map<String, ProductDetails>> = _products.asStateFlow()

    private val _ownsBase8x = MutableStateFlow(false)
    /** Owns the 8x product (controls whether the upgrade row is shown). */
    val ownsBase8x: StateFlow<Boolean> = _ownsBase8x.asStateFlow()

    private val _billingReady = MutableStateFlow(false)
    /** Play connection is up and product details loaded. */
    val billingReady: StateFlow<Boolean> = _billingReady.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    /** One-shot user-facing billing message (purchase success/failure). */
    val message: StateFlow<String?> = _message.asStateFlow()

    fun consumeMessage() { _message.value = null }

    /** Runs after a purchase lands (used to lift the local daily-limit block). */
    var onEntitlementGranted: (() -> Unit)? = null

    private val billingClient: BillingClient by lazy {
        BillingClient.newBuilder(context)
            .setListener(this)
            .enablePendingPurchases(
                PendingPurchasesParams.newBuilder().enableOneTimeProducts().build()
            )
            .build()
    }

    init {
        _tier.value = prefs.getInt(KEY_TIER, 1)
        _ownsBase8x.value = prefs.getBoolean(KEY_OWNS_8X, false)
    }

    /**
     * Connects to Play (if needed), loads product details, and re-queries owned
     * purchases. Safe to call repeatedly — also serves as "Restore purchases".
     */
    fun refresh() {
        when (billingClient.connectionState) {
            BillingClient.ConnectionState.CONNECTED -> {
                queryProducts(); queryEntitlements()
            }
            BillingClient.ConnectionState.CONNECTING -> Unit
            else -> billingClient.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) {
                    if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                        queryProducts(); queryEntitlements()
                    } else {
                        _billingReady.value = false
                    }
                }

                override fun onBillingServiceDisconnected() {
                    _billingReady.value = false
                }
            })
        }
    }

    /** Launches the Play purchase sheet for [productId]. */
    fun launchPurchase(activity: Activity, productId: String) {
        val details = _products.value[productId]
        if (details == null) {
            _message.value = "Google Play billing isn't available right now."
            return
        }
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(details)
                        .build()
                )
            )
            .build()
        billingClient.launchBillingFlow(activity, params)
    }

    // ── Internals ────────────────────────────────────────────────────────────

    private fun queryProducts() {
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                ALL_PRODUCTS.map {
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(it)
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build()
                }
            )
            .build()
        billingClient.queryProductDetailsAsync(params) { result, queryResult ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                val detailsList = queryResult.productDetailsList
                _products.value = detailsList.associateBy { it.productId }
                _billingReady.value = detailsList.isNotEmpty()
            }
        }
    }

    private fun queryEntitlements() {
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()
        billingClient.queryPurchasesAsync(params) { result, purchases ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                applyPurchases(purchases, fromPurchaseFlow = false)
            }
        }
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: List<Purchase>?) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK ->
                applyPurchases(purchases.orEmpty(), fromPurchaseFlow = true)
            BillingClient.BillingResponseCode.USER_CANCELED -> Unit
            else -> _message.value = "Purchase didn't complete. You haven't been charged."
        }
    }

    private fun applyPurchases(purchases: List<Purchase>, fromPurchaseFlow: Boolean) {
        val owned = purchases
            .filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }
            .flatMap { it.products }
            .toSet()

        // One-time entitlements must be acknowledged within 3 days or Play
        // auto-refunds them.
        purchases
            .filter { it.purchaseState == Purchase.PurchaseState.PURCHASED && !it.isAcknowledged }
            .forEach { purchase ->
                billingClient.acknowledgePurchase(
                    AcknowledgePurchaseParams.newBuilder()
                        .setPurchaseToken(purchase.purchaseToken)
                        .build()
                ) { /* best-effort; re-tried on next refresh() if it failed */ }
            }

        val newTier = when {
            PRODUCT_20X in owned || PRODUCT_UPGRADE_20X in owned -> 20
            PRODUCT_8X in owned -> 8
            else -> 1
        }
        val tierIncreased = newTier > _tier.value
        _tier.value = newTier
        _ownsBase8x.value = PRODUCT_8X in owned
        prefs.edit()
            .putInt(KEY_TIER, newTier)
            .putBoolean(KEY_OWNS_8X, PRODUCT_8X in owned)
            .apply()

        if (tierIncreased) {
            onEntitlementGranted?.invoke()
            if (fromPurchaseFlow) {
                _message.value = "Thank you! Your ${newTier}x usage boost is active."
            }
        }

        // Report the full owned set to the worker, which verifies each token
        // against the Google Play Developer API and records the device's
        // server-side tier (the one the budget actually trusts).
        verifyWithServer(
            purchases.filter { it.purchaseState == Purchase.PurchaseState.PURCHASED },
        )
    }

    /**
     * Fire-and-forget POST of the device's owned purchase tokens to the
     * worker's /verify_purchase. The worker checks them with Google and stores
     * the verified tier in D1; on success we adopt its tier as authoritative.
     * 501 = verification not configured server-side (worker then trusts the
     * client-claimed tier); network errors are ignored — retried implicitly on
     * the next refresh().
     */
    private fun verifyWithServer(owned: List<Purchase>) {
        scope.launch {
            // AI features off = the user opted out of ALL backend communication.
            // Verification resumes on the next refresh() after re-enabling.
            if (!appPrefs.aiFeaturesEnabled.first()) return@launch
            try {
                val arr = JSONArray()
                owned.forEach { p ->
                    p.products
                        .filter { it in ALL_PRODUCTS }
                        .forEach { pid ->
                            arr.put(
                                JSONObject()
                                    .put("product_id", pid)
                                    .put("purchase_token", p.purchaseToken)
                            )
                        }
                }
                val body = JSONObject()
                    .put("device_id", deviceIdProvider.deviceId)
                    .put("purchases", arr)
                    .toString()

                val conn = URL("${BuildConfig.COMPOUND_API_URL}/verify_purchase")
                    .openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.connectTimeout = 10_000
                conn.readTimeout = 15_000
                conn.outputStream.use { it.write(body.toByteArray()) }

                if (conn.responseCode == 200) {
                    val verified = JSONObject(conn.inputStream.bufferedReader().readText())
                        .optInt("tier", -1)
                    if (verified in listOf(1, 8, 20) && verified != _tier.value) {
                        _tier.value = verified
                        prefs.edit().putInt(KEY_TIER, verified).apply()
                    }
                }
            } catch (_: Exception) {
                // Offline / worker unreachable — local tier stands until next refresh.
            }
        }
    }

    companion object {
        const val PRODUCT_8X = "usage_boost_8x"
        const val PRODUCT_20X = "usage_boost_20x"
        const val PRODUCT_UPGRADE_20X = "usage_boost_upgrade_20x"
        val ALL_PRODUCTS = listOf(PRODUCT_8X, PRODUCT_20X, PRODUCT_UPGRADE_20X)

        private const val KEY_TIER = "usage_tier"
        private const val KEY_OWNS_8X = "owns_boost_8x"
    }
}
