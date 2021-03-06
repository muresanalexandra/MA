/*
 * Copyright 2017 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ro.ubbcluj.cs.ds.billing

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.BillingResponse
import com.android.billingclient.api.BillingClient.FeatureType
import com.android.billingclient.api.BillingClient.SkuType
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.ConsumeResponseListener
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.Purchase.PurchasesResult
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.SkuDetailsParams
import com.android.billingclient.api.SkuDetailsResponseListener
import java.io.IOException
import java.util.ArrayList
import java.util.HashSet

/**
 * Handles all the interactions with Play Store (via Billing library), maintains connection to
 * it through BillingClient and caches temporary states/data if needed
 */
class BillingManager(private val mActivity: Activity, private val mBillingUpdatesListener: BillingUpdatesListener) : PurchasesUpdatedListener {

  /** A reference to BillingClient  */
  private var mBillingClient: BillingClient? = null

  /**
   * True if billing service is connected now.
   */
  private var mIsServiceConnected: Boolean = false

  private val mPurchases = ArrayList<Purchase>()

  private var mTokensToBeConsumed: MutableSet<String>? = null

  /**
   * Returns the value Billing client response code or BILLING_MANAGER_NOT_INITIALIZED if the
   * client connection response was not received yet.
   */
  var billingClientResponseCode = BILLING_MANAGER_NOT_INITIALIZED
    private set

  val context: Context
    get() = mActivity

  /**
   * Listener to the updates that happen when purchases list was updated or consumption of the
   * item was finished
   */
  interface BillingUpdatesListener {
    fun onBillingClientSetupFinished()
    fun onConsumeFinished(token: String, @BillingResponse result: Int)
    fun onPurchasesUpdated(purchases: List<Purchase>)
  }

  init {
    Log.d(TAG, "Creating Billing client.")
    mBillingClient = BillingClient.newBuilder(mActivity).setListener(this).build()

    Log.d(TAG, "Starting setup.")

    // Start setup. This is asynchronous and the specified listener will be called
    // once setup completes.
    // It also starts to report all the new purchases through onPurchasesUpdated() callback.
    startServiceConnection(Runnable {
      // Notifying the listener that billing client is ready
      mBillingUpdatesListener.onBillingClientSetupFinished()
      // IAB is fully set up. Now, let's get an inventory of stuff we own.
      Log.d(TAG, "Setup successful. Querying inventory.")
      queryPurchases()
    })
  }

  /**
   * Handle a callback that purchases were updated from the Billing library
   */
  override fun onPurchasesUpdated(resultCode: Int, purchases: List<Purchase>?) {
    when (resultCode) {
      BillingResponse.OK -> {
        for (purchase in purchases!!) {
          handlePurchase(purchase)
        }
        mBillingUpdatesListener.onPurchasesUpdated(mPurchases)
      }
      BillingResponse.USER_CANCELED -> Log.i(TAG, "onPurchasesUpdated() - user cancelled the purchase flow - skipping")
      else -> Log.w(TAG, "onPurchasesUpdated() got unknown resultCode: $resultCode")
    }
  }

  /**
   * Start a purchase flow
   */
  fun initiatePurchaseFlow(skuId: String, @SkuType billingType: String) {
    initiatePurchaseFlow(skuId, null, billingType)
  }

  /**
   * Start a purchase or subscription replace flow
   */
  fun initiatePurchaseFlow(skuId: String, oldSkus: ArrayList<String>?,
                           @SkuType billingType: String) {
    val purchaseFlowRequest = Runnable {
      Log.d(TAG, "Launching in-app purchase flow. Replace old SKU? " + (oldSkus != null))
      val purchaseParams = BillingFlowParams.newBuilder()
          .setSku(skuId).setType(billingType).setOldSkus(oldSkus).build()
      mBillingClient!!.launchBillingFlow(mActivity, purchaseParams)
    }

    executeServiceRequest(purchaseFlowRequest)
  }

  /**
   * Clear the resources
   */
  fun destroy() {
    Log.d(TAG, "Destroying the manager.")

    if (mBillingClient != null && mBillingClient!!.isReady) {
      mBillingClient!!.endConnection()
      mBillingClient = null
    }
  }

  fun querySkuDetailsAsync(@SkuType itemType: String, skuList: List<String>,
                           listener: SkuDetailsResponseListener) {
    // Creating a runnable from the request to use it inside our connection retry policy below
    val queryRequest = Runnable {
      // Query the purchase async
      val params = SkuDetailsParams.newBuilder()
      params.setSkusList(skuList).setType(itemType)
      mBillingClient!!.querySkuDetailsAsync(params.build()
      ) { responseCode, skuDetailsList -> listener.onSkuDetailsResponse(responseCode, skuDetailsList) }
    }

    executeServiceRequest(queryRequest)
  }

  fun consumeAsync(purchaseToken: String) {
    // If we've already scheduled to consume this token - no action is needed (this could happen
    // if you received the token when querying purchases inside onReceive() and later from
    // onActivityResult()
    if (mTokensToBeConsumed == null) {
      mTokensToBeConsumed = HashSet()
    } else if (mTokensToBeConsumed!!.contains(purchaseToken)) {
      Log.i(TAG, "Token was already scheduled to be consumed - skipping...")
      return
    }
    mTokensToBeConsumed!!.add(purchaseToken)

    // Generating Consume Response listener
    val onConsumeListener = ConsumeResponseListener { responseCode, purchasedToken ->
      // If billing service was disconnected, we try to reconnect 1 time
      // (feel free to introduce your retry policy here).
      mBillingUpdatesListener.onConsumeFinished(purchasedToken, responseCode)
    }

    // Creating a runnable from the request to use it inside our connection retry policy below
    val consumeRequest = Runnable {
      // Consume the purchase async
      mBillingClient!!.consumeAsync(purchaseToken, onConsumeListener)
    }

    executeServiceRequest(consumeRequest)
  }

  /**
   * Handles the purchase
   *
   * Note: Notice that for each purchase, we check if signature is valid on the client.
   * It's recommended to move this check into your backend.
   * See [Security.verifyPurchase]
   *
   * @param purchase Purchase to be handled
   */
  private fun handlePurchase(purchase: Purchase) {
    if (!verifyValidSignature(purchase.originalJson, purchase.signature)) {
      Log.i(TAG, "Got a purchase: $purchase; but signature is bad. Skipping...")
      return
    }

    Log.d(TAG, "Got a verified purchase: $purchase")

    mPurchases.add(purchase)
  }

  /**
   * Handle a result from querying of purchases and report an updated list to the listener
   */
  private fun onQueryPurchasesFinished(result: PurchasesResult) {
    // Have we been disposed of in the meantime? If so, or bad result code, then quit
    if (mBillingClient == null || result.responseCode != BillingResponse.OK) {
      Log.w(TAG, "Billing client was null or result code (" + result.responseCode
          + ") was bad - quitting")
      return
    }

    Log.d(TAG, "Query inventory was successful.")

    // Update the UI and purchases inventory with new list of purchases
    mPurchases.clear()
    onPurchasesUpdated(BillingResponse.OK, result.purchasesList)
  }

  /**
   * Checks if subscriptions are supported for current client
   *
   * Note: This method does not automatically retry for RESULT_SERVICE_DISCONNECTED.
   * It is only used in unit tests and after queryPurchases execution, which already has
   * a retry-mechanism implemented.
   *
   */
  fun areSubscriptionsSupported(): Boolean {
    val responseCode = mBillingClient!!.isFeatureSupported(FeatureType.SUBSCRIPTIONS)
    if (responseCode != BillingResponse.OK) {
      Log.w(TAG, "areSubscriptionsSupported() got an error response: $responseCode")
    }
    return responseCode == BillingResponse.OK
  }

  /**
   * Query purchases across various use cases and deliver the result in a formalized way through
   * a listener
   */
  fun queryPurchases() {
    val queryToExecute = Runnable {
      val time = System.currentTimeMillis()
      val purchasesResult = mBillingClient!!.queryPurchases(SkuType.INAPP)
      Log.i(TAG, "Querying purchases elapsed time: " + (System.currentTimeMillis() - time)
          + "ms")
      // If there are subscriptions supported, we add subscription rows as well
      if (areSubscriptionsSupported()) {
        val subscriptionResult = mBillingClient!!.queryPurchases(SkuType.SUBS)
        Log.i(TAG, "Querying purchases and subscriptions elapsed time: "
            + (System.currentTimeMillis() - time) + "ms")
        Log.i(TAG, "Querying subscriptions result code: "
            + subscriptionResult.responseCode
            + " res: " + subscriptionResult.purchasesList.size)

        if (subscriptionResult.responseCode == BillingResponse.OK) {
          purchasesResult.purchasesList.addAll(
              subscriptionResult.purchasesList)
        } else {
          Log.e(TAG, "Got an error response trying to query subscription purchases")
        }
      } else if (purchasesResult.responseCode == BillingResponse.OK) {
        Log.i(TAG, "Skipped subscription purchases query since they are not supported")
      } else {
        Log.w(TAG, "queryPurchases() got an error response code: " + purchasesResult.responseCode)
      }
      onQueryPurchasesFinished(purchasesResult)
    }

    executeServiceRequest(queryToExecute)
  }

  private fun startServiceConnection(executeOnSuccess: Runnable?) {
    mBillingClient!!.startConnection(object : BillingClientStateListener {
      override fun onBillingSetupFinished(@BillingResponse billingResponseCode: Int) {
        Log.d(TAG, "Setup finished. Response code: $billingResponseCode")

        if (billingResponseCode == BillingResponse.OK) {
          mIsServiceConnected = true
          executeOnSuccess?.run()
        }
        billingClientResponseCode = billingResponseCode
      }

      override fun onBillingServiceDisconnected() {
        mIsServiceConnected = false
      }
    })
  }

  private fun executeServiceRequest(runnable: Runnable) {
    if (mIsServiceConnected) {
      runnable.run()
    } else {
      // If billing service was disconnected, we try to reconnect 1 time.
      // (feel free to introduce your retry policy here).
      startServiceConnection(runnable)
    }
  }

  /**
   * Verifies that the purchase was signed correctly for this developer's public key.
   *
   * Note: It's strongly recommended to perform such check on your backend since hackers can
   * replace this method with "constant true" if they decompile/rebuild your app.
   *
   */
  private fun verifyValidSignature(signedData: String, signature: String): Boolean {
    // Some sanity checks to see if the developer (that's you!) really followed the
    // instructions to run this sample (don't put these checks on your app!)
    if (BASE_64_ENCODED_PUBLIC_KEY.contains("CONSTRUCT_YOUR")) {
      throw RuntimeException("Please update your app's public key at: " + "BASE_64_ENCODED_PUBLIC_KEY")
    }

    return try {
      Security.verifyPurchase(BASE_64_ENCODED_PUBLIC_KEY, signedData, signature)
    } catch (e: IOException) {
      Log.e(TAG, "Got an exception trying to validate a purchase: $e")
      false
    }

  }

  companion object {
    // Default value of mBillingClientResponseCode until BillingManager was not yet initialized
    const val BILLING_MANAGER_NOT_INITIALIZED = -1

    private val TAG = "BillingManager"

    /* BASE_64_ENCODED_PUBLIC_KEY should be YOUR APPLICATION'S PUBLIC KEY
     * (that you got from the Google Play developer console). This is not your
     * developer public key, it's the *app-specific* public key.
     *
     * Instead of just storing the entire literal string here embedded in the
     * program,  construct the key at runtime from pieces or
     * use bit manipulation (for example, XOR with some other string) to hide
     * the actual key.  The key itself is not secret information, but we don't
     * want to make it easy for an attacker to replace the public key with one
     * of their own and then fake messages from the server.
     */
    private const val BASE_64_ENCODED_PUBLIC_KEY =
        "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAjEq0CDSDhflhA49oUVT" +
        "d5ztVhhZEEq3HOYyKJYebljST64FWWer/bguqvpniTJ39rKD/REz06iAxnY" +
        "++BcSfk1nBCsTbuDxkbmmmwXEBo6gXjykY9v9ToEUaLi9fQCGhOYa/oVdkr" +
        "SUMhkSbeA94WUCZvkf9hjH97TRc73IM+CCYx2sFHwEupeJMzvWjJstFp5GN" +
        "zjpWjLY8Xg61bBhC2z5emGGleQuaD3PGh+5Q+7LI2r1rvtvVjcMdDLuAcA4" +
        "iiijf/f+pFE7BVb/JvaR63T8K3GWZYMfrGfWmh0KU/Uvyz6VCv3GgEJtGqr" +
        "2wXaP9mm0p5F+IyQp7bwzptQQkUQIDAQAB"
  }
}

