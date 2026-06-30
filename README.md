# Flip Exporter

A RuneLite plugin that exports flipping-relevant account data to local JSON, so you can build your
own Grand Exchange analysis, spreadsheets, dashboards, or tooling. **Local files only — no network,
no automation, no strategy.** It just gives you clean, canonical data; what you do with it is yours.

## Why another exporter

It emits the things flipping tools actually need, correctly, in one place:

- **Noted items resolved** — items collected from the GE land in your bag *noted* (a different item
  id that isn't the tradeable one). This plugin folds them back onto their tradeable id (via
  `getLinkedNoteId`) and flags them, so collected stock matches your offers/history.
- **Real offer prices** — the listed price you set on an offer, available even at 0% fill.
- **Placement times** — a per-slot timestamp for when each offer was placed.
- **Persisted trade history** — an append-only, deduped log of every completed/cancelled fill, the
  basis for cost and realised P&L. Survives restarts.
- **Cash on hand** — coins + platinum tokens, the deployable amount.
- **Atomic writes** — a reader never catches a half-written file.

## Output

Written to `~/.runelite/flip-exporter/`:

- **`latest.json`** — snapshot every few ticks: cash, inventory (noted-resolved), live GE offers,
  optional bank.
- **`history.json`** — persisted completed/cancelled trades.
- **`state.json`** — internal per-slot dedup/placement state.

### `latest.json`
```jsonc
{
  "exporter": "flip-exporter", "schema": 1, "timestamp": 0,
  "rsn": "...", "world": 302, "gameState": "LOGGED_IN", "geSlots": 8,
  "coins": 532618, "platinum": 0, "cashOnHand": 532618,
  "inventory": { "loaded": true, "coins": 532618, "platinum": 0,
    "items": [ {"slot":0,"id":2114,"noted":true,"qty":1500,"name":"Pineapple","price":210,"value":315000} ] },
  "offers": [ {"slot":0,"uuid":"...","state":"BUYING","isBuy":true,"id":2114,"name":"Pineapple",
    "listedPrice":202,"marketPrice":210,"total":1500,"completed":1500,"remaining":0,
    "spent":303000,"avgPrice":202,"placedAt":0} ]
}
```
`id` on inventory items is always the **tradeable** id (noted folded away).

### `history.json`
```jsonc
{ "exporter": "flip-exporter", "schema": 1, "trades": [
  {"uuid":"...","slot":0,"id":2114,"name":"Pineapple","isBuy":true,"qty":1500,
   "listedPrice":202,"spent":303000,"avgPrice":202,"state":"BOUGHT","placedAt":0,"completedAt":0} ] }
```

## Config
- **Snapshot interval (ticks)** — default 5 (≈3s). History is written immediately on every fill.
- **Export bank contents** — default off (only refreshes while the bank is open).
- **Trade history size** — default 20,000 (oldest dropped past this).

## Build (for development)
```sh
./gradlew test     # unit tests
./gradlew run      # launch RuneLite (developer mode) with the plugin, for local testing
```
On macOS / JDK 9+ the `run` task already passes the `--add-opens`/`--add-exports` flags RuneLite's UI
needs from source.

## Privacy
Writes only to local files under `~/.runelite/flip-exporter/`. No network connections. No game
automation. It reads your own account state and writes it to your own disk.
