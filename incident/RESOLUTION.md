# INC-2026-09-11 — resolution note (for the incident channel)

**What broke:** `Amounts.AMOUNT` required a two-decimal group on the amount
itself. A whole-rupee SMS ("Rs.5 debited...") never matched, so the regex
scanner kept looking and locked onto the next number that did match — the
quoted account balance — and recorded that as the transaction amount instead.

**How we found it:** reproduced against the real corpus, not the illustrative
example in the incident doc — message `m-00022-2f118b` (account 4821, Rs.5 to
UPI/WATER CAN) — and added `IncidentINC20260911Test`, which fails against the
old regex and passes against the fix.

**Who was affected:** 28 messages / 19 distinct transactions across accounts
4821 and 9075 — every whole-rupee-amount SMS in the corpus, HDFC and ICICI
both, not just the one account that complained.

**The fix:** made the decimal group optional (`(?:\.[0-9]{1,2})?`) so a bare
integer amount matches itself instead of being skipped past.

**Why it won't recur:** the old suite was green throughout because nothing in
it exercised a whole-rupee amount; the fix ships together with the first test
that does, pinned to the real affected message so it can't regress silently.
