package in.simplifymoney.ledgersync.ingest;

import in.simplifymoney.ledgersync.model.Direction;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * One real transaction, after Deduper has merged every message that
 * evidences it - but before Categorizer has decided its Category. This is the
 * internal shape between "a pile of parsed messages" and the frozen
 * NormalizedTxn output contract; it carries statedBalanceAfter, which
 * NormalizedTxn deliberately does not, because Reconciler needs it and
 * nothing downstream of categorization does.
 */
record MergedTxn(
        String accountLast4,
        OffsetDateTime occurredAt,
        Direction direction,
        BigDecimal amount,
        String merchant,
        BigDecimal statedBalanceAfter,
        List<String> sourceMessageIds) {
}
