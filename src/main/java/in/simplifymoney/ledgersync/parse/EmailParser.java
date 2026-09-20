package in.simplifymoney.ledgersync.parse;

import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.RawMessage;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bank transaction alert emails.
 *
 * Both banks use the same body shape:
 *
 *   Date: Wed, 01 Jul 2026 09:02:00 +0530
 *   Subject: Transaction alert on your account
 *
 *   Dear Customer,
 *
 *   Your account ending 4821 has been credited with INR 45,000.
 *   Merchant / Remarks: SALARY CREDIT
 *   Transaction reference: 1597155421
 *
 *   This is a system generated email.
 *
 * occurredAt comes from the "Date:" header, not RawMessage.receivedAt - the
 * header is when the bank sent the alert (right after the transaction), while
 * receivedAt is when the phone's mail client happened to sync it, which can be
 * (and in this corpus, is) tens of minutes later.
 *
 * The header's own offset is not always +0530: at least one message in the
 * corpus carries a UTC "+0000" Date header. NormalizedTxn.occurredAt is
 * documented as IST, so the header is parsed with whatever offset it actually
 * carries and then converted to IST - not silently assumed to already be IST.
 *
 * No balance is ever quoted in these emails, so ParsedTxn.statedBalance is
 * always null for this parser.
 */
public final class EmailParser implements MessageParser {

    private static final DateTimeFormatter HEADER_DATE =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH);

    private static final Pattern DATE_LINE =
            Pattern.compile("^Date:\\s*(?<date>.+)$", Pattern.MULTILINE);

    private static final Pattern BODY = Pattern.compile(
            "account ending (?<acct>\\d{4}) has been (?<dir>credited|debited) with "
                    + "(?:Rs\\.?|INR)\\s*(?<amount>[0-9,]+(?:\\.[0-9]+)?)\\.\\s*\\n"
                    + "Merchant / Remarks:\\s*(?<merchant>.+)");

    @Override
    public boolean supports(RawMessage m) {
        return "email".equals(m.channel());
    }

    @Override
    public Optional<ParsedTxn> parse(RawMessage m) {
        String body = m.body();

        Matcher dateLine = DATE_LINE.matcher(body);
        Matcher content = BODY.matcher(body);
        if (!dateLine.find() || !content.find()) return Optional.empty();

        OffsetDateTime at;
        try {
            at = OffsetDateTime.parse(dateLine.group("date").trim(), HEADER_DATE)
                    .withOffsetSameInstant(Dates.IST);
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }

        Direction dir = "debited".equals(content.group("dir")) ? Direction.DEBIT : Direction.CREDIT;
        BigDecimal amount = new BigDecimal(content.group("amount").replace(",", "")).setScale(2);
        String merchant = content.group("merchant").trim();

        return Optional.of(new ParsedTxn(content.group("acct"), at, dir, amount, merchant,
                null, m.messageId()));
    }
}
