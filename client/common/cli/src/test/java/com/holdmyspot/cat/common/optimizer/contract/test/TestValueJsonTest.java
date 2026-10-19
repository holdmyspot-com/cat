package com.holdmyspot.cat.common.optimizer.contract.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.contract.TestValue;
import com.holdmyspot.cat.common.optimizer.contract.TestValueJson;

import org.testng.annotations.Test;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;

/**
 * Tests {@link TestValueJson}.
 */
public final class TestValueJsonTest
{
	private final JsonMapper json = JsonMapper.builder().build();

	/**
	 * Creates the test.
	 */
	public TestValueJsonTest()
	{
	}

	/**
	 * Verifies that values that CAT writes read back unchanged, and that a call is written with its version, seed and
	 * parameters in name order.
	 */
	@Test
	public void writtenValuesReadBack()
	{
		// Requirement: R-C21
		Map<String, TestValue> values = new LinkedHashMap<>();
		values.put("notes", new TestValue.Text("release-notes.md"));
		values.put("before", new TestValue.Generated("log_lines", 1, 7419, Map.of("count", 18_000L)));
		ArrayNode array = json.createArrayNode();
		TestValueJson.write(values, array);

		requireThat(array.toString(), "json").isEqualTo("[{\"variable\":\"notes\",\"text\":\"release-notes.md\"}," +
			"{\"variable\":\"before\",\"call\":{\"generator\":\"log_lines\",\"version\":1,\"seed\":7419," +
			"\"count\":18000}}]");
		requireThat(TestValueJson.read(array), "read").isEqualTo(values);
	}

	/**
	 * Verifies that a call that a model writes receives the generator's version and a seed that no other value of
	 * the campaign holds.
	 */
	@Test
	public void modelCallReceivesVersionAndNewSeed()
	{
		// Requirement: R-C21
		JsonNode array = json.readTree("""
			[{"variable": "before", "call": {"generator": "log_lines", "count": 3}}]""");
		SplittableRandom first = new SplittableRandom(5);
		long drawn = first.nextLong();
		Set<Long> taken = new HashSet<>(Set.of(drawn));

		TestValue value = TestValueJson.readCalls(array, new SplittableRandom(5), taken).get("before");

		TestValue.Generated generated = (TestValue.Generated) value;
		requireThat(generated.generator(), "generator").isEqualTo("log_lines");
		requireThat(generated.version(), "version").isEqualTo(1);
		requireThat(generated.parameters(), "parameters").isEqualTo(Map.of("count", 3L));
		requireThat(generated.seed(), "seed").isNotEqualTo(drawn);
		requireThat(taken, "taken").isEqualTo(Set.of(drawn, generated.seed()));
	}

	/**
	 * Verifies that a model's call that states its seed or version, a parameter that is not a whole number, and a
	 * value with both text and a call are refused.
	 */
	@Test
	public void refusesMalformedModelValues()
	{
		// Requirement: R-C21
		List<String> refused = List.of(
			"[{\"variable\": \"before\", \"call\": {\"generator\": \"log_lines\", \"count\": 3, \"seed\": 9}}]",
			"[{\"variable\": \"before\", \"call\": {\"generator\": \"log_lines\", \"count\": 3, \"version\": 1}}]",
			"[{\"variable\": \"before\", \"call\": {\"generator\": \"log_lines\", \"count\": 2.5}}]",
			"[{\"variable\": \"before\", \"text\": \"x\", \"call\": {\"generator\": \"log_lines\", \"count\": 3}}]",
			"[{\"variable\": \"before\", \"call\": {\"count\": 3}}]",
			"[{\"variable\": \"before\", \"call\": {\"generator\": \"unknown_generator\"}}]");
		for (String values : refused)
		{
			try
			{
				TestValueJson.readCalls(json.readTree(values), new SplittableRandom(5), new HashSet<>());
				throw new AssertionError("Accepted " + values);
			}
			catch (IllegalArgumentException e)
			{
				requireThat(e.getMessage(), "message").contains("before");
			}
		}
	}

	/**
	 * Verifies that a call that states both its version and its seed, as CAT writes them into a draft's values file,
	 * keeps them, and that its seed becomes taken.
	 */
	@Test
	public void modelCallKeepsStatedVersionAndSeed()
	{
		JsonNode array = json.readTree("""
			[{"variable": "before", "call": {"generator": "log_lines", "version": 1, "seed": 9, "count": 3}}]""");
		Set<Long> taken = new HashSet<>();

		TestValue value = TestValueJson.readCalls(array, new SplittableRandom(5), taken).get("before");

		requireThat(value, "value").isEqualTo(new TestValue.Generated("log_lines", 1, 9, Map.of("count", 3L)));
		requireThat(taken, "taken").isEqualTo(Set.of(9L));
	}
}
