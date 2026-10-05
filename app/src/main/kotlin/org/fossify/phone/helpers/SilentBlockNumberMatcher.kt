package org.fossify.phone.helpers

import android.content.Context
import android.telecom.TelecomManager
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import com.google.i18n.phonenumbers.PhoneNumberUtil
import java.util.Locale

/**
 * Decides whether two phone numbers belong to the same caller, regardless of their formatting, e.g.
 * "+98 912 123 4567", "09121234567" and "0912-123-4567" all match each other when the device is in Iran.
 */
class SilentBlockNumberMatcher(context: Context) {
    private val appContext = context.applicationContext

    private val regionHint: String by lazy {
        val telephonyManager = appContext.getSystemService(TelephonyManager::class.java)
        listOf(
            telephonyManager?.simCountryIso,
            telephonyManager?.networkCountryIso,
            Locale.getDefault().country
        )
            .firstOrNull { !it.isNullOrBlank() }
            ?.uppercase(Locale.US)
            .orEmpty()
    }

    private val e164Cache = HashMap<String, String?>()

    fun matches(first: String, second: String): Boolean {
        val normalizedFirst = normalize(first)
        val normalizedSecond = normalize(second)
        return when {
            normalizedFirst.isEmpty() || normalizedSecond.isEmpty() -> false
            normalizedFirst == normalizedSecond -> true
            // numbers can only match if they share the trailing digits, skip the expensive checks otherwise
            comparableKey(normalizedFirst) != comparableKey(normalizedSecond) -> false
            // national numbers of the current region, e.g. "0912..." and "+98912..." in Iran
            isSameE164Number(normalizedFirst, normalizedSecond) -> true
            else -> isLibPhoneNumberMatch(normalizedFirst, normalizedSecond)
        }
    }

    /** Compares two callers, a hidden caller only matches another hidden caller. */
    fun isSameCaller(
        firstNumber: String,
        isFirstHidden: Boolean,
        secondNumber: String,
        isSecondHidden: Boolean,
    ): Boolean {
        return if (isFirstHidden || isSecondHidden) {
            isFirstHidden && isSecondHidden
        } else {
            matches(firstNumber, secondNumber)
        }
    }

    private fun isSameE164Number(normalizedFirst: String, normalizedSecond: String): Boolean {
        val firstE164 = toE164(normalizedFirst)
        return firstE164 != null && firstE164 == toE164(normalizedSecond)
    }

    /**
     * Also matches a national number with the international one of another region, e.g. while roaming. If one number
     * is only the end of the other one, that's fine for a trunk prefix ("0912..." vs "912..."), but not for a local
     * number saved without its area code, "2233 4455" must not match a stranger's "+98 31 2233 4455".
     */
    private fun isLibPhoneNumberMatch(normalizedFirst: String, normalizedSecond: String): Boolean {
        val matchType = try {
            PhoneNumberUtil.getInstance().isNumberMatch(normalizedFirst, normalizedSecond)
        } catch (_: Exception) {
            PhoneNumberUtil.MatchType.NOT_A_NUMBER
        }

        return when (matchType) {
            PhoneNumberUtil.MatchType.EXACT_MATCH, PhoneNumberUtil.MatchType.NSN_MATCH -> true
            PhoneNumberUtil.MatchType.SHORT_NSN_MATCH -> differsByTrunkPrefixOnly(normalizedFirst, normalizedSecond)
            PhoneNumberUtil.MatchType.NOT_A_NUMBER -> isLooselyEqual(normalizedFirst, normalizedSecond)
            else -> false
        }
    }

    private fun differsByTrunkPrefixOnly(normalizedFirst: String, normalizedSecond: String): Boolean {
        val (shorter, longer) = listOf(normalizedFirst, normalizedSecond)
            .map { it.trimStart('+') }
            .sortedBy { it.length }
        return longer.length == shorter.length + 1 && longer.startsWith("0") && longer.endsWith(shorter)
    }

    // the system's caller ID comparison, for strings libphonenumber doesn't consider numbers at all
    @Suppress("DEPRECATION")
    private fun isLooselyEqual(normalizedFirst: String, normalizedSecond: String): Boolean {
        return PhoneNumberUtils.compare(normalizedFirst, normalizedSecond)
    }

    private fun toE164(normalizedNumber: String): String? {
        synchronized(e164Cache) {
            if (e164Cache.containsKey(normalizedNumber)) {
                return e164Cache[normalizedNumber]
            }
        }

        val formatted = try {
            PhoneNumberUtils.formatNumberToE164(normalizedNumber, regionHint)
        } catch (_: Exception) {
            null
        }

        synchronized(e164Cache) {
            e164Cache[normalizedNumber] = formatted
        }
        return formatted
    }

    companion object {
        /**
         * [PhoneNumberUtils.compare] requires at least this many trailing digits to be identical,
         * so it can be used for indexing numbers that could possibly match.
         */
        private const val MIN_MATCHING_DIGITS = 7

        private val HIDDEN_NUMBER_PLACEHOLDERS = setOf("-1", "-2", "-3", "-4")

        /**
         * Returns the dialable network portion of the number with keypad letters converted to digits,
         * containing only digits and an optional leading plus sign.
         */
        fun normalize(number: String): String {
            if (number.isBlank()) {
                return ""
            }

            val converted = PhoneNumberUtils.convertKeypadLettersToDigits(number)
            val networkPortion = PhoneNumberUtils.extractNetworkPortion(converted) ?: converted
            val hasPlus = networkPortion.trimStart().startsWith("+")
            val digits = networkPortion.filter { it in '0'..'9' }
            return when {
                digits.isEmpty() -> ""
                hasPlus -> "+$digits"
                else -> digits
            }
        }

        /** Any two matching numbers are guaranteed to have the same comparable key. */
        fun comparableKey(number: String): String {
            return normalize(number).trimStart('+').takeLast(MIN_MATCHING_DIGITS)
        }

        /**
         * True for withheld caller IDs: restricted, unknown and unavailable. Payphones show no number either, but
         * they aren't hiding anything. Telecom and the call log use the same presentation values.
         */
        fun isHiddenPresentation(presentation: Int): Boolean {
            return presentation != TelecomManager.PRESENTATION_ALLOWED
                && presentation != TelecomManager.PRESENTATION_PAYPHONE
        }

        /** True for empty numbers and the placeholders the system call log uses for private or unknown callers. */
        fun isHiddenNumber(number: String?): Boolean {
            return number.isNullOrBlank() || number.trim() in HIDDEN_NUMBER_PLACEHOLDERS || normalize(number).isEmpty()
        }
    }
}
