package io.github.cowwoc.cat.common.optimizer.contract.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.contract.ScenarioFormatException;
import io.github.cowwoc.cat.common.optimizer.contract.TestCase;
import io.github.cowwoc.cat.common.optimizer.contract.TestValue;
import io.github.cowwoc.cat.common.optimizer.contract.ValuesFile;

import org.testng.annotations.Test;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.json.JsonMapper;

/**
 * Tests {@link ValuesFile}.
 */
public final class ValuesFileTest
{
	private final JsonMapper json = JsonMapper.builder().build();

	/**
	 * Creates the test fixture.
	 */
	public ValuesFileTest()
	{
	}

	/**
	 * Reads a values file.
	 *
	 * @param content the file's content
	 * @return the test cases
	 * @throws IOException             if the content cannot be read
	 * @throws ScenarioFormatException if the content is refused
	 */
	private List<TestCase> read(String content) throws IOException, ScenarioFormatException
	{
		return ValuesFile.read(json, new StringReader(content), "tests/case/values.json");
	}

	/**
	 * Returns the problem that reading reports.
	 *
	 * @param content a values file that is refused
	 * @return the exception's message
	 * @throws IOException if the content cannot be read
	 */
	private String refusal(String content) throws IOException
	{
		try
		{
			read(content);
		}
		catch (ScenarioFormatException e)
		{
			return e.getMessage();
		}
		throw new AssertionError("The values file was accepted:\n" + content);
	}

	/**
	 * Verifies that written test cases, with text and generated values, read back equal and in order.
	 *
	 * @throws IOException             if the content cannot be written or read
	 * @throws ScenarioFormatException if the written content is refused
	 */
	@Test
	public void roundTrip() throws IOException, ScenarioFormatException
	{
		// Requirement: R-C22
		Map<String, TestValue> first = new LinkedHashMap<>();
		first.put("notes", new TestValue.Text("release-notes.md"));
		first.put("before", new TestValue.Generated("log_lines", 1, -4_611_686_018_427_387_904L,
			Map.of("count", 18_000L)));
		Map<String, TestValue> second = new LinkedHashMap<>();
		second.put("notes", new TestValue.Text("line one\nline \"two\" \\ | é"));
		second.put("before", new TestValue.Text(""));
		List<TestCase> testCases = List.of(new TestCase(first, "2025-03-04"), new TestCase(second, "March 5"));

		StringWriter out = new StringWriter();
		ValuesFile.write(json, testCases, out);
		requireThat(out.toString(), "out").startsWith("{").contains("\"format_version\" : 1").endsWith("\n");
		List<TestCase> read = read(out.toString());
		requireThat(read, "read").isEqualTo(testCases);
		requireThat(List.copyOf(read.get(0).values().keySet()), "order").isEqualTo(List.of("notes", "before"));
	}

	/**
	 * Verifies that an unknown field is refused at every level.
	 *
	 * @throws IOException if the content cannot be read
	 */
	@Test
	public void refusesUnknownFields() throws IOException
	{
		// Requirement: R-C22
		requireThat(refusal("""
			{"format_version": 1, "test_cases": [], "comment": "x"}"""), "topLevel").contains("comment");
		requireThat(refusal("""
			{"format_version": 1, "test_cases": [{"values": {"a": "x"}, "expected_outcome": "y", "weight": 2}]}"""),
			"testCase").contains("weight");
		requireThat(refusal("""
			{"format_version": 1, "test_cases": [{"values": {"a": {"generator": "log_lines", "version": 1,
			"seed": 3, "count": 5, "width": 80}}, "expected_outcome": "y"}]}"""), "generated").contains("width");
	}

	/**
	 * Verifies that a missing or other format version is refused.
	 *
	 * @throws IOException if the content cannot be read
	 */
	@Test
	public void refusesFormatVersion() throws IOException
	{
		// Requirement: R-C22
		requireThat(refusal("""
			{"test_cases": []}"""), "missing").contains("format_version");
		requireThat(refusal("""
			{"format_version": 2, "test_cases": []}"""), "other").contains("format_version");
	}

	/**
	 * Verifies that malformed test cases and values are refused with the file's location.
	 *
	 * @throws IOException if the content cannot be read
	 */
	@Test
	public void refusesMalformedTestCases() throws IOException
	{
		// Requirement: R-C22
		List<String> malformed = List.of(
			"""
				{"format_version": 1}""",
			"""
				{"format_version": 1, "test_cases": [{"values": {"a": "x"}}]}""",
			"""
				{"format_version": 1, "test_cases": [{"values": {"a": 5}, "expected_outcome": "y"}]}""",
			"""
				{"format_version": 1, "test_cases": [{"values": {"a": "x", "a": "z"}, "expected_outcome": "y"}]}""",
			"""
				{"format_version": 1, "test_cases": [{"values": {"a": {"generator": "lorem", "version": 1,
				"seed": 3}}, "expected_outcome": "y"}]}""",
			"""
				{"format_version": 1, "test_cases": [{"values": {"a": {"generator": "log_lines",
				"count": 5}}, "expected_outcome": "y"}]}""",
			"""
				{"format_version": 1, "test_cases": [{"values": {"a": "x"}, "expected_outcome": "y"}]} []""",
			"""
				{"format_version": 1, "test_cases": [""");
		for (String content : malformed)
			requireThat(refusal(content), "refusal(" + malformed.indexOf(content) + ")").startsWith("tests/case/values.json");
	}

	/**
	 * Verifies that a draft's values file keeps a generator call without its version and seed, a call with both, and
	 * the line of each test case, and still refuses a call that states only one of them.
	 *
	 * @throws IOException             if the content cannot be read
	 * @throws ScenarioFormatException if the content is refused
	 */
	@Test
	public void readsDraftCallsWithoutSeeds() throws IOException, ScenarioFormatException
	{
		String content = """
			{
			  "format_version": 1,
			  "test_cases": [
			    {"values": {"notes": "a.md"}, "expected_outcome": "one"},
			    {
			      "values": {"before": {"generator": "log_lines", "count": 3}},
			      "expected_outcome": "two"
			    },
			    {"values": {"before": {"generator": "log_lines", "version": 1, "seed": 9, "count": 3}},
			      "expected_outcome": "three"}
			  ]
			}
			""";
		List<ValuesFile.DraftTestCase> read = ValuesFile.readDraft(json, new StringReader(content),
			"tests/case/values.json");

		requireThat(read.stream().map(ValuesFile.DraftTestCase::line).toList(), "lines").
			isEqualTo(List.of(4, 5, 9));
		requireThat(read.get(0).testCase().toString(), "first").isEqualTo(
			"{\"values\":[{\"variable\":\"notes\",\"text\":\"a.md\"}],\"expected_outcome\":\"one\"}");
		requireThat(read.get(1).testCase().toString(), "second").isEqualTo("{\"values\":[{\"variable\":\"before\"," +
			"\"call\":{\"generator\":\"log_lines\",\"count\":3}}],\"expected_outcome\":\"two\"}");
		requireThat(read.get(2).testCase().toString(), "third").isEqualTo("{\"values\":[{\"variable\":\"before\"," +
			"\"call\":{\"generator\":\"log_lines\",\"version\":1,\"seed\":9,\"count\":3}}]," +
			"\"expected_outcome\":\"three\"}");

		try
		{
			ValuesFile.readDraft(json, new StringReader("""
				{"format_version": 1, "test_cases": [
				{"values": {"a": {"generator": "log_lines", "seed": 3, "count": 5}}, "expected_outcome": "y"}]}"""),
				"tests/case/values.json");
			throw new AssertionError("A call with only a seed was accepted");
		}
		catch (ScenarioFormatException e)
		{
			requireThat(e.getMessage(), "message").startsWith("tests/case/values.json:2:");
		}
	}
}
