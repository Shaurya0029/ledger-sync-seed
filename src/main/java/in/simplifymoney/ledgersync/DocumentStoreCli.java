package in.simplifymoney.ledgersync;

import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import in.simplifymoney.ledgersync.store.Backfill;
import in.simplifymoney.ledgersync.store.ConsistencyChecker;
import in.simplifymoney.ledgersync.store.MongoDocumentStore;
import in.simplifymoney.ledgersync.store.SqlLedgerStore;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Random;

/**
 * Entry point for everything Task 4 (the document store) needs that App's
 * "migrate / ingest / report" pipeline does not.
 *
 * This is a SEPARATE main class from App on purpose. build.gradle documents
 * the main source set as compiling "against the JDK alone" and verify.sh
 * proves that with a bare `javac` sweep of every file under src/main/java, no
 * classpath. The document store is the one place this project genuinely
 * needs an external dependency (the MongoDB driver - see MongoDocumentStore's
 * class doc for why Mongo), so rather than pull that dependency into App
 * (and break the dependency-free build every path through Tasks 2 and 3 and
 * verify.sh relies on), it lives here. verify.sh's compile step explicitly
 * excludes this file and MongoDocumentStore.java for that reason - see its
 * own comment.
 *
 *   backfill                                 move the SQL ledger into the document store
 *   check-consistency                        compare SQL and the document store, print
 *                                             divergences
 *   seed-documents <n>                       write n synthetic transactions straight into
 *                                             the document store (SQL/the real corpus are
 *                                             not touched) so query-stats has something to
 *                                             measure at 100,000-transaction scale
 *   query-stats [acct] [yyyy-MM] [messageId]  print examined-vs-returned for the three
 *                                             DocumentStore queries, against whatever the
 *                                             store currently holds
 *
 * All four need a MongoDB reachable at $MONGO_URI (default
 * mongodb://localhost:27017 - see docker-compose.yml: `docker compose up`).
 * Run with: ./gradlew runDocumentStoreCli --args="<command> ..."
 */
public final class DocumentStoreCli {

    private static final Path DB = Path.of("data", "ledger");
    private static final String MONGO_URI =
            System.getenv().getOrDefault("MONGO_URI", "mongodb://localhost:27017");
    private static final String MONGO_DB = "ledgersync";

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("usage: backfill | check-consistency | seed-documents <n> "
                    + "| query-stats [acct] [yyyy-MM] [messageId]");
            System.exit(2);
        }

        switch (args[0]) {
            case "backfill" -> {
                try (SqlLedgerStore sqlStore = new SqlLedgerStore(DB);
                        MongoDocumentStore docs = new MongoDocumentStore(MONGO_URI, MONGO_DB)) {
                    System.out.println(new Backfill(sqlStore, docs).run());
                }
            }
            case "check-consistency" -> {
                try (SqlLedgerStore sqlStore = new SqlLedgerStore(DB);
                        MongoDocumentStore docs = new MongoDocumentStore(MONGO_URI, MONGO_DB)) {
                    var divergences = new ConsistencyChecker(sqlStore, docs).check();
                    if (divergences.isEmpty()) {
                        System.out.println("no divergences found");
                    } else {
                        System.out.println(divergences.size() + " divergence(s):");
                        for (var d : divergences) {
                            System.out.println("  " + d.what() + ": sql=" + d.inSql()
                                    + " documents=" + d.inDocuments());
                        }
                    }
                }
            }
            case "seed-documents" -> {
                int n = args.length > 1 ? Integer.parseInt(args[1]) : 100_000;
                try (MongoDocumentStore docs = new MongoDocumentStore(MONGO_URI, MONGO_DB)) {
                    seedDocuments(docs, n);
                    System.out.println("seeded " + n + " transactions (probe: account 4821, "
                            + "month 2026-07, message id seed-probe-000000)");
                }
            }
            case "query-stats" -> {
                String acct = args.length > 1 ? args[1] : "4821";
                YearMonth month = args.length > 2 ? YearMonth.parse(args[2]) : YearMonth.of(2026, 7);
                String messageId = args.length > 3 ? args[3] : "seed-probe-000000";
                try (MongoDocumentStore docs = new MongoDocumentStore(MONGO_URI, MONGO_DB)) {
                    System.out.println(docs.explainForAccountMonth(acct, month));
                    System.out.println(docs.explainCategoryTotals(acct));
                    System.out.println(docs.explainByMessageId(messageId));
                }
            }
            default -> {
                System.err.println("unknown command: " + args[0]);
                System.exit(2);
            }
        }
    }

    /** Writes n synthetic-but-plausible transactions straight into the
     *  document store. Always includes a deterministic probe transaction -
     *  account 4821, 2026-07, message id seed-probe-000000 - so query-stats
     *  has something specific to look up rather than a random draw. */
    private static void seedDocuments(MongoDocumentStore docs, int n) {
        String[] accounts = {"4821", "9075", "3310", "1123", "5588", "7741"};
        Category[] categories = Category.values();
        Random random = new Random(42);

        docs.save(new NormalizedTxn("4821",
                OffsetDateTime.of(2026, 7, 15, 12, 0, 0, 0, ZoneOffset.ofHoursMinutes(5, 30)),
                Direction.DEBIT, new BigDecimal("100.00"), Category.SPEND, "PROBE MERCHANT",
                List.of("seed-probe-000000")));

        for (int i = 1; i < n; i++) {
            String acct = accounts[random.nextInt(accounts.length)];
            int monthOffset = random.nextInt(24);
            YearMonth ym = YearMonth.of(2025, 1).plusMonths(monthOffset);
            int day = 1 + random.nextInt(28);
            OffsetDateTime when = OffsetDateTime.of(ym.getYear(), ym.getMonthValue(), day,
                    random.nextInt(24), random.nextInt(60), 0, 0, ZoneOffset.ofHoursMinutes(5, 30));
            Direction dir = random.nextBoolean() ? Direction.DEBIT : Direction.CREDIT;
            Category cat = dir == Direction.CREDIT
                    ? Category.INCOME : categories[random.nextInt(categories.length)];
            BigDecimal amount = BigDecimal.valueOf(1 + random.nextInt(500_000), 2);
            docs.save(new NormalizedTxn(acct, when, dir, amount, cat, "SEEDED MERCHANT " + (i % 40),
                    List.of("seed-" + String.format("%06d", i))));
        }
    }
}
