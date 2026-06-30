package dev.flipexporter;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class NotedResolutionTest
{
	@Test
	public void unnotedItemUnchanged()
	{
		// unnoted item: getNote() == -1 → id stays as-is (linkedNoteId is its *noted* form)
		assertEquals(2114, FlipExporterPlugin.canonicalId(2114, -1, 2115));
	}

	@Test
	public void notedItemFoldsToTradeableId()
	{
		// noted item (note template 799) → its linked unnoted id (the tradeable one the flipper uses)
		assertEquals(2114, FlipExporterPlugin.canonicalId(2115, 799, 2114));
		assertEquals(567, FlipExporterPlugin.canonicalId(568, 799, 567));     // Unpowered orb
		assertEquals(31970, FlipExporterPlugin.canonicalId(31971, 799, 31970)); // Teak repair kit
	}

	@Test
	public void notedWithoutValidLinkFallsBackToRawId()
	{
		assertEquals(2115, FlipExporterPlugin.canonicalId(2115, 799, -1));
		assertEquals(2115, FlipExporterPlugin.canonicalId(2115, 799, 0));
	}
}
