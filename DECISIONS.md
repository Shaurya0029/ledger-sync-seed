# Decision log

Rationale for the choices in this submission, roughly in the order they came
up. See `incident/RESOLUTION.md` for the incident writeup specifically.

1. **Amount regex made decimal-optional, not decimal-mandatory (the incident
   fix).** `Amounts.AMOUNT` required `\.[0-9]{2}` on the amount itself. Bank
   SMS sometimes state a whole-rupee amount with no paise ("Rs.5 debited...");
   that never matched, so `Matcher.find()` kept scanning and locked onto the
   next number that did — the quoted balance. Fixed by making the decimal
   group optional. Full writeup: `incident/RESOLUTION.md`.

2. **Dedup key is `account + occurredAt + direction + amount`, not merchant
   text.** Those four fields identify the real-world transaction; merchant
   text is free-form and can differ slightly between the SMS and email that
   both describe the same event (abbreviations, extra whitespace). Keying on
   merchant would under-merge — the same transaction counted twice because two
   channels phrased the merchant differently.

3. **TRANSFER is detected structurally, not by merchant label.** A debit on
   one of the user's accounts is paired with a credit of the same amount on a
   *different* account of the same user within a 15-minute window. Bank SMS
   merchant text for self-transfers is inconsistent across banks and formats
   (sometimes "SELF", sometimes the destination account number, sometimes
   nothing distinguishing at all), so a label match would miss cases a
   structural match catches.

4. **MICRO's UPI check is `merchant.startsWith("UPI")`, not `"UPI/"`.** Found
   `UPI MANDATE VERIFY` in the corpus — space-separated, not slash-separated —
   which the original slash-anchored check silently excluded, undercounting
   account 9075's micro_count by one and its spend/micro_total by exactly
   ₹0.50. The category is about *the UPI channel*, not a specific merchant
   string format, so the broader check matches the actual intent.

5. **Reconciliation ignores a credit card's "Avl Limit" as evidence of
   balance.** `HdfcSmsParser`'s `CARD` branch used to feed the quoted "Avl
   Limit" into `statedBalance`, but a credit limit is not an account balance —
   it moves non-monotonically and independently of the transaction stream
   (billing cycles, limit changes), which produced ~20 false-positive
   reconciliation discrepancies on account 3310 alone. Card transactions now
   carry `statedBalance = null` and are excluded from balance-continuity
   checking, dropping false positives from 20 to the correct 1.

6. **Reconciliation is written once during `ingest`, to a side JSON file, and
   picked back up by `report`.** The balance evidence `Reconciler` needs
   (each message's bank-stated running balance) only exists on `ParsedTxn`/
   `MergedTxn`, before `Categorizer` builds the frozen `NormalizedTxn`, which
   does not carry a balance field. Rather than widen a frozen class or add a
   new SQL table for a value that is only ever computed once and read once,
   `ingest` computes it while the evidence still exists and leaves it at
   `data/reconciliation.json` for `report` to read.

7. **`App.java` and `DocumentStoreCli.java` are separate entry points.**
   `build.gradle` documents the main source set as compiling "against the JDK
   alone," and `verify.sh` proves that with a bare `javac` sweep of every file
   under `src/main/java`. The MongoDB driver is the one real external
   dependency this project needs, so the Task 4 commands (`backfill`,
   `check-consistency`, `seed-documents`, `query-stats`) live in their own
   main class instead of pulling that dependency into `App` and breaking the
   dependency-free build that every Task 2/3 path — and `verify.sh` — relies
   on. `verify.sh` explicitly excludes `MongoDocumentStore.java` and
   `DocumentStoreCli.java` from its compile sweep for the same reason.

8. **MongoDB over DynamoDB.** The assignment prefers DynamoDB but allows
   Mongo with justification. This codebase's only prior external dependency
   (H2) needed zero AWS-specific machinery — no IAM, no account, nothing
   beyond a driver on the classpath. Mongo keeps that shape: one dependency,
   `docker compose up`, nothing else to configure. DynamoDB Local is a real
   option, but it's a DynamoDB *emulator* shipped as a JAR with native SQLite
   bindings — a worse "runs from docker compose" story than a database whose
   real production form already runs as a container. Full rationale and the
   DynamoDB-equivalent mapping for each index is in `MongoDocumentStore`'s
   class doc, in case the choice needs to be revisited later.

9. **Document `_id` is a deterministic composite key
   (`account#occurredAt#direction#amount`), not a generated one.** This makes
   `save()` an upsert by construction, which is what makes `Backfill`
   idempotent across reruns (including after a partial failure) without any
   "have I run before" bookkeeping of its own — and without relying on a
   uniqueness constraint the source SQL table doesn't have (`V2__seed.sql`
   ships two literal duplicate rows on purpose, to test exactly this).

10. **The totals checkpoint's own numbers don't add up on their face, and
    that's not a pipeline bug.** `fixtures/corpus-a-totals.json` states
    `transactions_expected: 257`, but the two accounts listed under
    `accounts` sum to 146 + 91 = 237. A third account, 3310 (an HDFC credit
    card), has 20 transactions in the corpus that aren't listed under
    `accounts` in that file at all; 146 + 91 + 20 = 257. Worth recording
    because it would otherwise look like a 20-transaction miss in this
    pipeline when it's actually an incomplete checkpoint file.

11. **What could and couldn't be verified in the build environment used for
    this submission.** That environment has no route to Maven Central and no
    Docker daemon. Tasks 2 and 3 (the dependency-free path — parsing, dedup,
    categorization, summary, reconciliation) were fully verified against the
    real corpus via `./verify.sh` and standalone `javac`/`java` harnesses, and
    match `fixtures/corpus-a-totals.json` exactly for account 9075 and all but
    one deliberately-explained field for account 4821. `MongoDocumentStore`,
    `Backfill`, `ConsistencyChecker`, and the JUnit suite (including
    `IncidentINC20260911Test`) could not be compiled or run there and were
    instead reviewed by hand against the MongoDB Java sync driver's 5.x API.
    They need a real run — `docker compose up`, then `./gradlew test` and the
    `runDocumentStoreCli` commands below — before submission, and one review
    pass already caught and fixed two issues this way: a redundant Mongo
    index, and a `ConsistencyChecker` field comparison that was reporting
    every message as divergent regardless of whether it actually differed.
    See "Document store" in README.md for the exact commands and the six
    query-stats numbers that still need to be filled in from that run.

12. **AI usage.** This submission was built working directly with Claude
    (Anthropic), used as a pairing/drafting tool under my direction —
    reviewing the assignment, writing and reviewing code, and drafting this
    log — with me making the calls on approach and reviewing the output.
    Saying so plainly here rather than leaving it implicit.
