package in.simplifymoney.ledgersync.store;

import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Proves the two stores agree, and says precisely where they do not.
 *
 * Two independent checks, because a single one can be fooled in a way the
 * assignment specifically warns about ("a checker that compares row counts
 * will not" find what we changed):
 *
 *  1. Per-message-id, field-by-field. SQL is the source of truth for which
 *     message ids should exist and what they should point to; every one of
 *     them is looked up in the document store (DocumentStore.byMessageId -
 *     the one query the interface offers for exactly this) and compared
 *     field by field. This catches a document that is missing, or a document
 *     whose account/time/direction/amount/category/source_message_ids has
 *     been altered.
 *
 *  2. Per-account category totals. Sums SQL's own transactions by category
 *     and compares against DocumentStore.categoryTotals(). This is the net
 *     to catch what (1) cannot: an *extra* document in the store with no
 *     corresponding SQL message id at all - nothing in SQL points a lookup
 *     at it, but it still throws a per-account total off, which this catches.
 *
 * Both read from SQL only once and treat it as ground truth throughout -
 * this is a one-directional check (does the document store match SQL), which
 * matches how Backfill is documented to flow (SQL -> documents).
 */
public final class ConsistencyChecker {

    private final SqlLedgerStore sql;
    private final DocumentStore documents;

    public ConsistencyChecker(SqlLedgerStore sql, DocumentStore documents) {
        this.sql = sql;
        this.documents = documents;
    }

    public List<Divergence> check() {
        List<NormalizedTxn> sqlRows = sql.all();
        List<Divergence> out = new ArrayList<>();
        out.addAll(checkByMessageId(sqlRows));
        out.addAll(checkCategoryTotals(sqlRows));
        return out;
    }

    private List<Divergence> checkByMessageId(List<NormalizedTxn> sqlRows) {
        Map<String, NormalizedTxn> repByKey = new LinkedHashMap<>();
        Map<String, TreeSet<String>> idsByKey = new LinkedHashMap<>();
        for (NormalizedTxn t : sqlRows) {
            String key = mergeKey(t);
            repByKey.putIfAbsent(key, t);
            idsByKey.computeIfAbsent(key, k -> new TreeSet<>()).addAll(t.sourceMessageIds());
        }

        List<Divergence> out = new ArrayList<>();
        for (Map.Entry<String, NormalizedTxn> e : repByKey.entrySet()) {
            NormalizedTxn expected = e.getValue();
            Set<String> expectedIds = idsByKey.get(e.getKey());

            for (String msgId : expectedIds) {
                Optional<NormalizedTxn> found = documents.byMessageId(msgId);
                if (found.isEmpty()) {
                    out.add(new Divergence("transaction for message " + msgId,
                            summarize(expected, expectedIds), "‹absent from document store›"));
                    continue;
                }

                NormalizedTxn actual = found.get();
                if (!expected.accountLast4().equals(actual.accountLast4())) {
                    field(out, msgId, "account_last4", expected.accountLast4(), actual.accountLast4());
                }
                if (!expected.occurredAt().toString().equals(actual.occurredAt().toString())) {
                    field(out, msgId, "occurred_at", expected.occurredAt().toString(),
                            actual.occurredAt().toString());
                }
                if (expected.direction() != actual.direction()) {
                    field(out, msgId, "direction", expected.direction().name(), actual.direction().name());
                }
                if (expected.amount().compareTo(actual.amount()) != 0) {
                    field(out, msgId, "amount", expected.amount().toPlainString(),
                            actual.amount().toPlainString());
                }
                if (expected.category() != actual.category()) {
                    field(out, msgId, "category", expected.category().name(), actual.category().name());
                }

                Set<String> actualIds = new TreeSet<>(actual.sourceMessageIds());
                if (!actualIds.equals(expectedIds)) {
                    field(out, msgId, "source_message_ids", expectedIds.toString(), actualIds.toString());
                }
            }
        }
        return out;
    }

    private List<Divergence> checkCategoryTotals(List<NormalizedTxn> sqlRows) {
        // SQL has no uniqueness guarantee (see Backfill's class doc): the same
        // real transaction can appear as more than one row, distinguished
        // only by message id. Summing raw rows would count it once per
        // duplicate, so merge by the same key Backfill uses before totaling -
        // otherwise this would flag Backfill's own correct deduplication as a
        // divergence on every run.
        Map<String, NormalizedTxn> repByKey = new LinkedHashMap<>();
        for (NormalizedTxn t : sqlRows) {
            repByKey.putIfAbsent(mergeKey(t), t);
        }

        Map<String, Map<Category, BigDecimal>> sqlTotals = new TreeMap<>();
        for (NormalizedTxn t : repByKey.values()) {
            sqlTotals.computeIfAbsent(t.accountLast4(), k -> new EnumMap<>(Category.class))
                    .merge(t.category(), t.amount(), BigDecimal::add);
        }

        List<Divergence> out = new ArrayList<>();
        for (Map.Entry<String, Map<Category, BigDecimal>> e : sqlTotals.entrySet()) {
            String account = e.getKey();
            Map<Category, BigDecimal> expected = e.getValue();
            Map<Category, BigDecimal> actual = documents.categoryTotals(account);

            for (Category c : Category.values()) {
                BigDecimal want = expected.getOrDefault(c, BigDecimal.ZERO.setScale(2));
                BigDecimal got = actual.getOrDefault(c, BigDecimal.ZERO.setScale(2));
                if (want.compareTo(got) != 0) {
                    out.add(new Divergence("category total " + account + "/" + c,
                            want.toPlainString(), got.toPlainString()));
                }
            }
        }
        return out;
    }

    private static void field(List<Divergence> out, String msgId, String field,
                               String expected, String actual) {
        out.add(new Divergence(field + " (message " + msgId + ")", expected, actual));
    }

    private static String mergeKey(NormalizedTxn t) {
        return t.accountLast4() + '#' + t.occurredAt() + '#' + t.direction() + '#'
                + t.amount().toPlainString();
    }

    private static String summarize(NormalizedTxn t, Set<String> ids) {
        return t.accountLast4() + " " + t.direction() + " " + t.amount() + " @ "
                + t.occurredAt() + " sources=" + ids;
    }

    /** One place the two stores disagree. */
    public record Divergence(String what, String inSql, String inDocuments) {}
}
