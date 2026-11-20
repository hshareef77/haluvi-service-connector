package mv.haluvi.serviceconnector

import java.math.BigDecimal
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** Which Faseyha form a service uses and the label of its payment button. */
enum class Kind(val path: String, val action: String, val walletName: String) {
    RECHARGE("/recharge", "Recharge", "Recharge"),
    PACKAGE("/packages", "Activate package", "Package Recharge"),
    BILL("/bills", "Pay", "Bill Pay")
}

data class FaseyhaService(val label: String, val kind: Kind, val operator: String)

/**
 * Pure matching and validation rules shared by the payment and Wallet screens.
 * No Android dependencies, so every rule is covered by plain JVM unit tests.
 */
object FaseyhaRules {
    val SERVICES = listOf(
        FaseyhaService("Dhiraagu Reload", Kind.RECHARGE, "Dhiraagu"),
        FaseyhaService("Ooredoo Raastas", Kind.RECHARGE, "Ooredoo"),
        FaseyhaService("Dhiraagu Bill Pay", Kind.BILL, "Dhiraagu"),
        FaseyhaService("Ooredoo Bill Pay", Kind.BILL, "Ooredoo"),
        FaseyhaService("Medianet Bill Pay", Kind.BILL, "Medianet"),
        FaseyhaService("Dhiraagu Packages", Kind.PACKAGE, "Dhiraagu"),
        FaseyhaService("Ooredoo Packages", Kind.PACKAGE, "Ooredoo")
    )

    /** Names the provider uses for an operator in pages, receipts and Wallet rows. */
    fun operatorAliases(operator: String): List<String> = when (operator.lowercase(Locale.ROOT)) {
        "ooredoo" -> listOf("ooredoo", "supernet", "raastas")
        "dhiraagu" -> listOf("dhiraagu")
        "medianet" -> listOf("medianet")
        else -> listOf(operator.lowercase(Locale.ROOT))
    }

    private const val NUMBER = "[0-9][0-9,]*(?:\\.[0-9]{1,2})?"
    // A currency marker glued to letters or digits (for example the reference FRF6591…)
    // is never an amount.
    private val currencyFirst = Regex("(?<![A-Za-z0-9])(?:MVR|MRf|Rf|ރ\\.?)\\s*($NUMBER)(?![0-9A-Za-z])", RegexOption.IGNORE_CASE)
    private val currencyLast = Regex("(?<![A-Za-z0-9.,])($NUMBER)\\s*(?:MVR|MRf)(?![A-Za-z])", RegexOption.IGNORE_CASE)
    private val totalLabels = listOf("total\\s+to\\s+pay", "total\\s+amount", "amount\\s+to\\s+pay", "amount\\s+paid", "total", "amount")
        .map { Regex("(?<![A-Za-z])$it\\s*:?\\s*(?:MVR|MRf|Rf|ރ\\.?)?\\s*($NUMBER)(?![0-9A-Za-z])", RegexOption.IGNORE_CASE) }

    fun money(value: String?): BigDecimal? {
        val cleaned = value?.replace(",", "")?.replace(Regex("(?i)MVR|MRf|Rf|ރ\\.?"), "")?.trim()
        if (cleaned.isNullOrEmpty()) return null
        return try {
            val parsed = BigDecimal(cleaned)
            if (parsed.scale() > 2) null else parsed.setScale(2)
        } catch (_: NumberFormatException) { null }
    }

    fun sameMoney(a: BigDecimal?, b: BigDecimal?) = a != null && b != null && a.compareTo(b) == 0

    fun format(amount: BigDecimal?): String = amount?.setScale(2)?.toPlainString() ?: "unknown"

    /** Amount the user typed: positive, at most two decimals, at most 100,000. */
    fun validAmount(text: String): BigDecimal? {
        val parsed = text.trim().replace(",", "").toBigDecimalOrNull() ?: return null
        if (parsed <= BigDecimal.ZERO || parsed > BigDecimal("100000") || parsed.scale() > 2) return null
        return parsed.setScale(2)
    }

    /** Cleans a typed recipient. Mobile numbers accept +960/960 prefixes, spaces and dashes. */
    fun cleanRecipient(kind: Kind, text: String): String? {
        val compact = text.trim().replace(Regex("[\\s-]"), "")
        return if (kind == Kind.BILL) {
            compact.uppercase(Locale.ROOT).takeIf { it.matches(Regex("[A-Z0-9]{5,30}")) }
        } else {
            val digits = compact.removePrefix("+")
            val local = if (digits.length == 10 && digits.startsWith("960")) digits.substring(3) else digits
            local.takeIf { it.matches(Regex("[0-9]{7}")) }
        }
    }

    /** Letters and digits only, upper case, without a leading Maldives country code. */
    fun normalizeRecipient(text: String): String {
        val compact = text.replace(Regex("[^A-Za-z0-9]"), "").uppercase(Locale.ROOT)
        return if (compact.length == 10 && compact.startsWith("960")) compact.substring(3) else compact
    }

    fun amountsIn(text: String): List<BigDecimal> =
        (currencyFirst.findAll(text) + currencyLast.findAll(text))
            .mapNotNull { money(it.groupValues[1]) }.toList()

    /** The first amount explicitly labelled as a total or amount, by label priority. */
    fun labelledTotal(text: String): BigDecimal? {
        val flat = text.replace(Regex("\\s+"), " ")
        for (pattern in totalLabels) {
            pattern.find(flat)?.let { return money(it.groupValues[1]) }
        }
        return null
    }

    /** True when the provider text shows the recipient as a separate value. */
    fun recipientIn(text: String, recipient: String): Boolean {
        val wanted = normalizeRecipient(recipient)
        if (wanted.isEmpty()) return false
        val upper = text.uppercase(Locale.ROOT)
        val joined = upper.replace(Regex("\\b(\\d{3})[\\s-](\\d{4})\\b"), "$1$2")
        val pattern = Regex("(^|[^A-Z0-9])(?:\\+?960)?" + Regex.escape(wanted) + "([^A-Z0-9]|$)")
        return pattern.containsMatchIn(upper) || pattern.containsMatchIn(joined)
    }

    fun mentionsOperator(text: String, operator: String): Boolean {
        val lower = text.lowercase(Locale.ROOT)
        return operatorAliases(operator).any { lower.contains(it) }
    }

    /**
     * A provider's second confirmation dialog may be pressed automatically only when it
     * shows the authorised recipient, operator and amount. A labelled total must equal
     * the authorised amount; without one, the authorised amount must appear.
     */
    fun confirmationMatches(text: String, recipient: String, operator: String, amount: BigDecimal?): Boolean {
        if (amount == null || !recipientIn(text, recipient) || !mentionsOperator(text, operator)) return false
        val total = labelledTotal(text)
        return if (total != null) sameMoney(total, amount) else amountsIn(text).any { sameMoney(it, amount) }
    }

    /** Wallet service names for each kind of transaction. */
    fun kindMatches(kind: Kind, walletService: String): Boolean {
        val name = walletService.lowercase(Locale.ROOT)
        return when (kind) {
            Kind.RECHARGE -> !name.contains("package") && !name.contains("bill") &&
                (name.contains("reload") || name.contains("raastas") || name.contains("recharge") || name.contains("top up") || name.contains("topup"))
            Kind.PACKAGE -> name.contains("package") || name.contains("data") || name.contains("bundle") || name.contains("plan")
            Kind.BILL -> name.contains("bill") || name.contains("postpaid") || name.contains("supernet") ||
                name.contains("broadband") || name.contains("fixed") || name.contains("medianet")
        }
    }

    private val walletDate = Regex("^([A-Za-z]{3})\\s+(\\d{1,2})(?:,\\s*(\\d{4}))?,?\\s+(\\d{1,2}:\\d{2}\\s*[AaPp][Mm])$")

    /** Parses Wallet dates such as "Nov 20, 3:15 PM". A missing year is the latest non-future one. */
    fun parseWalletDate(text: String, now: Long = System.currentTimeMillis()): Long? {
        val match = walletDate.matchEntire(text.replace(Regex("\\s+"), " ").trim()) ?: return null
        val (month, day, year, clockText) = match.destructured
        val parser = SimpleDateFormat("MMM d yyyy h:mm a", Locale.ENGLISH).apply { isLenient = false }
        val current = Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.YEAR)
        val explicitYear = year.isNotEmpty()
        val clock = clockText.uppercase(Locale.ROOT).replace(Regex("\\s*([AP]M)"), " $1")
        return try {
            val parsed = parser.parse("$month $day ${if (explicitYear) year else current} $clock") ?: return null
            val calendar = Calendar.getInstance().apply { time = parsed }
            if (!explicitYear && calendar.timeInMillis > now + 24 * 3600_000L) calendar.add(Calendar.YEAR, -1)
            calendar.timeInMillis
        } catch (_: Exception) { null }
    }

    /** Provider clocks may be slightly early; a matching row must be from this attempt. */
    fun recentEnough(rowTime: Long, startedAt: Long) =
        startedAt > 0 && (rowTime - startedAt) in -2 * 60_000L..30 * 60_000L

    /**
     * Whether a Wallet history row could be this app's payment. A row amount, when shown,
     * must equal the authorised amount. The receipt must still confirm the recipient.
     */
    fun walletRowMatches(
        service: FaseyhaService, rowService: String, rowAmount: String?, rowDate: String,
        expected: BigDecimal?, startedAt: Long, now: Long = System.currentTimeMillis()
    ): Boolean = walletRowCandidate(service, rowService, rowAmount, rowDate, expected, startedAt, null, now)

    /**
     * Like [walletRowMatches], but when the Wallet references seen just before paying
     * are known, a reference that was not there before ([isNew]) identifies the new
     * entry without relying on the phone clock matching Faseyha's clock.
     */
    fun walletRowCandidate(
        service: FaseyhaService, rowService: String, rowAmount: String?, rowDate: String,
        expected: BigDecimal?, startedAt: Long, isNew: Boolean?, now: Long = System.currentTimeMillis()
    ): Boolean {
        if (expected == null || !mentionsOperator(rowService, service.operator) || !kindMatches(service.kind, rowService)) return false
        val shown = money(rowAmount)
        if (shown != null && !sameMoney(shown, expected)) return false
        val time = parseWalletDate(rowDate, now)
        return when (isNew) {
            false -> false
            true -> time == null || startedAt - time < 24 * 3600_000L
            null -> time != null && recentEnough(time, startedAt)
        }
    }

    /** The wallet balance fell by exactly the paid amount. */
    fun balanceDropped(before: BigDecimal?, after: BigDecimal?, amount: BigDecimal?) =
        before != null && after != null && sameMoney(before - after, amount)

    /** Final verdict from a provider receipt: same reference, recipient and amount. */
    fun receiptConfirms(
        expectedReference: String, receiptReference: String, recipient: String,
        receiptAccount: String, expected: BigDecimal?, receiptTotal: String?
    ): Boolean = expectedReference.isNotBlank() && expectedReference.equals(receiptReference, ignoreCase = true) &&
        normalizeRecipient(recipient).isNotEmpty() && normalizeRecipient(recipient) == normalizeRecipient(receiptAccount) &&
        sameMoney(expected, money(receiptTotal))

    fun successfulStatus(status: String) =
        status.equals("Successful", true) || status.equals("Completed", true)
}
