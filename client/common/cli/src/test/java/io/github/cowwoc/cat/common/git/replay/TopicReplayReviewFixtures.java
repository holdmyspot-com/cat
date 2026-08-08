package io.github.cowwoc.cat.common.git.replay;

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
			{"version":5,"base":"base","tipTree":"tree","sources":[
			{"hash":"source-1","parentTree":"tree-0","tree":"tree-1"},
			{"hash":"source-2","parentTree":"tree-1","tree":"tree-2"}],"outputs":[],"inventory":[
			{"id":"source-1:1","source":"source-1","path":"feature.txt","beforeMode":"100644",
			"beforeBlob":"blob-0","afterMode":"100644","afterBlob":"blob-1"},
			{"id":"source-2:1","source":"source-2","path":"feature.txt","beforeMode":"100644",
			"beforeBlob":"blob-1","afterMode":"100644","afterBlob":"blob-2"},
			{"id":"source-2:2","source":"source-2","path":"helper.txt","beforeMode":"000000",
			"beforeBlob":"0000000000000000000000000000000000000000","afterMode":"100644",
			"afterBlob":"blob-3"}],"batches":[],"sourceReconstructions":[],"outputWitnesses":[],
			"discardedOutputs":[],"sourceReviews":[],"outputPlan":null}
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
		return originReviews(scaffold, "output-1");
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
			result.append("{\"inventoryId\":\"").append(item.path("inventoryId").stringValue()).append(
				"\",\"predecessors\":").append(predecessors(item.path("candidates"))).append(
				",\"classification\":\"same-unit\",\"affectedUnit\":\"").append(item.path("path").stringValue()).
				append(" text\",\"owningResponsibility\":\"feature\",\"predecessorRelationship\":").append(
				"\"corrects-predecessor\",\"destination\":\"").append(destination).append(
				"\",\"rejectedDestinations\":[],\"evidence\":").
				append("\"The feature output contains reviewed placement ").append(item.path("inventoryId").stringValue()).
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
