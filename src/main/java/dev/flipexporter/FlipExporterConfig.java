package dev.flipexporter;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("flipexporter")
public interface FlipExporterConfig extends Config
{
	@ConfigItem(
			keyName = "exportIntervalTicks",
			name = "Snapshot interval (ticks)",
			description = "Game ticks between snapshot writes (1 tick = 0.6s). 5 = every 3s is plenty for most readers. The completed-trade history is written immediately on every fill regardless of this."
	)
	default int exportIntervalTicks()
	{
		return 5;
	}

	@ConfigItem(
			keyName = "exportBank",
			name = "Export bank contents",
			description = "Include your bank in the snapshot (only refreshes while the bank is open). Off by default — active flipping stock usually lives in your bag, not the bank."
	)
	default boolean exportBank()
	{
		return false;
	}

	@ConfigItem(
			keyName = "maxHistoryTrades",
			name = "Trade history size",
			description = "How many completed trades to retain in history.json (oldest dropped past this). Enough for cost-basis and audit."
	)
	default int maxHistoryTrades()
	{
		return 20000;
	}
}
