package in.simplifymoney.ledgersync.store;

import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Moves everything already in the SQL store into the document store.
 *
 * Two things this has to survive, both called out in the assignment:
 *
 *  - the SQL store has no uniqueness guarantee.
 *    db/migration/V2__seed.sql ships two literal duplicate rows
 *    (m-legacy-0001, m-legacy-0002: same account, time, direction and
 *    amount, only the message id differs) for exactly this reason. Two SQL
 *    rows that agree on account+occurredAt+direction+amount are the same
 *    real transaction seen twice, not two transactions, so they are merged
 *    here (source_message_ids unioned) before anything is written, the same
 *    way Deduper merges raw messages during ingest.
 *
 *  - this runs more than once, including after a partial failure.
 *    Nothing here keeps its own "have I run before" bookkeeping. Instead,
 *    DocumentStore.save() is expected to upsert by a deterministic key
 *    derived from the transaction's own fields (see
 *    MongoDocumentStore.idFor) - so writing the same merged transaction
 *    twice, on two different runs, converges on the same one document rather
 *    than duplicating it. Idempotency lives in the key, not in run state.
 */
public final class Backfill {

    private final SqlLedgerStore source;
    private final DocumentStore target;

    public Backfill(SqlLedgerStore source, DocumentStore target) {
        this.source = source;
        this.target = target;
    }

    public Result run() {
        List<NormalizedTxn> rows = source.all();

        Map<String, Merged> byKey = new LinkedHashMap<>();
        for (NormalizedTxn t : rows) {
            byKey.computeIfAbsent(mergeKey(t), k -> new Merged(t)).absorb(t);
        }

        long written = 0;
        long skipped = 0;
        for (Merged m : byKey.values()) {
            try {
                target.save(m.build());
                written++;
            } catch (RuntimeException e) {
                // A partial failure here is exactly what this method is
                // documented to survive: leave the row unwritten, count it,
                // and let the next run (idempotent by key) pick it back up.
                skipped++;
            }
        }
        return new Result(rows.size(), written, skipped);
    }

    private static String mergeKey(NormalizedTxn t) {
        return t.accountLast4() + '#' + t.occurredAt() + '#' + t.direction() + '#'
                + t.amount().toPlainString();
    }

    private static final class Merged {
        private final NormalizedTxn first;
        private final TreeSet<String> messageIds = new TreeSet<>();

        Merged(NormalizedTxn first) {
            this.first = first;
        }

        void absorb(NormalizedTxn t) {
            messageIds.addAll(t.sourceMessageIds());
        }

        NormalizedTxn build() {
            return new NormalizedTxn(first.accountLast4(), first.occurredAt(), first.direction(),
                    first.amount(), first.category(), first.merchant(), List.copyOf(messageIds));
        }
    }

    public record Result(long read, long written, long skipped) {}
}
