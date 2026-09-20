package in.simplifymoney.ledgersync.parse;

import java.math.BigDecimal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rupee amounts as banks write them.
 *
 * Handles the prefixes we see in practice - "Rs.", "Rs ", "INR " - and strips
 * the thousands separators before handing back a BigDecimal.
 *
 * See INC-2026-09-11: the decimal group used to be mandatory
 * ("[0-9,]+\.[0-9]{2}"), so a whole-rupee amount with no paise - "Rs.5
 * debited ... Avl Bal: Rs.92,213.10" - never matched at all. find() then kept
 * scanning rightward and landed on the next figure that DID satisfy the old
 * pattern, which was the quoted balance. The decimal group below is now
 * optional, so the amount that is actually leftmost in the message - the real
 * one - is what gets matched, whether or not it carries paise.
 */
public final class Amounts {

    private Amounts() {}

    private static final Pattern AMOUNT =
            Pattern.compile("(?:Rs\\.?|INR)\\s*([0-9,]+(?:\\.[0-9]{1,2})?)");

    private static final Pattern BALANCE = Pattern.compile(
            "(?:Avl\\s*Bal|Available\\s*Balance|BalAvl|Avl\\s*Limit)\\s*:?\\s*"
                    + "(?:Rs\\.?|INR)\\s*([0-9,]+\\.[0-9]{2})",
            Pattern.CASE_INSENSITIVE);

    /** The transaction amount: the first rupee figure in the message. */
    public static BigDecimal first(String body) {
        Matcher m = AMOUNT.matcher(body);
        if (!m.find()) return null;
        return toDecimal(m.group(1));
    }

    /** The balance the bank quoted, if it quoted one. */
    public static BigDecimal statedBalance(String body) {
        Matcher m = BALANCE.matcher(body);
        if (!m.find()) return null;
        return toDecimal(m.group(1));
    }

    private static BigDecimal toDecimal(String raw) {
        return new BigDecimal(raw.replace(",", "")).setScale(2);
    }
}
