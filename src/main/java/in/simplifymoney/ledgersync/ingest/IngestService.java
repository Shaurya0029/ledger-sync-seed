package in.simplifymoney.ledgersync.ingest;

import in.simplifymoney.ledgersync.json.Json;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import in.simplifymoney.ledgersync.model.RawMessage;
import in.simplifymoney.ledgersync.parse.ParsedTxn;
import in.simplifymoney.ledgersync.parse.Parsers;
import in.simplifymoney.ledgersync.store.LedgerStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Reads a corpus of raw messages and puts transactions in the ledger.
 *
 * Three passes over the corpus, in order:
 *
 *   1. parse   every message on its own, independently (Parsers)
 *   2. dedupe  messages that evidence the same real transaction (Deduper)
 *   3. categorize  decide SPEND / INCOME / MICRO / TRANSFER (Categorizer)
 *
 * A fourth pass, Reconciler, runs alongside categorization: it uses the
 * balances the SMS parsers captured (and which categorization discards, since
 * NormalizedTxn does not carry one) to find account movement that no message
 * explains. That can only happen while this balance evidence is still in
 * memory, so its output is written now, to a side file next to the ledger
 * database, for the separate "report" run to pick back up - see
 * reconciliationFile().
 */
public final class IngestService {

    private final Parsers parsers;
    private final LedgerStore store;
    private final Path reconciliationFile;

    public IngestService(Parsers parsers, LedgerStore store) {
        this(parsers, store, null);
    }

    public IngestService(Parsers parsers, LedgerStore store, Path reconciliationFile) {
        this.parsers = parsers;
        this.store = store;
        this.reconciliationFile = reconciliationFile;
    }

    public Stats ingestFile(Path corpus) throws IOException {
        List<RawMessage> messages = readCorpus(corpus);

        List<ParsedTxn> parsed = new ArrayList<>();
        int skipped = 0;
        for (RawMessage m : messages) {
            Optional<ParsedTxn> p = parsers.parse(m);
            if (p.isEmpty()) {
                skipped++;
            } else {
                parsed.add(p.get());
            }
        }

        List<MergedTxn> merged = Deduper.dedupe(parsed);
        List<NormalizedTxn> ledger = Categorizer.categorize(merged);
        for (NormalizedTxn t : ledger) {
            store.save(t);
        }

        if (reconciliationFile != null) {
            List<Map<String, Object>> discrepancies = Reconciler.reconcile(merged);
            Files.createDirectories(reconciliationFile.toAbsolutePath().getParent());
            Files.writeString(reconciliationFile, Json.writePretty(discrepancies));
        }

        return new Stats(messages.size(), ledger.size(), skipped);
    }

    public static List<RawMessage> readCorpus(Path corpus) throws IOException {
        List<RawMessage> out = new ArrayList<>();
        try (Stream<String> lines = Files.lines(corpus)) {
            for (String line : (Iterable<String>) lines.filter(s -> !s.isBlank())::iterator) {
                Map<String, Object> o = Json.parseObject(line);
                out.add(new RawMessage(
                        (String) o.get("message_id"),
                        (String) o.get("channel"),
                        (String) o.get("sender"),
                        OffsetDateTime.parse((String) o.get("received_at")),
                        (String) o.get("device_id"),
                        (String) o.get("body")));
            }
        }
        return out;
    }

    /** messagesRead: raw lines in the corpus. transactionsWritten: distinct
     *  real transactions after dedup - not one per message.
     *  messagesSkipped: messages no parser recognised as a transaction
     *  (OTPs, balance enquiries, adverts, phishing, courier/order pings). */
    public record Stats(int messagesRead, int transactionsWritten, int messagesSkipped) {}
}
