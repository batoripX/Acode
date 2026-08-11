package com.foxdebug.system

import android.content.Context
import android.util.Log
import com.foxdebug.acode.rk.auth.EncryptedPreferenceManager
import org.json.JSONException
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Random

class RewardPassManager(context: Context) {

    private val adsPrefManager = EncryptedPreferenceManager(context, ADS_PREFS_FILENAME)
    private val random = Random()

    @Throws(JSONException::class)
    fun getRewardStatus(): String {
        val state = syncRewardState(loadRewardState())
        val status = buildRewardStatus(state)

        if (status.optBoolean("hasPendingExpiryNotice")) {
            state.put("expiryNoticePendingUntil", 0L)
        }

        saveRewardState(state)
        return status.toString()
    }

    @Throws(JSONException::class)
    fun redeemReward(offerId: String): String {
        val state = syncRewardState(loadRewardState())
        val redemptionsToday = state.optInt("redemptionsToday", 0)
        val now = System.currentTimeMillis()
        val adFreeUntil = state.optLong("adFreeUntil", 0L)
        val remainingMs = (adFreeUntil - now).coerceAtLeast(0L)

        if (redemptionsToday >= MAX_REDEMPTIONS_PER_DAY) {
            throw JSONException(
                "Daily limit reached. You can redeem up to $MAX_REDEMPTIONS_PER_DAY rewards per day."
            )
        }

        if (remainingMs >= MAX_ACTIVE_PASS_MS) {
            throw JSONException("You already have the maximum 10 hours of ad-free time active.")
        }

        val grantedDurationMs = resolveRewardDuration(offerId)
        val baseTime = maxOf(now, adFreeUntil)
        val newAdFreeUntil = minOf(baseTime + grantedDurationMs, now + MAX_ACTIVE_PASS_MS)
        val appliedDurationMs = (newAdFreeUntil - baseTime).coerceAtLeast(0L)

        state.apply {
            put("adFreeUntil", newAdFreeUntil)
            put("lastExpiredRewardUntil", 0L)
            put("expiryNoticePendingUntil", 0L)
            put("redemptionDay", todayKey)
            put("redemptionsToday", redemptionsToday + 1)
        }
        saveRewardState(state)

        val status = buildRewardStatus(state).apply {
            put("grantedDurationMs", grantedDurationMs)
            put("appliedDurationMs", appliedDurationMs)
            put("offerId", offerId)
        }
        return status.toString()
    }

    private fun loadRewardState(): JSONObject {
        val raw = adsPrefManager.getString(KEY_REWARD_STATE, "")
        if (raw.isNullOrEmpty()) {
            return defaultRewardState()
        }

        return try {
            mergeRewardState(JSONObject(raw))
        } catch (error: JSONException) {
            Log.w(TAG, "Failed to parse reward state, resetting.", error)
            defaultRewardState()
        }
    }

    private fun defaultRewardState(): JSONObject {
        return JSONObject().apply {
            put("adFreeUntil", 0L)
            put("lastExpiredRewardUntil", 0L)
            put("expiryNoticePendingUntil", 0L)
            put("redemptionDay", todayKey)
            put("redemptionsToday", 0)
        }
    }

    private fun mergeRewardState(parsed: JSONObject): JSONObject {
        return defaultRewardState().apply {
            put("adFreeUntil", parsed.optLong("adFreeUntil", 0L))
            put("lastExpiredRewardUntil", parsed.optLong("lastExpiredRewardUntil", 0L))
            put("expiryNoticePendingUntil", parsed.optLong("expiryNoticePendingUntil", 0L))
            put("redemptionDay", parsed.optString("redemptionDay", todayKey))
            put("redemptionsToday", parsed.optInt("redemptionsToday", 0))
        }
    }

    private fun saveRewardState(state: JSONObject) {
        adsPrefManager.setString(KEY_REWARD_STATE, state.toString())
    }

    private fun syncRewardState(state: JSONObject): JSONObject {
        val currentTodayKey = todayKey
        if (currentTodayKey != state.optString("redemptionDay", currentTodayKey)) {
            state.put("redemptionDay", currentTodayKey)
            state.put("redemptionsToday", 0)
        }

        val adFreeUntil = state.optLong("adFreeUntil", 0L)
        val now = System.currentTimeMillis()
        if (adFreeUntil in 1..now) {
            if (state.optLong("expiryNoticePendingUntil", 0L) != adFreeUntil) {
                state.put("expiryNoticePendingUntil", adFreeUntil)
            }
            state.put("lastExpiredRewardUntil", adFreeUntil)
            state.put("adFreeUntil", 0L)
        }

        return state
    }

    private fun buildRewardStatus(state: JSONObject): JSONObject {
        val now = System.currentTimeMillis()
        val adFreeUntil = state.optLong("adFreeUntil", 0L)
        val redemptionsToday = state.optInt("redemptionsToday", 0)
        val remainingMs = (adFreeUntil - now).coerceAtLeast(0L)
        val remainingRedemptions = (MAX_REDEMPTIONS_PER_DAY - redemptionsToday).coerceAtLeast(0)

        val expiryNoticePendingUntil = state.optLong("expiryNoticePendingUntil", 0L)

        return JSONObject().apply {
            put("adFreeUntil", adFreeUntil)
            put("lastExpiredRewardUntil", state.optLong("lastExpiredRewardUntil", 0L))
            put("isActive", adFreeUntil > now)
            put("remainingMs", remainingMs)
            put("redemptionsToday", redemptionsToday)
            put("remainingRedemptions", remainingRedemptions)
            put("maxRedemptionsPerDay", MAX_REDEMPTIONS_PER_DAY)
            put("maxActivePassMs", MAX_ACTIVE_PASS_MS)
            put("hasPendingExpiryNotice", expiryNoticePendingUntil > 0L)
            put("expiryNoticePendingUntil", expiryNoticePendingUntil)

            val canRedeem = remainingRedemptions > 0 && remainingMs < MAX_ACTIVE_PASS_MS
            put("canRedeem", canRedeem)
            put("redeemDisabledReason", getRedeemDisabledReason(remainingRedemptions, remainingMs))
        }
    }

    private fun getRedeemDisabledReason(remainingRedemptions: Int, remainingMs: Long): String {
        if (remainingRedemptions <= 0) {
            return "Daily limit reached. You can redeem up to $MAX_REDEMPTIONS_PER_DAY rewards per day."
        }
        if (remainingMs >= MAX_ACTIVE_PASS_MS) {
            return "You already have the maximum 10 hours of ad-free time active."
        }
        return ""
    }

    private fun resolveRewardDuration(offerId: String): Long {
        return when (offerId) {
            "quick" -> ONE_HOUR_MS
            "focus" -> {
                val selectedHours = 4 + random.nextInt(3)
                selectedHours * ONE_HOUR_MS
            }
            else -> throw JSONException("Unknown reward offer: $offerId")
        }
    }

    private val todayKey: String
        get() = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    companion object {
        private const val TAG = "SystemRewardPass"
        private const val ADS_PREFS_FILENAME = "ads"
        private const val KEY_REWARD_STATE = "reward_state"
        private const val ONE_HOUR_MS = 60L * 60L * 1000L
        private const val MAX_ACTIVE_PASS_MS = 10L * ONE_HOUR_MS
        private const val MAX_REDEMPTIONS_PER_DAY = 3
    }
}
