# Chain fan-out probe

Measures what a multi-hop reconciliation would actually cost **before** deciding
whether SRSE should support N-way joins. Run it against real Golden Layer tables;
the answer is a number, not an opinion.

The question it answers: is a chain expensive *because it is a chain*, or because
of the key it is chained on?

## The shape

A chain is the case the current hub model cannot express — a third table that
relates to the **second** table rather than to the hub:

    beneficiary --(id = m_id)--> bank account --(account_no)--> transaction

Transactions belong to the account, not to the person, so this cannot be
rewritten as two independent hub↔target matches.

## Procedure

1. `ANALYZE` each table once so `SHOW STATS` has row counts and distinct counts:

   ```sql
   ANALYZE <catalog>.<schema>.<table>;
   SHOW STATS FOR <catalog>.<schema>.<table>;
   ```

2. Measure each hop's ACTUAL output. Substitute real tables and keys:

   ```sql
   -- hop 1
   SELECT count(*) FROM <A> a JOIN <B> b ON a.<ka> = b.<kb>;

   -- hop 2: the chain (third table keyed on B, not A)
   SELECT count(*) FROM <A> a
     JOIN <B> b ON a.<ka> = b.<kb>
     JOIN <C> c ON b.<kb2> = c.<kc>;
   ```

3. Estimate the same hops from statistics and compare. The estimator SRSE's
   fan-out guard already uses, applied per hop:

       est(hop1) = rows(A) x rows(B) / max(distinct(A.ka), distinct(B.kb))
       est(hop2) = est(hop1) x rows(C) / max(distinct(B.kb2), distinct(C.kc))

4. Record estimate, actual and error per hop.

## Baseline from the local synthetic stack (2026-09)

200,000 beneficiaries, 20,001 bank rows, 120,006 transactions (6 per account).

| Hop | Key | Estimated | Actual | Error |
|-----|-----|-----------|--------|-------|
| 1 | `id = m_id` (selective both sides) | 19,814 | 20,000 | 0.93% |
| 2 | `+ account_no` (the chain) | 117,536 | **120,000** | 2.05% |
| 1 | `district` (7 distinct) | 571,457,143 | 571,457,293 | 0.00% |
| 2 | `district` chain | 3,389,930,098 | **3,428,743,758** | 1.13% |

## What the baseline says

**A well-keyed chain is cheap.** Person → account → transaction came to 120,000
rows across three tables — trivially runnable, and about six times hop 1, which
is just the transactions-per-account ratio.

**The same three tables chained on a low-cardinality key cost 3.4 billion rows.**
Six times worse than the two-table district case (571M).

So the expense is not the chain. It is the key. That distinction matters for the
N-way decision recorded in CLAUDE.md: the objection to N-way was fan-out, and
fan-out turns out to be governed by key selectivity, which the existing guard
already measures — and which extends to chains at 1-2% accuracy per hop.

**What this does NOT settle.** Cost is only one of the three reasons the hub
model was chosen. An N-way query still gives up per-target failure isolation,
the per-target time budget, and partial results — one bad hop returns nothing
where today four of five targets still stream. Those are design losses, not
numbers, and no probe will decide them.

## Before reopening N-way

Run this against the real chain the department actually needs, not a synthetic
one. If a realistic Rajasthan chain lands in the low millions, the cost
objection is answered and what remains is the isolation trade. If it lands in
the hundreds of millions, the answer stays no and this file is the reason.
