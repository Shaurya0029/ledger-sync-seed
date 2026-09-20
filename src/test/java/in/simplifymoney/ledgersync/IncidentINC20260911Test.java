package in.simplifymoney.ledgersync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import in.simplifymoney.ledgersync.model.RawMessage;
import in.simplifymoney.ledgersync.parse.Amounts;
import in.simplifymoney.ledgersync.parse.HdfcSmsParser;
import in.simplifymoney.ledgersync.parse.ParsedTxn;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * INC-2026-09-11 - a customer's ledger showed a Rs.92,213.10 spend against a
 * Rs.5 UPI payment.
 *
 * Root cause: parse.Amounts.AMOUNT required a decimal group
 * ("[0-9,]+\.[0-9]{2}") on the transaction amount itself. A whole-rupee
 * amount with no paise - "Rs.5 debited ... Avl Bal: Rs.92,213.10" - never
 * satisfied that pattern, so Matcher.find() kept scanning rightward past it
 * and landed on the next figure that did: the quoted balance.
 *
 * Before the fix (parse.Amounts.AMOUNT with a mandatory decimal group), both
 * assertions below fail: readsWholeRupeeAmountNotTheBalance fails outright
 * (Amounts.first returns 92213.10, the balance, not 5.00), and
 * ingestingTheAffectedMessageProducesTheRealAmount fails the same way one
 * layer up, through the real parser.
 *
 * m-00022-2f118b is the actual corpus message this happened on
 * (fixtures/corpus-a.jsonl); the account and time differ from the
 * illustrative example in README.md/the incident doc, but the shape - and
 * the bug - are the same message class.
 */
class IncidentINC20260911Test {

    private static final String AFFECTED_MESSAGE_BODY =
            "Rs.5 debited from a/c **4821 on 04-07-26 at 11:54 to UPI/WATER CAN. "
                    + "Avl Bal: Rs.92,213.10. Not you? Call 18002586161";

    @Test
    @DisplayName("a whole-rupee amount is read as itself, not skipped past in favour of the balance")
    void readsWholeRupeeAmountNotTheBalance() {
        BigDecimal amount = Amounts.first(AFFECTED_MESSAGE_BODY);
        assertEquals(new BigDecimal("5.00"), amount);
        assertNotEquals(new BigDecimal("92213.10"), amount);
    }

    @Test
    @DisplayName("ingesting the real affected message (m-00022-2f118b) produces a Rs.5 debit, not Rs.92,213.10")
    void ingestingTheAffectedMessageProducesTheRealAmount() {
        RawMessage m = new RawMessage(
                "m-00022-2f118b",
                "sms",
                HdfcSmsParser.SENDER,
                OffsetDateTime.parse("2026-07-04T12:39:00+05:30"),
                "dev-3f1a90c47b21",
                AFFECTED_MESSAGE_BODY);

        Optional<ParsedTxn> parsed = new HdfcSmsParser().parse(m);

        assertEquals(new BigDecimal("5.00"), parsed.orElseThrow().amount());
    }
}
