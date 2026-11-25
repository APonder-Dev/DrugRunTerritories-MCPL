# Economy

All money effects are configurable, capped, and logged. Nothing in DrugRunTerritories withdraws money from players.

## Dealer sale bonus (Docks)

- Applies to DrugLab **SELL** transactions only, for members of a faction that controls a territory with a `DEALER_SELL` bonus.
- The multiplier is applied to DrugLab's finished price, so DrugLab's quality pricing, demand, daily requests, boosters and buy-back ceiling all still apply first.
- Stacking: several DEALER_SELL territories combine with `territories.bonuses.stacking` and never exceed `hard-cap` (default 1.25).
- Caps: `dealer.max-bonus-per-transaction`. The result also never exceeds what the dealer charges to buy the same items, so buy-and-resell stays unprofitable.
- Requirements: `dealer.minimum-rank`, optional `dealer.allowed-worlds`.
- DrugLab makes the single deposit with the final price. A cancelled or failed sale pays no bonus, because the bonus is part of the one payment.
- When faction data can't be read, no bonus applies.

## Recurring income (Downtown)

Each `income` territory pays `amount` every `interval-minutes` of ownership, at most `max-payouts-per-ownership` times. Payment goes to the faction leader (`recipient: LEADER`) or is split between online members (`ONLINE_MEMBERS`).

### Idempotent payouts

1. Every payout has a unique key: `territory:ownershipId:period`. A new capture creates a new ownership id.
2. The key is **claimed** in `drt_income_payouts` (INSERT … IGNORE) before any money moves. A second claim of the same key, from a retry, a reload, a restart or a concurrent tick, inserts nothing and is dropped.
3. On the main thread the payout is re-validated: same ownership still current, period ended before ownership expiry, faction still exists, economy available, recipient found.
4. Vault deposit.
5. Row marked PAID or FAILED, and an audit entry written.

If the server stops between steps 2 and 5, the row stays PENDING. On the next start, every PENDING row becomes **UNKNOWN**, is reported in the console and the audit log, and is **never retried**: double payment is ruled out, at the cost of possibly missing one payout. Review them with `/drugrun admin economy audit unknown`.

By default (`pay-missed-periods: false`) periods that ended while the server was offline are not back-paid.

### No payouts when

- the territory is no longer owned by the same ownership (captured, reset, expired, disbanded)
- the period ends after the ownership expiry
- the faction no longer exists
- no economy is available, or no leader/online member exists (recorded as FAILED, not retried)

## Capture money rewards

`rewards.money` in a territory is paid once per ownership, with the same claim-first ledger (period index 0).

## Auditing

- Every payout: `economy.income.paid|failed`, `economy.capture-reward.paid|failed`
- Recovery: `economy.recovery`
- Admin actions: `admin.*`

Entries go to `drt_audit_log` and the server log. `/drugrun admin economy audit [all|paid|failed|unknown]` lists recent payouts.
