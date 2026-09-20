package in.simplifymoney.ledgersync.ingest;

import in.simplifymoney.ledgersync.model.Direction;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Finds movement in an account's balance that no message in the corpus
 * explains.
 *
 * Every HDFC and ICICI SMS (not email - emails never quote one) states the
 * balance the account was left with after that message's own transaction.
 * Walk an account's evidenced transactions in time order: if the balance
 * quoted by transaction N, adjusted by transaction N+1's own direction and
 * amount, does not equal the balance quoted by N+1, then whatever produced
 * that difference has no message evidencing it - it cannot go in ledger.json
 * (NormalizedTxn requires at least one source message), so it belongs here
 * instead.
 *
 * Deliberately not used: any "opening balance" figure. The real service does
 * not have fixtures/corpus-a-totals.json to consult at runtime - only the
 * corpus itself - so this looks solely at consistency between consecutive
 * pieces of evidence. That means a gap before the FIRST evidenced transaction
 * on an account is structurally invisible to this check; there is nothing
 * upstream of it to compare against. Worth knowing, not worth pretending
 * otherwise.
 */
final class Reconciler {

    private Reconciler() {}

    static List<Map<String, Object>> reconcile(List<MergedTxn> merged) {
        Map<String, List<MergedTxn>> byAccount = new TreeMap<>();
        for (MergedTxn t : merged) {
            if (t.statedBalanceAfter() == null) continue;
            byAccount.computeIfAbsent(t.accountLast4(), k -> new ArrayList<>()).add(t);
        }

        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, List<MergedTxn>> e : byAccount.entrySet()) {
            List<MergedTxn> chain = new ArrayList<>(e.getValue());
            chain.sort(Comparator.comparing(MergedTxn::occurredAt));

            for (int i = 1; i < chain.size(); i++) {
                MergedTxn prev = chain.get(i - 1);
                MergedTxn curr = chain.get(i);

                BigDecimal expected = curr.direction() == Direction.DEBIT
                        ? prev.statedBalanceAfter().subtract(curr.amount())
                        : prev.statedBalanceAfter().add(curr.amount());
                BigDecimal gap = curr.statedBalanceAfter().subtract(expected);
                if (gap.signum() == 0) continue;

                String impliedDirection = gap.signum() < 0 ? "a debit" : "a credit";
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("account_last4", e.getKey());
                row.put("occurred_at", prev.occurredAt().toString());
                row.put("amount", gap.abs().setScale(2).toPlainString());
                row.put("note", "Balance on account " + e.getKey()
                        + " moves by " + gap.abs().setScale(2).toPlainString()
                        + " more than the evidenced transactions between "
                        + prev.occurredAt() + " (sources " + prev.sourceMessageIds()
                        + ", stated balance " + prev.statedBalanceAfter() + ") and "
                        + curr.occurredAt() + " (sources " + curr.sourceMessageIds()
                        + ", stated balance " + curr.statedBalanceAfter()
                        + ") account for. Implies " + impliedDirection
                        + " of " + gap.abs().setScale(2).toPlainString()
                        + " with no corresponding message in this corpus.");
                out.add(row);
            }
        }
        return out;
    }
}
