package io.github.cowwoc.cat.common.git.replay.test;

import tools.jackson.databind.JsonNode;

/**
 * Creates reviewer-authored manifest and origin-review JSON for topic-replay tests.
 */
final class TopicReplayReviewFixtures
{
	/**
	 * Prevents construction of static fixture operations.
	 */
	private TopicReplayReviewFixtures()
	{
	}

	/**
	 * Returns a manifest with one changed path and one newly added path in its second source.
	 *
	 * @return the JSON manifest
	 */
	static String manifestJson()
	{
		return """
			{"version":5,"base":"base","tip_tree":"tree","sources":[
			{"hash":"source-1","parent_tree":"tree-0","tree":"tree-1"},
			{"hash":"source-2","parent_tree":"tree-1","tree":"tree-2"}],"outputs":[],"inventory":[
			{"id":"source-1:1","source":"source-1","path":"feature.txt","before_mode":"100644",
			"before_blob":"blob-0","after_mode":"100644","after_blob":"blob-1"},
			{"id":"source-2:1","source":"source-2","path":"feature.txt","before_mode":"100644",
			"before_blob":"blob-1","after_mode":"100644","after_blob":"blob-2"},
			{"id":"source-2:2","source":"source-2","path":"helper.txt","before_mode":"000000",
			"before_blob":"0000000000000000000000000000000000000000","after_mode":"100644",
			"after_blob":"blob-3"}],"batches":[],"source_reconstructions":[],"output_witnesses":[],
			"discarded_outputs":[],"source_reviews":[],"output_plan":null}
			""";
	}

	/**
	 * Creates complete same-unit decisions for a generated origin-review scaffold.
	 *
	 * @param scaffold the immutable review scaffold
	 * @return the reviewer-authored decision JSON
	 */
	static String originReviews(JsonNode scaffold)
	{
		return originReviews(scaffold, "output_1");
	}

	/**
	 * Creates complete same-unit decisions for a generated origin-review scaffold.
	 *
	 * @param scaffold the immutable review scaffold
	 * @param destination the selected output
	 * @return the reviewer-authored decision JSON
	 */
	static String originReviews(JsonNode scaffold, String destination)
	{
		int expectedCharactersPerReview = 300;
		StringBuilder result = new StringBuilder(scaffold.size() * expectedCharactersPerReview);
		result.append('[');
		for (int index = 0; index < scaffold.size(); ++index)
		{
			if (index > 0)
				result.append(',');
			JsonNode item = scaffold.get(index);
			result.append("{\"inventory_id\":\"").append(item.path("inventory_id").stringValue()).append(
				"\",\"predecessors\":").append(predecessors(item.path("candidates"))).append(
				",\"classification\":\"same_unit\",\"affected_unit\":\"").append(item.path("path").stringValue()).
				append(" text\",\"owning_responsibility\":\"feature\",\"predecessor_relationship\":").append(
				"\"corrects_predecessor\",\"destination\":\"").append(destination).append(
				"\",\"rejected_destinations\":[],\"evidence\":").
				append("\"The feature output contains reviewed placement ").append(item.path("inventory_id").stringValue()).
				append(" for ").append(item.path("path").stringValue()).append(".\"}");
		}
		return result.append(']').toString();
	}

	/**
	 * Returns the predecessor source hashes from one scaffold candidate list.
	 *
	 * @param candidates the scaffold candidates
	 * @return the JSON source-hash array
	 */
	static String predecessors(JsonNode candidates)
	{
		StringBuilder result = new StringBuilder(candidates.size() * 42);
		result.append('[');
		for (int index = 0; index < candidates.size(); ++index)
		{
			if (index > 0)
				result.append(',');
			result.append('"').append(candidates.get(index).path("source").stringValue()).append('"');
		}
		return result.append(']').toString();
	}
}
