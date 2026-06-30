# Flip Exporter

A RuneLite plugin that writes **one clean, canonical data source** for the [`osrs-flipper`](../osrs-flipper) tool — replacing the two third-party plugins it currently stitches together (Flipping Utilities + Local Data Exporter) and the whole class of bugs that came from them.

## Why

Consuming two plugins not built for our needs caused recurring data bugs:

| problem | fixed here by |
|---|---|
| GE-collected stock arrives **noted** (id = unnoted+1, not in the GE mapping) → invisible to the flipper | resolving noted→tradeable id **in-plugin** (`getLinkedNoteId()`); every item is emitted under its tradeable id + a `noted` flag |
| offer price reported as `0` until it starts filling | real `listedPrice` (the price you set) always, even at 0% fill |
| Flipping Utilities' slot state doesn't repopulate cleanly on relog; two sources disagree | one source, read straight from the client's GE offers |
| no offer placement time; trade history scattered | per-slot `placedAt`, and a **persisted completed-trade log** for cost basis / audit |
| reader catching a half-written file | atomic write (temp file + rename) |

The Python brain stays in Python. This plugin only emits data.

## Output

Written to `~/.runelite/flip-exporter/`:

- **`latest.json`** — snapshot every few ticks: cash (coins + platinum), noted-resolved inventory, live GE offers (real prices, qty, `placedAt`), optional bank.
- **`history.json`** — append-only log of every completed/cancelled fill (item, buy/sell, qty, price, spent, timestamps). Survives restarts; the cost-basis + audit record.
- **`state.json`** — internal per-slot dedup state (so a fill is logged exactly once, even across restarts).

### `latest.json` shape
```jsonc
{
  "exporter": "flip-exporter", "schema": 1, "timestamp": 0,
  "rsn": "...", "world": 302, "gameState": "LOGGED_IN", "geSlots": 8,
  "coins": 532618, "platinum": 0, "cashOnHand": 532618,
  "inventory": { "loaded": true, "coins": 532618, "platinum": 0,
    "items": [ {"slot":0,"id":2114,"noted":true,"qty":1500,"name":"Pineapple","price":210,"value":315000} ] },
  "offers": [ {"slot":0,"state":"BUYING","isBuy":true,"id":2114,"name":"Pineapple",
    "listedPrice":202,"marketPrice":210,"total":1500,"completed":1500,"remaining":0,
    "spent":303000,"avgPrice":202,"placedAt":0} ]
}
```
`id` on inventory items is always the **tradeable** id (noted folded away), so it matches the journal directly.

### `history.json` shape
```jsonc
{ "exporter": "flip-exporter", "schema": 1, "trades": [
  {"uuid":"...","slot":0,"id":2114,"name":"Pineapple","isBuy":true,"qty":1500,
   "listedPrice":202,"spent":303000,"avgPrice":202,"state":"BOUGHT","placedAt":0,"completedAt":0} ] }
```

## Build & run

Requires a JDK (11+). The gradle wrapper is included.

**Test it (developer-mode RuneLite with the plugin loaded):**
```sh
./gradlew run
```
This launches a RuneLite instance with Flip Exporter active; log in and it starts writing `~/.runelite/flip-exporter/`.

**Install it into your normal RuneLite (daily use):**
```sh
./gradlew shadowJar
cp build/libs/flip-exporter-0.1.0-all.jar ~/.runelite/sideloaded-plugins/
```
Restart RuneLite; enable **Flip Exporter** in the plugin list. (Create `~/.runelite/sideloaded-plugins/` if it doesn't exist.)

Run the unit test (noted-resolution logic):
```sh
./gradlew test
```

## Config
- **Snapshot interval (ticks)** — default 5 (≈3s). History is written immediately on every fill regardless.
- **Export bank contents** — default off (the flipper keeps stock in your bag).
- **Trade history size** — default 20,000 (oldest dropped past this).

## Once it's running
Point the flipper at this source (a follow-up change to `osrs-flipper`'s readers): drop the Flipping Utilities + Local Data Exporter dependencies and read `latest.json` + `history.json` here. Noted resolution, real prices, placement times, and trade history all come from one place.
