package in.simplifymoney.ledgersync.ingest;

import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Assigns each merged transaction its Category.
 *
 * TRANSFER is decided first and structurally, not by merchant text: two
 * merged transactions on DIFFERENT accounts, opposite direction, the same
 * amount, within a short window of each other, are one leg each of the user
 * moving their own money. (In this corpus that window is always under three
 * minutes and the merchant is always "IMPS/P2A/PARAG KAPOOR" - the other
 * account holder's own name - but neither of those is required by the rule,
 * so it keeps working if a third self-owned account, or a same-day NEFT
 * instead of an IMPS, shows up in a later corpus.)
 *
 * Everything left over is MICRO if it is a debit of 100.00 or less whose
 * merchant is on the UPI rail (merchant starts with "UPI", which covers both
 * "UPI/XYZ" and "UPI XYZ" - both appear in the corpus), SPEND if it is any
 * other debit, and INCOME if it is a credit.
 */
final class Categorizer {

    private static final BigDecimal MICRO_CEILING = new BigDecimal("100.00");
    private static final Duration TRANSFER_WINDOW = Duration.ofMinutes(15);

    private Categorizer() {}

    static List<NormalizedTxn> categorize(List<MergedTxn> merged) {
        boolean[] isTransfer = new boolean[merged.size()];
        markTransferPairs(merged, isTransfer);

        List<NormalizedTxn> out = new ArrayList<>();
        for (int i = 0; i < merged.size(); i++) {
            MergedTxn t = merged.get(i);
            Category category = isTransfer[i] ? Category.TRANSFER : nonTransferCategory(t);
            out.add(new NormalizedTxn(t.accountLast4(), t.occurredAt(), t.direction(),
                    t.amount(), category, t.merchant(), t.sourceMessageIds()));
        }
        out.sort(Comparator.comparing(NormalizedTxn::accountLast4)
                .thenComparing(NormalizedTxn::occurredAt));
        return out;
    }

    private static Category nonTransferCategory(MergedTxn t) {
        if (t.direction() == Direction.DEBIT
                && t.amount().compareTo(MICRO_CEILING) <= 0
                && t.merchant().toUpperCase(java.util.Locale.ROOT).startsWith("UPI")) {
            return Category.MICRO;
        }
        return t.direction() == Direction.DEBIT ? Category.SPEND : Category.INCOME;
    }

    private static void markTransferPairs(List<MergedTxn> merged, boolean[] isTransfer) {
        for (int i = 0; i < merged.size(); i++) {
            if (isTransfer[i] || merged.get(i).direction() != Direction.DEBIT) continue;
            MergedTxn debit = merged.get(i);

            for (int j = 0; j < merged.size(); j++) {
                if (i == j || isTransfer[j]) continue;
                MergedTxn credit = merged.get(j);
                if (credit.direction() != Direction.CREDIT) continue;
                if (credit.accountLast4().equals(debit.accountLast4())) continue;
                if (!credit.amount().equals(debit.amount())) continue;

                Duration gap = Duration.between(debit.occurredAt(), credit.occurredAt()).abs();
                if (gap.compareTo(TRANSFER_WINDOW) <= 0) {
                    isTransfer[i] = true;
                    isTransfer[j] = true;
                    break;
                }
            }
        }
    }
}
