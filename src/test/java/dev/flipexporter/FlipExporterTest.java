package dev.flipexporter;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

/** Dev launcher: `./gradlew run` starts RuneLite (developer mode) with this plugin loaded. */
public class FlipExporterTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(FlipExporterPlugin.class);
		RuneLite.main(args);
	}
}
