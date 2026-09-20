package in.simplifymoney.ledgersync.store;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.gte;
import static com.mongodb.client.model.Filters.lt;

import com.mongodb.ExplainVerbosity;
import com.mongodb.client.AggregateIterable;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Accumulators;
import com.mongodb.client.model.Aggregates;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.Sorts;
import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.Decimal128;

/**
 * The document store, backed by MongoDB.
 *
 * ---------------------------------------------------------------- why Mongo
 * The assignment says DynamoDB is preferred, Mongo is fine, and to justify
 * the choice. This codebase's only real external dependency so far (H2) is
 * loaded straight from java.sql with zero AWS-specific machinery; MongoDB's
 * sync driver keeps that shape - a single new dependency, no IAM, no account,
 * runs from `docker compose up` with nothing else to configure. DynamoDB
 * Local exists, but it is a DynamoDB *emulator* shipped as a JAR with native
 * SQLite bindings, which is a worse "runs from docker compose" story than a
 * database whose real production form already runs as a container. Given the
 * time box, Mongo was the pragmatic pick; a DynamoDB implementation would
 * follow the same document shapes below (see the mapping notes on each
 * index), just single-table with GSIs instead of secondary indexes.
 *
 * ---------------------------------------------------------- document shape
 * One document per NormalizedTxn, collection "transactions":
 *
 *   _id                 "<account>#<occurredAt>#<direction>#<amount>" -
 *                       deterministic and dedup-safe: replacing by _id makes
 *                       Backfill idempotent across reruns (see Backfill.java)
 *                       without a uniqueness constraint on the *source* SQL
 *                       table, which does not have one (INC note in
 *                       Backfill.java).
 *   account_last4       string, 4 digits
 *   occurred_at          ISO-8601 string, IST offset preserved as-is
 *   occurred_month       "yyyy-MM", derived from occurred_at - exists purely
 *                       so Q1 can equality-match a month instead of range-
 *                       scanning occurred_at with a timezone-aware bound
 *   direction, category  strings (enum names)
 *   amount               Decimal128 - this service handles money; nothing
 *                       here should ever be a double
 *   merchant             string
 *   source_message_ids   array of strings
 *
 * ------------------------------------------------------------------ indexes
 *   idx_account_month   {account_last4:1, occurred_month:1, occurred_at:-1}
 *                       serves Q1 directly: equality on the first two keys,
 *                       and the index's own order already satisfies "newest
 *                       first", so no in-memory sort. DynamoDB equivalent:
 *                       partition key account_last4, sort key
 *                       "<occurred_month>#<occurred_at>" (descending scan).
 *
 *   idx_account         {account_last4:1}
 *                       serves Q2's $match stage: only that account's
 *                       documents are examined before $group sums them by
 *                       category. This is "for its whole history", so unlike
 *                       Q1 there is no month to narrow by - examined here is
 *                       genuinely every document that account has, which is
 *                       the honest cost of a full-history rollup with no
 *                       precomputed running total. DynamoDB equivalent: same
 *                       partition key as above, Query without a sort-key
 *                       condition, summed in the application (Dynamo has no
 *                       server-side $group).
 *
 *   idx_message (unique) {source_message_ids:1}
 *                       a multikey index - Mongo indexes each array element
 *                       separately - so Q3 is a point lookup: examined and
 *                       returned are both 0 or 1. DynamoDB equivalent: a GSI
 *                       on a message id would need one item per
 *                       (message_id, transaction) pair, since Dynamo cannot
 *                       index into an array the way Mongo can; a single-
 *                       message-per-transaction assumption does not hold
 *                       here (dedup can cite several), so Mongo's multikey
 *                       index is a genuinely better fit for Q3, not just a
 *                       lazier one.
 */
public final class MongoDocumentStore implements DocumentStore, AutoCloseable {

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy-MM");

    private final MongoClient client;
    private final MongoCollection<Document> transactions;

    public MongoDocumentStore(String connectionString, String databaseName) {
        this.client = MongoClients.create(connectionString);
        MongoDatabase db = client.getDatabase(databaseName);
        this.transactions = db.getCollection("transactions");
        ensureIndexes();
    }

    private void ensureIndexes() {
        // {account_last4, occurred_month, occurred_at desc} alone serves Q1:
        // equality on the first two keys plus a trailing sort key means the
        // index's own order already satisfies "newest first", so there is no
        // separate {account_last4, occurred_month} index - it would be a
        // strict prefix of this one and MongoDB does not collapse redundant
        // indexes for you, so keeping both would just tax every write for a
        // query neither index-only combination serves any better than this
        // one already does.
        transactions.createIndex(
                Indexes.compoundIndex(
                        Indexes.ascending("account_last4"),
                        Indexes.ascending("occurred_month"),
                        Indexes.descending("occurred_at")),
                new IndexOptions().name("idx_account_month").background(true));
        transactions.createIndex(Indexes.ascending("account_last4"),
                new IndexOptions().name("idx_account").background(true));
        transactions.createIndex(Indexes.ascending("source_message_ids"),
                new IndexOptions().name("idx_message").background(true));
    }

    @Override
    public void save(NormalizedTxn txn) {
        transactions.replaceOne(eq("_id", idFor(txn)), toDocument(txn),
                new ReplaceOptions().upsert(true));
    }

    @Override
    public List<NormalizedTxn> forAccountMonth(String accountLast4, YearMonth month) {
        Bson filter = and(eq("account_last4", accountLast4),
                eq("occurred_month", month.format(MONTH)));
        List<NormalizedTxn> out = new ArrayList<>();
        try (var cursor = transactions.find(filter)
                .sort(Sorts.descending("occurred_at"))
                .iterator()) {
            while (cursor.hasNext()) out.add(fromDocument(cursor.next()));
        }
        return out;
    }

    @Override
    public Map<Category, BigDecimal> categoryTotals(String accountLast4) {
        Map<Category, BigDecimal> out = new EnumMap<>(Category.class);
        for (Category c : Category.values()) out.put(c, BigDecimal.ZERO.setScale(2));

        AggregateIterable<Document> result = transactions.aggregate(List.of(
                Aggregates.match(eq("account_last4", accountLast4)),
                Aggregates.group("$category", Accumulators.sum("total", "$amount"))));
        for (Document d : result) {
            Category c = Category.valueOf(d.getString("_id"));
            Object total = d.get("total");
            BigDecimal amount = total instanceof Decimal128 dec
                    ? dec.bigDecimalValue().setScale(2)
                    : new BigDecimal(String.valueOf(total)).setScale(2);
            out.put(c, amount);
        }
        return out;
    }

    @Override
    public Optional<NormalizedTxn> byMessageId(String messageId) {
        Document d = transactions.find(eq("source_message_ids", messageId)).first();
        return Optional.ofNullable(d).map(MongoDocumentStore::fromDocument);
    }

    /** Examined vs returned for each of the three queries, at whatever scale
     *  the collection currently holds - used by the "query-stats" CLI command
     *  to produce the six numbers README.md's "Document store" section asks
     *  for at 100,000 transactions. See QueryStats' own note on why those
     *  numbers are not filled in here. */
    public QueryStats explainForAccountMonth(String accountLast4, YearMonth month) {
        Bson filter = and(eq("account_last4", accountLast4),
                eq("occurred_month", month.format(MONTH)));
        FindIterable<Document> find = transactions.find(filter).sort(Sorts.descending("occurred_at"));
        return QueryStats.fromFindExplain("Q1 forAccountMonth",
                find.explain(ExplainVerbosity.EXECUTION_STATS));
    }

    public QueryStats explainCategoryTotals(String accountLast4) {
        AggregateIterable<Document> agg = transactions.aggregate(List.of(
                Aggregates.match(eq("account_last4", accountLast4)),
                Aggregates.group("$category", Accumulators.sum("total", "$amount"))));
        return QueryStats.fromAggregateExplain("Q2 categoryTotals",
                agg.explain(ExplainVerbosity.EXECUTION_STATS));
    }

    public QueryStats explainByMessageId(String messageId) {
        FindIterable<Document> find = transactions.find(eq("source_message_ids", messageId));
        return QueryStats.fromFindExplain("Q3 byMessageId",
                find.explain(ExplainVerbosity.EXECUTION_STATS));
    }

    @Override
    public void close() {
        client.close();
    }

    // ------------------------------------------------------------- mapping

    private static String idFor(NormalizedTxn t) {
        return t.accountLast4() + '#' + t.occurredAt() + '#' + t.direction() + '#'
                + t.amount().toPlainString();
    }

    private static Document toDocument(NormalizedTxn t) {
        LocalDate d = t.occurredAt().toLocalDate();
        Document doc = new Document();
        doc.put("_id", idFor(t));
        doc.put("account_last4", t.accountLast4());
        doc.put("occurred_at", t.occurredAt().toString());
        doc.put("occurred_month", d.format(MONTH));
        doc.put("direction", t.direction().name());
        doc.put("category", t.category().name());
        doc.put("amount", new Decimal128(t.amount()));
        doc.put("merchant", t.merchant());
        doc.put("source_message_ids", t.sourceMessageIds());
        return doc;
    }

    private static NormalizedTxn fromDocument(Document d) {
        Object amount = d.get("amount");
        BigDecimal amt = amount instanceof Decimal128 dec
                ? dec.bigDecimalValue().setScale(2)
                : new BigDecimal(String.valueOf(amount)).setScale(2);
        @SuppressWarnings("unchecked")
        List<String> sourceIds = (List<String>) d.get("source_message_ids");
        return new NormalizedTxn(
                d.getString("account_last4"),
                OffsetDateTime.parse(d.getString("occurred_at")),
                Direction.valueOf(d.getString("direction")),
                amt,
                Category.valueOf(d.getString("category")),
                d.getString("merchant"),
                sourceIds);
    }

    /** examined: docs the engine looked at. returned: docs it handed back.
     *  A query whose examined is close to returned is using its index well;
     *  a gap between them is a collection scan hiding behind a small result. */
    public record QueryStats(String query, long examined, long returned) {

        static QueryStats fromFindExplain(String query, Document explain) {
            Document stats = executionStats(explain);
            return new QueryStats(query,
                    stats.getInteger("totalDocsExamined", 0),
                    stats.getInteger("nReturned", 0));
        }

        static QueryStats fromAggregateExplain(String query, Document explain) {
            // Aggregation explain nests executionStats per pipeline stage
            // under "stages" when there is an initial $cursor stage; fall
            // back to a top-level executionStats for simple pipelines.
            Document stats = explain.containsKey("executionStats")
                    ? explain.get("executionStats", Document.class)
                    : firstStageExecutionStats(explain);
            return new QueryStats(query,
                    stats.getInteger("totalDocsExamined", 0),
                    stats.getInteger("nReturned", 0));
        }

        private static Document executionStats(Document explain) {
            return explain.get("executionStats", Document.class);
        }

        @SuppressWarnings("unchecked")
        private static Document firstStageExecutionStats(Document explain) {
            List<Document> stages = (List<Document>) explain.get("stages");
            for (Document stage : stages) {
                Document cursorStage = stage.get("$cursor", Document.class);
                if (cursorStage != null && cursorStage.containsKey("executionStats")) {
                    return cursorStage.get("executionStats", Document.class);
                }
            }
            return new Document("totalDocsExamined", 0).append("nReturned", 0);
        }
    }
}
