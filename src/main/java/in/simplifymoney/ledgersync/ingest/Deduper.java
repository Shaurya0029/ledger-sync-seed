package in.simplifymoney.ledgersync.ingest;

import in.simplifymoney.ledgersync.parse.ParsedTxn;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Merges parsed messages that evidence the same real transaction.
 *
 * One real transaction can arrive as more than one message: the bank sends
 * both an SMS and an email for the same event, and/or the phone re-uploads
 * the same SMS or email it already uploaded once (RawMessage.messageId
 * identifies the upload, not the underlying message - see its javadoc).
 *
 * Empirically (checked against fixtures/corpus-a-totals.json) the account,
 * minute-precision occurredAt, direction and amount together are enough to
 * key on: every group that shares those four fields also agrees on merchant,
 * across HDFC and ICICI, across SMS and email formats, and across duplicate
 * uploads. Merchant is deliberately NOT part of the key - it is exactly the
 * kind of thing that could legitimately be spelled slightly differently by
 * two channels reporting the same transaction, and NormalizedTxn.merchant is
 * documented as "not graded" for that reason.
 */
final class Deduper {

    private Deduper() {}

    static List<MergedTxn> dedupe(List<ParsedTxn> parsed) {
        Map<String, Group> groups = new LinkedHashMap<>();

        for (ParsedTxn p : parsed) {
            String key = p.accountLast4() + '|' + p.occurredAt() + '|'
                    + p.direction() + '|' + p.amount().toPlainString();
            groups.computeIfAbsent(key, k -> new Group(p)).add(p);
        }

        List<MergedTxn> out = new ArrayList<>();
        for (Group g : groups.values()) {
            out.add(new MergedTxn(
                    g.first.accountLast4(),
                    g.first.occurredAt(),
                    g.first.direction(),
                    g.first.amount(),
                    g.first.merchant(),
                    g.statedBalance,
                    List.copyOf(new TreeSet<>(g.messageIds))));
        }
        return out;
    }

    private static final class Group {
        final ParsedTxn first;
        final List<String> messageIds = new ArrayList<>();
        java.math.BigDecimal statedBalance;

        Group(ParsedTxn first) {
            this.first = first;
        }

        void add(ParsedTxn p) {
            messageIds.add(p.sourceMessageId());
            // Every message in a group should quote the same "balance after"
            // (or none at all - email never does). Keep the first non-null
            // one we see; Reconciler only needs one anchor per transaction.
            if (statedBalance == null && p.statedBalance() != null) {
                statedBalance = p.statedBalance();
            }
        }
    }
}
