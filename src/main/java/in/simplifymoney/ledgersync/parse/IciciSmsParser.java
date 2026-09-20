package in.simplifymoney.ledgersync.parse;

import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.RawMessage;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ICICI Bank SMS.
 *
 * Two shapes are in production: the original "Dear Customer, Acct XX.... is
 * debited with" sentence (V1), and a terser "ICICI Bank Acct XXNNNN Dr/Cr INR
 * X on DD-Mon-YYYY HH:MM; MERCHANT ref no NNNN. BalAvl Rs X" shape (V2) that
 * carries its own amount inline rather than relying on Amounts.first - V2
 * messages quote the available balance right after the amount, so scanning
 * for "the first rupee figure" would be as fragile here as it was in
 * INC-2026-09-11.
 */
public final class IciciSmsParser implements MessageParser {

    public static final String SENDER = "VM-ICICIB-T";

    private static final Pattern V1 = Pattern.compile(
            "Acct XX(?<acct>\\d{4}) is (?<dir>debited|credited) with .*? "
                    + "on (?<when>\\d{2}/\\d{2}/\\d{4} \\d{2}:\\d{2})\\. "
                    + "Info: (?<merchant>[^.]+)\\.");

    private static final Pattern V2 = Pattern.compile(
            "ICICI Bank Acct XX(?<acct>\\d{4}) (?<dir>Dr|Cr) INR "
                    + "(?<amount>[0-9,]+(?:\\.[0-9]+)?) "
                    + "on (?<when>\\d{2}-\\w{3}-\\d{4} \\d{2}:\\d{2}); "
                    + "(?<merchant>.+?) ref no \\d+\\. "
                    + "BalAvl Rs (?<balance>[0-9,]+(?:\\.[0-9]+)?)");

    @Override
    public boolean supports(RawMessage m) {
        return "sms".equals(m.channel()) && SENDER.equals(m.sender());
    }

    @Override
    public Optional<ParsedTxn> parse(RawMessage m) {
        String body = m.body();

        Matcher v1 = V1.matcher(body);
        if (v1.find()) {
            BigDecimal amount = Amounts.first(body);
            OffsetDateTime at = Dates.ist(v1.group("when"));
            if (amount == null || at == null) return Optional.empty();

            Direction d = "debited".equals(v1.group("dir")) ? Direction.DEBIT : Direction.CREDIT;
            return Optional.of(new ParsedTxn(v1.group("acct"), at, d, amount,
                    v1.group("merchant").trim(), Amounts.statedBalance(body), m.messageId()));
        }

        Matcher v2 = V2.matcher(body);
        if (v2.find()) {
            OffsetDateTime at = Dates.ist(v2.group("when"));
            if (at == null) return Optional.empty();

            Direction d = "Dr".equals(v2.group("dir")) ? Direction.DEBIT : Direction.CREDIT;
            BigDecimal amount = toAmount(v2.group("amount"));
            BigDecimal balance = toAmount(v2.group("balance"));
            return Optional.of(new ParsedTxn(v2.group("acct"), at, d, amount,
                    v2.group("merchant").trim(), balance, m.messageId()));
        }

        return Optional.empty();
    }

    private static BigDecimal toAmount(String raw) {
        return new BigDecimal(raw.replace(",", "")).setScale(2);
    }
}
