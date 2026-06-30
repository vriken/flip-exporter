package dev.flipexporter;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.google.inject.Inject;
import com.google.inject.Provides;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.Player;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.GrandExchangeOfferChanged;
import net.runelite.client.RuneLite;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

@Slf4j
@PluginDescriptor(
		name = "Flip Exporter",
		description = "Canonical flipping data export: cash, noted-resolved inventory, live GE offers, trade history.",
		tags = {"flipping", "grand exchange", "data", "export", "json"}
)
public class FlipExporterPlugin extends Plugin
{
	static final String EXPORTER = "flip-exporter";
	static final int SCHEMA = 1;
	static final String VERSION = "0.1.0";
	private static final int LOGIN_SETTLE_TICKS = 4;
	private static final int COINS_ID = 995;
	private static final int PLATINUM_ID = 13204;

	@Inject private Gson gson;
	@Inject private Client client;
	@Inject private ItemManager itemManager;
	@Inject private ConfigManager configManager;

	private int ticksLoggedIn;
	private int ticksSinceExport;

	/** Per-slot tracking so each offer instance is recorded to history exactly once, with a stable
	 *  placement time, even across client restarts. Persisted to state.json. */
	private Map<Integer, SlotState> slots = new LinkedHashMap<>();
	/** Completed-trade log (the cost-basis / audit record). Persisted to history.json. */
	private List<Map<String, Object>> history = new ArrayList<>();

	@Provides
	FlipExporterConfig provideConfig(ConfigManager cm)
	{
		return cm.getConfig(FlipExporterConfig.class);
	}

	@Override
	protected void startUp()
	{
		ticksLoggedIn = 0;
		ticksSinceExport = 0;
		loadState();
		log.info("Flip Exporter v{} started ({} historical trades loaded).", VERSION, history.size());
	}

	@Override
	protected void shutDown()
	{
		log.info("Flip Exporter stopped.");
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		if (client.getGameState() != GameState.LOGGED_IN || client.getLocalPlayer() == null)
		{
			ticksLoggedIn = 0;
			ticksSinceExport = 0;
			return;
		}
		ticksLoggedIn++;
		ticksSinceExport++;
		if (ticksLoggedIn < LOGIN_SETTLE_TICKS || ticksSinceExport < intervalTicks())
		{
			return;
		}
		ticksSinceExport = 0;
		try
		{
			exportSnapshot();
		}
		catch (RuntimeException e)
		{
			log.warn("Flip Exporter snapshot failed.", e);
		}
	}

	// --- Trade history: record every completed/cancelled fill once, the moment it happens --------

	@Subscribe
	public void onGrandExchangeOfferChanged(GrandExchangeOfferChanged event)
	{
		int slot = event.getSlot();
		GrandExchangeOffer offer = event.getOffer();
		if (offer == null || offer.getState() == null)
		{
			return;
		}
		GrandExchangeOfferState state = offer.getState();
		long now = System.currentTimeMillis();

		if (state == GrandExchangeOfferState.EMPTY)
		{
			slots.remove(slot);
			saveState();
			return;
		}

		int itemId = offer.getItemId();
		SlotState s = slots.get(slot);
		if (s == null || s.itemId != itemId)
		{
			// a fresh offer instance in this slot — give it a stable id + placement time
			s = new SlotState();
			s.uuid = UUID.randomUUID().toString();
			s.itemId = itemId;
			s.placedAt = now;
			s.recorded = false;
			slots.put(slot, s);
		}

		boolean terminal = state == GrandExchangeOfferState.BOUGHT
				|| state == GrandExchangeOfferState.SOLD
				|| state == GrandExchangeOfferState.CANCELLED_BUY
				|| state == GrandExchangeOfferState.CANCELLED_SELL;
		int sold = offer.getQuantitySold();
		if (terminal && sold > 0 && !s.recorded)
		{
			boolean isBuy = state == GrandExchangeOfferState.BOUGHT || state == GrandExchangeOfferState.CANCELLED_BUY;
			int spent = offer.getSpent();
			Map<String, Object> trade = new LinkedHashMap<>();
			trade.put("uuid", s.uuid);
			trade.put("slot", slot);
			trade.put("id", itemId);                 // GE items are tradeable (unnoted) ids
			trade.put("name", itemName(itemId));
			trade.put("isBuy", isBuy);
			trade.put("qty", sold);
			trade.put("listedPrice", offer.getPrice());
			trade.put("spent", spent);
			trade.put("avgPrice", sold > 0 ? Math.round(spent / (double) sold) : offer.getPrice());
			trade.put("state", state.name());
			trade.put("placedAt", s.placedAt);
			trade.put("completedAt", now);
			history.add(trade);
			int max = Math.max(100, config().maxHistoryTrades());
			while (history.size() > max)
			{
				history.remove(0);
			}
			s.recorded = true;
			saveHistory();
		}
		saveState();
	}

	// --- Snapshot (cash + inventory + offers) ---------------------------------------------------

	private void exportSnapshot()
	{
		Player me = client.getLocalPlayer();
		if (me == null)
		{
			return;
		}
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("exporter", EXPORTER);
		out.put("schema", SCHEMA);
		out.put("version", VERSION);
		out.put("timestamp", System.currentTimeMillis());
		out.put("timestampIso", Instant.now().toString());
		out.put("rsn", me.getName());
		out.put("world", client.getWorld());
		out.put("gameState", client.getGameState().name());
		out.put("geSlots", 8);

		Map<String, Object> inv = buildInventory();
		out.put("inventory", inv);
		long coins = getLong(inv.get("coins"));
		long platinum = getLong(inv.get("platinum"));
		out.put("coins", coins);
		out.put("platinum", platinum);
		out.put("cashOnHand", coins + platinum * 1000L);   // deployable gp

		if (config().exportBank())
		{
			out.put("bank", buildContainer(InventoryID.BANK));
		}
		out.put("offers", buildOffers());

		File dir = exportDir();
		if (!dir.exists() && !dir.mkdirs())
		{
			log.warn("Flip Exporter could not create {}", dir.getAbsolutePath());
			return;
		}
		writeAtomic(new File(dir, "latest.json"), out);
	}

	/** Inventory items, with NOTED items folded onto their tradeable id (collected GE stock arrives
	 *  noted; analysis tools track tradeable ids). Also pulls out coins (995) + platinum (13204). */
	private Map<String, Object> buildInventory()
	{
		Map<String, Object> result = new LinkedHashMap<>();
		ItemContainer container = client.getItemContainer(InventoryID.INVENTORY);
		boolean loaded = container != null;
		result.put("loaded", loaded);
		result.put("fromCache", false);
		result.put("lastSeen", System.currentTimeMillis());

		long coins = 0;
		long platinum = 0;
		List<Map<String, Object>> items = new ArrayList<>();
		if (container != null)
		{
			Item[] raw = container.getItems();
			for (int slot = 0; slot < raw.length; slot++)
			{
				Item item = raw[slot];
				if (item == null || item.getId() <= 0 || item.getQuantity() <= 0)
				{
					continue;
				}
				int rawId = item.getId();
				int qty = item.getQuantity();
				if (rawId == COINS_ID)
				{
					coins += qty;
					continue;
				}
				if (rawId == PLATINUM_ID)
				{
					platinum += qty;
					continue;
				}
				ItemComposition comp = itemManager.getItemComposition(rawId);
				int id = canonicalId(rawId, comp.getNote(), comp.getLinkedNoteId());
				boolean noted = comp.getNote() != -1;
				int price = itemManager.getItemPrice(id);
				Map<String, Object> it = new LinkedHashMap<>();
				it.put("slot", slot);
				it.put("id", id);              // tradeable (unnoted) id — matches offers + history
				it.put("noted", noted);
				it.put("name", itemName(id));
				it.put("qty", qty);
				it.put("price", price);
				it.put("value", (long) price * qty);
				items.add(it);
			}
		}
		result.put("coins", coins);
		result.put("platinum", platinum);
		result.put("items", items);
		return result;
	}

	private List<Map<String, Object>> buildOffers()
	{
		List<Map<String, Object>> out = new ArrayList<>();
		GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
		if (offers == null)
		{
			return out;
		}
		for (int slot = 0; slot < offers.length; slot++)
		{
			GrandExchangeOffer offer = offers[slot];
			if (offer == null || offer.getState() == null || offer.getState() == GrandExchangeOfferState.EMPTY)
			{
				continue;
			}
			GrandExchangeOfferState state = offer.getState();
			int itemId = offer.getItemId();
			int total = offer.getTotalQuantity();
			int completed = offer.getQuantitySold();
			int spent = offer.getSpent();
			SlotState s = slots.get(slot);
			Map<String, Object> o = new LinkedHashMap<>();
			o.put("slot", slot);
			o.put("uuid", s != null ? s.uuid : null);   // same id this offer carries into history.json
			o.put("state", state.name());
			o.put("isBuy", state.name().contains("BUY") || state == GrandExchangeOfferState.BOUGHT);
			o.put("id", itemId);
			o.put("name", itemName(itemId));
			o.put("listedPrice", offer.getPrice());        // the price YOU set — known at 0% fill
			o.put("marketPrice", itemId > 0 ? itemManager.getItemPrice(itemId) : 0);
			o.put("total", total);
			o.put("completed", completed);
			o.put("remaining", Math.max(0, total - completed));
			o.put("spent", spent);
			o.put("avgPrice", completed > 0 ? Math.round(spent / (double) completed) : offer.getPrice());
			o.put("placedAt", s != null ? s.placedAt : 0L);
			out.add(o);
		}
		return out;
	}

	private Map<String, Object> buildContainer(InventoryID id)
	{
		Map<String, Object> result = new LinkedHashMap<>();
		ItemContainer container = client.getItemContainer(id);
		result.put("loaded", container != null);
		List<Map<String, Object>> items = new ArrayList<>();
		if (container != null)
		{
			for (Item item : container.getItems())
			{
				if (item == null || item.getId() <= 0 || item.getQuantity() <= 0)
				{
					continue;
				}
				ItemComposition comp = itemManager.getItemComposition(item.getId());
				int cid = canonicalId(item.getId(), comp.getNote(), comp.getLinkedNoteId());
				Map<String, Object> it = new LinkedHashMap<>();
				it.put("id", cid);
				it.put("noted", comp.getNote() != -1);
				it.put("name", itemName(cid));
				it.put("qty", item.getQuantity());
				items.add(it);
			}
		}
		result.put("items", items);
		return result;
	}

	/** Fold a noted item id onto its tradeable (unnoted) id. Pure for testability: RuneLite's
	 *  ItemComposition.getNote() is -1 for unnoted items and the note template (799) for noted ones,
	 *  and getLinkedNoteId() is the linked (unnoted) id of a noted item. */
	static int canonicalId(int id, int note, int linkedNoteId)
	{
		return (note != -1 && linkedNoteId > 0) ? linkedNoteId : id;
	}

	private String itemName(int id)
	{
		try
		{
			return id > 0 ? itemManager.getItemComposition(id).getName() : "";
		}
		catch (RuntimeException e)
		{
			return String.valueOf(id);
		}
	}

	// --- Persistence ----------------------------------------------------------------------------

	private void loadState()
	{
		File hist = new File(exportDir(), "history.json");
		Map<String, Object> h = readJson(hist, new TypeToken<Map<String, Object>>() {}.getType());
		history = new ArrayList<>();
		if (h != null && h.get("trades") instanceof List)
		{
			for (Object t : (List<?>) h.get("trades"))
			{
				if (t instanceof Map)
				{
					@SuppressWarnings("unchecked")
					Map<String, Object> m = (Map<String, Object>) t;
					history.add(m);
				}
			}
		}
		Map<String, SlotState> st = readJson(new File(exportDir(), "state.json"),
				new TypeToken<Map<String, SlotState>>() {}.getType());
		slots = new LinkedHashMap<>();
		if (st != null)
		{
			st.forEach((k, v) -> slots.put(Integer.parseInt(k), v));
		}
	}

	private void saveHistory()
	{
		Map<String, Object> h = new LinkedHashMap<>();
		h.put("exporter", EXPORTER);
		h.put("schema", SCHEMA);
		h.put("trades", history);
		File dir = exportDir();
		if (dir.exists() || dir.mkdirs())
		{
			writeAtomic(new File(dir, "history.json"), h);
		}
	}

	private void saveState()
	{
		File dir = exportDir();
		if (dir.exists() || dir.mkdirs())
		{
			writeAtomic(new File(dir, "state.json"), slots);
		}
	}

	private <T> T readJson(File file, Type type)
	{
		if (file == null || !file.exists())
		{
			return null;
		}
		try (Reader r = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8))
		{
			return gson.fromJson(r, type);
		}
		catch (IOException | RuntimeException e)
		{
			log.debug("Flip Exporter could not read {}", file.getName(), e);
			return null;
		}
	}

	/** Write via a temp file + atomic rename, so a reader never sees a half-written JSON. */
	private void writeAtomic(File file, Object data)
	{
		File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
		try
		{
			try (Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8))
			{
				gson.toJson(data, w);
			}
			try
			{
				Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			}
			catch (IOException atomicUnsupported)
			{
				Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
			}
		}
		catch (IOException e)
		{
			log.warn("Flip Exporter failed to write {}", file.getName(), e);
		}
	}

	private File exportDir()
	{
		return new File(RuneLite.RUNELITE_DIR, EXPORTER);
	}

	private int intervalTicks()
	{
		return Math.max(1, config().exportIntervalTicks());
	}

	private FlipExporterConfig config()
	{
		return configManager.getConfig(FlipExporterConfig.class);
	}

	private static long getLong(Object v)
	{
		return v instanceof Number ? ((Number) v).longValue() : 0L;
	}

	/** Per-slot offer tracking, persisted so history dedup + placement times survive restarts. */
	static class SlotState
	{
		String uuid;
		int itemId;
		long placedAt;
		boolean recorded;
	}
}
