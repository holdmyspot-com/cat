package com.holdmyspot.cat.common.git.replay.test;

import com.holdmyspot.cat.common.git.replay.BatchManifest;
import com.holdmyspot.cat.common.git.replay.InventoryItem;
import com.holdmyspot.cat.common.git.replay.Output;
import com.holdmyspot.cat.common.git.replay.SourceSnapshot;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import tools.jackson.databind.json.JsonMapper;

/**
 * Supplies captured path facts for origin-review policy tests without creating Git history.
 */
final class OriginReviewFixtures
{
	/**
	 * Prevents construction.
	 */
	private OriginReviewFixtures()
	{
	}

	/**
	 * Writes captured additions followed by corrections or reversions for the given paths.
	 *
	 * @param json the JSON mapper
	 * @param directory the owned data directory
	 * @param reverted whether the second source removes the paths
	 * @param paths the paths in captured inventory order
	 * @return the manifest file
	 * @throws IOException if the manifest cannot be written
	 */
	static Path writeManifest(JsonMapper json, Path directory, boolean reverted, String... paths) throws IOException
	{
		String absent = "0000000000000000000000000000000000000000";
		List<InventoryItem> inventory = new ArrayList<>();
		for (int index = 0; index < paths.length; ++index)
		{
			inventory.add(new InventoryItem("source-1:" + (index + 1), "source-1", paths[index], "000000", absent,
				"100644", "original-" + index));
		}
		String afterMode = "100644";
		String afterBlobPrefix = "corrected-";
		if (reverted)
		{
			afterMode = "000000";
			afterBlobPrefix = absent;
		}
		for (int index = 0; index < paths.length; ++index)
		{
			String afterBlob = afterBlobPrefix;
			if (!reverted)
				afterBlob = afterBlobPrefix + index;
			inventory.add(new InventoryItem("source-2:" + (index + 1), "source-2", paths[index], "100644",
				"original-" + index, afterMode, afterBlob));
		}
		BatchManifest manifest = new BatchManifest(5, "base", "tree-2", List.of(
			new SourceSnapshot("source-1", "tree-0", "tree-1"),
			new SourceSnapshot("source-2", "tree-1", "tree-2")), List.of(
			new Output("output_1", "source-1", "feature"),
			new Output("output_2", "source-2", "correction")), inventory,
			List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null);
		Path file = directory.resolve("manifest.json");
		json.writeValue(file.toFile(), manifest);
		return file;
	}
}
