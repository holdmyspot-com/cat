package com.holdmyspot.cat.common.optimizer.contract;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.StreamWriteFeature;
import tools.jackson.core.TokenStreamLocation;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Reads and writes a prompt test's values file, {@code values.json}, which holds its test cases' values and expected
 * outcomes:
 * <pre>{@code
 * {
 *   "format_version": 1,
 *   "test_cases": [
 *     {
 *       "values": {
 *         "notes": "release-notes.md",
 *         "before": {"generator": "log_lines", "version": 1, "seed": 7419, "count": 18000}
 *       },
 *       "expected_outcome": "2025-03-04"
 *     }
 *   ]
 * }
 * }</pre>
 * A value is a string, or a generator call with its {@code generator}, {@code version} and {@code seed} and the
 * generator's parameters. In a draft directory, a call may leave out both its {@code version} and its {@code seed},
 * which CAT adds when it validates the proposal. Unknown fields are refused. Reading streams the file, so it holds at
 * most one test case at a time as a tree.
 */
public final class ValuesFile
{
	/**
	 * The values file's name in a prompt test directory.
	 */
	public static final String NAME = "values.json";
	/**
	 * The format version that this class reads and writes.
	 */
	public static final int FORMAT_VERSION = 1;
	private static final String FORMAT_VERSION_FIELD = "format_version";
	private static final String TEST_CASES = "test_cases";
	private static final String VALUES = "values";
	private static final String GENERATOR = "generator";
	private static final String VERSION = "version";
	private static final String SEED = "seed";

	/**
	 * Prevents instantiation.
	 */
	private ValuesFile()
	{
	}

	/**
	 * Reads a values file.
	 *
	 * @param json     the JSON mapper
	 * @param in       the file's content
	 * @param location the file's location, which starts each problem's message
	 * @return the test cases, in file order
	 * @throws NullPointerException    if any of the arguments are null
	 * @throws IOException             if {@code in} cannot be read
	 * @throws ScenarioFormatException if the content is not a values file of {@link #FORMAT_VERSION}
	 */
	public static List<TestCase> read(JsonMapper json, Reader in, String location)
		throws IOException, ScenarioFormatException
	{
		return read(json, in, location, false, draft ->
		{
			ObjectNode testCase = draft.testCase();
			try
			{
				return new TestCase(TestValueJson.read(testCase.get(VALUES)),
					testCase.get(TestCase.EXPECTED_OUTCOME).asString());
			}
			catch (IllegalArgumentException e)
			{
				throw new ScenarioFormatException(location + ":" + draft.line() + ": " + e.getMessage());
			}
		});
	}

	/**
	 * Reads the values file of a prompt test in a draft directory, whose generator calls may leave out both their
	 * version and their seed.
	 *
	 * @param json     the JSON mapper
	 * @param in       the file's content
	 * @param location the file's location, which starts each problem's message
	 * @return the test cases, in file order
	 * @throws NullPointerException    if any of the arguments are null
	 * @throws IOException             if {@code in} cannot be read
	 * @throws ScenarioFormatException if the content is not a values file of {@link #FORMAT_VERSION}, apart from the
	 *                                 calls that leave out both their version and their seed
	 */
	public static List<DraftTestCase> readDraft(JsonMapper json, Reader in, String location)
		throws IOException, ScenarioFormatException
	{
		return read(json, in, location, true, draft -> draft);
	}

	/**
	 * Reads a values file, converting each test case once it is read.
	 *
	 * @param <T>       the type of a converted test case
	 * @param json      the JSON mapper
	 * @param in        the file's content
	 * @param location  the file's location, which starts each problem's message
	 * @param draft     {@code true} if a generator call may leave out both its version and its seed
	 * @param converter converts each test case
	 * @return the converted test cases, in file order
	 * @throws NullPointerException    if any of the arguments are null
	 * @throws IOException             if {@code in} cannot be read
	 * @throws ScenarioFormatException if the content is malformed or {@code converter} refuses a test case
	 */
	private static <T> List<T> read(JsonMapper json, Reader in, String location, boolean draft,
		Converter<T> converter) throws IOException, ScenarioFormatException
	{
		requireThat(json, "json").isNotNull();
		requireThat(in, "in").isNotNull();
		requireThat(location, "location").isNotNull();
		try (JsonParser parser = json.reader().with(StreamReadFeature.STRICT_DUPLICATE_DETECTION).
			without(StreamReadFeature.AUTO_CLOSE_SOURCE).createParser(in))
		{
			List<T> testCases = readRoot(parser, new Reading(json, location, draft), converter);
			if (parser.nextToken() != null)
				throw problem(parser, location, "content follows the top-level object");
			return testCases;
		}
		catch (JacksonException e)
		{
			if (e.getCause() instanceof IOException cause)
				throw cause;
			throw new ScenarioFormatException(where(location, e.getLocation()) + e.getOriginalMessage());
		}
	}

	/**
	 * A test case of a values file in a draft directory.
	 *
	 * @param line     the line on which the test case starts
	 * @param testCase the test case as a proposal writes it: its {@code values}, an array as {@link TestValueJson}
	 *                 writes it, and its {@code expected_outcome}
	 */
	public record DraftTestCase(int line, ObjectNode testCase)
	{
		/**
		 * Creates a test case.
		 *
		 * @param line     the line on which the test case starts
		 * @param testCase the test case as a proposal writes it
		 * @throws NullPointerException     if {@code testCase} is null
		 * @throws IllegalArgumentException if {@code line} is negative
		 */
		public DraftTestCase
		{
			requireThat(line, "line").isNotNegative();
			requireThat(testCase, "testCase").isNotNull();
		}
	}

	/**
	 * Converts a test case once it is read.
	 *
	 * @param <T> the type of a converted test case
	 */
	@FunctionalInterface
	private interface Converter<T>
	{
		/**
		 * Converts a test case.
		 *
		 * @param testCase the test case
		 * @return the converted test case
		 * @throws ScenarioFormatException if the test case is refused
		 */
		T convert(DraftTestCase testCase) throws ScenarioFormatException;
	}

	/**
	 * The state of one reading.
	 *
	 * @param json     the JSON mapper, which creates the test cases' trees
	 * @param location the file's location
	 * @param draft    {@code true} if a generator call may leave out both its version and its seed
	 */
	private record Reading(JsonMapper json, String location, boolean draft)
	{
	}

	/**
	 * Reads the top-level object.
	 *
	 * @param <T>       the type of a converted test case
	 * @param parser    the parser, before the object
	 * @param reading   the reading
	 * @param converter converts each test case
	 * @return the converted test cases
	 * @throws ScenarioFormatException if the object is not a values file of {@link #FORMAT_VERSION}
	 */
	private static <T> List<T> readRoot(JsonParser parser, Reading reading, Converter<T> converter)
		throws ScenarioFormatException
	{
		String location = reading.location();
		parser.nextToken();
		requireToken(parser, JsonToken.START_OBJECT, location, "the file must hold one JSON object");
		Integer formatVersion = null;
		boolean hasTestCases = false;
		List<T> testCases = List.of();
		while (parser.nextToken() == JsonToken.PROPERTY_NAME)
		{
			String name = parser.currentName();
			parser.nextToken();
			switch (name)
			{
				case FORMAT_VERSION_FIELD ->
				{
					if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT || !parser.isExpectedNumberIntToken())
						throw problem(parser, location, FORMAT_VERSION_FIELD + " must be an integer");
					formatVersion = parser.getIntValue();
				}
				case TEST_CASES ->
				{
					testCases = readTestCases(parser, reading, converter);
					hasTestCases = true;
				}
				default -> throw problem(parser, location, "the field '" + name + "' is unknown. Fields: " +
					FORMAT_VERSION_FIELD + ", " + TEST_CASES);
			}
		}
		if (formatVersion == null || formatVersion != FORMAT_VERSION)
		{
			throw problem(parser, location, FORMAT_VERSION_FIELD + " must be " + FORMAT_VERSION + ", but it is " +
				formatVersion);
		}
		if (!hasTestCases)
			throw problem(parser, location, "the field " + TEST_CASES + " is missing");
		return testCases;
	}

	/**
	 * Reads the array of test cases.
	 *
	 * @param <T>       the type of a converted test case
	 * @param parser    the parser, at the array's start
	 * @param reading   the reading
	 * @param converter converts each test case
	 * @return the converted test cases
	 * @throws ScenarioFormatException if a test case is malformed or {@code converter} refuses it
	 */
	private static <T> List<T> readTestCases(JsonParser parser, Reading reading, Converter<T> converter)
		throws ScenarioFormatException
	{
		String location = reading.location();
		requireToken(parser, JsonToken.START_ARRAY, location, TEST_CASES + " must be an array");
		List<T> testCases = new ArrayList<>();
		while (parser.nextToken() != JsonToken.END_ARRAY)
		{
			TokenStreamLocation start = parser.currentLocation();
			requireToken(parser, JsonToken.START_OBJECT, location, "a test case must be an object");
			ArrayNode values = null;
			String expectedOutcome = null;
			while (parser.nextToken() == JsonToken.PROPERTY_NAME)
			{
				String name = parser.currentName();
				parser.nextToken();
				switch (name)
				{
					case VALUES -> values = readValues(parser, reading);
					case TestCase.EXPECTED_OUTCOME ->
					{
						requireToken(parser, JsonToken.VALUE_STRING, location, TestCase.EXPECTED_OUTCOME +
							" must be a string");
						expectedOutcome = parser.getString();
					}
					default -> throw problem(parser, location, "the test case field '" + name + "' is unknown. " +
						"Fields: " + VALUES + ", " + TestCase.EXPECTED_OUTCOME);
				}
			}
			if (values == null)
				throw new ScenarioFormatException(where(location, start) + "the test case has no " + VALUES);
			if (expectedOutcome == null)
			{
				throw new ScenarioFormatException(where(location, start) + "the test case has no " +
					TestCase.EXPECTED_OUTCOME);
			}
			ObjectNode testCase = reading.json().createObjectNode();
			testCase.set(VALUES, values);
			testCase.put(TestCase.EXPECTED_OUTCOME, expectedOutcome);
			testCases.add(converter.convert(new DraftTestCase(Math.max(start.getLineNr(), 0), testCase)));
		}
		return testCases;
	}

	/**
	 * Reads a test case's values.
	 *
	 * @param parser  the parser, at the object's start
	 * @param reading the reading
	 * @return the values, one object per variable, in file order, as {@link TestValueJson} writes them
	 * @throws ScenarioFormatException if a value is malformed
	 */
	private static ArrayNode readValues(JsonParser parser, Reading reading) throws ScenarioFormatException
	{
		String location = reading.location();
		requireToken(parser, JsonToken.START_OBJECT, location, VALUES + " must be an object");
		ArrayNode values = reading.json().createArrayNode();
		while (parser.nextToken() == JsonToken.PROPERTY_NAME)
		{
			String name = parser.currentName();
			JsonToken token = parser.nextToken();
			ObjectNode value = values.addObject();
			value.put("variable", name);
			if (token == JsonToken.VALUE_STRING)
				value.put("text", parser.getString());
			else if (token == JsonToken.START_OBJECT)
				value.set("call", readCall(parser, reading));
			else
				throw problem(parser, location, "the value of " + name + " must be a string or a generator call");
		}
		return values;
	}

	/**
	 * Reads a generator call.
	 *
	 * @param parser  the parser, at the object's start
	 * @param reading the reading
	 * @return the call, with its properties in file order
	 * @throws ScenarioFormatException if the call is malformed
	 */
	private static ObjectNode readCall(JsonParser parser, Reading reading) throws ScenarioFormatException
	{
		String location = reading.location();
		TokenStreamLocation start = parser.currentLocation();
		ObjectNode call = reading.json().createObjectNode();
		boolean hasGenerator = false;
		Long version = null;
		boolean hasSeed = false;
		while (parser.nextToken() == JsonToken.PROPERTY_NAME)
		{
			String name = parser.currentName();
			parser.nextToken();
			if (name.equals(GENERATOR))
			{
				requireToken(parser, JsonToken.VALUE_STRING, location, GENERATOR + " must be a string");
				call.put(GENERATOR, parser.getString());
				hasGenerator = true;
				continue;
			}
			if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT || !parser.isExpectedNumberIntToken())
				throw problem(parser, location, name + " must be an integer");
			long number;
			try
			{
				number = parser.getLongValue();
			}
			catch (JacksonException _)
			{
				throw problem(parser, location, name + " must be an integer between " + Long.MIN_VALUE + " and " +
					Long.MAX_VALUE);
			}
			call.put(name, number);
			if (name.equals(VERSION))
				version = number;
			else if (name.equals(SEED))
				hasSeed = true;
		}
		boolean seeded = version != null && hasSeed;
		boolean unseeded = version == null && !hasSeed;
		if (!hasGenerator || !seeded && !(reading.draft() && unseeded))
		{
			String message = "a generator call must have " + GENERATOR + ", " + VERSION + " and " + SEED;
			if (reading.draft())
			{
				message = "a generator call must have " + GENERATOR + ", and either both " + VERSION + " and " + SEED +
					" or neither";
			}
			throw new ScenarioFormatException(where(location, start) + message);
		}
		if (version != null && (version < 1 || version > Integer.MAX_VALUE))
		{
			throw new ScenarioFormatException(where(location, start) + call.get(GENERATOR).asString() +
				" has no version " + version);
		}
		return call;
	}

	/**
	 * Rejects a token other than the expected one.
	 *
	 * @param parser   the parser
	 * @param expected the expected token
	 * @param location the file's location
	 * @param problem  the problem to report
	 * @throws ScenarioFormatException if the parser's current token is not {@code expected}
	 */
	private static void requireToken(JsonParser parser, JsonToken expected, String location, String problem)
		throws ScenarioFormatException
	{
		if (parser.currentToken() != expected)
			throw problem(parser, location, problem);
	}

	/**
	 * Returns a problem at the parser's location.
	 *
	 * @param parser   the parser
	 * @param location the file's location
	 * @param problem  the problem
	 * @return the exception
	 */
	private static ScenarioFormatException problem(JsonParser parser, String location, String problem)
	{
		return new ScenarioFormatException(where(location, parser.currentLocation()) + problem);
	}

	/**
	 * Returns the prefix that names a location in the file.
	 *
	 * @param location the file's location
	 * @param position the position in the file, or {@code null} if unknown
	 * @return the prefix
	 */
	private static String where(String location, TokenStreamLocation position)
	{
		if (position == null || position.getLineNr() < 1)
			return location + ": ";
		return location + ":" + position.getLineNr() + ":" + position.getColumnNr() + ": ";
	}

	/**
	 * Writes a values file.
	 *
	 * @param json      the JSON mapper
	 * @param testCases the test cases, in file order
	 * @param out       the writer that receives the file's content
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException          if {@code out} fails
	 */
	public static void write(JsonMapper json, List<TestCase> testCases, Writer out) throws IOException
	{
		requireThat(json, "json").isNotNull();
		requireThat(testCases, "testCases").isNotNull();
		requireThat(out, "out").isNotNull();
		try (JsonGenerator generator = json.writerWithDefaultPrettyPrinter().
			without(StreamWriteFeature.AUTO_CLOSE_TARGET).createGenerator(out))
		{
			generator.writeStartObject();
			generator.writeNumberProperty(FORMAT_VERSION_FIELD, FORMAT_VERSION);
			generator.writeArrayPropertyStart(TEST_CASES);
			for (TestCase testCase : testCases)
			{
				generator.writeStartObject();
				generator.writeObjectPropertyStart(VALUES);
				for (Map.Entry<String, TestValue> entry : testCase.values().entrySet())
				{
					generator.writeName(entry.getKey());
					switch (entry.getValue())
					{
						case TestValue.Text text -> generator.writeString(text.text());
						case TestValue.Generated generated ->
						{
							generator.writeStartObject();
							generator.writeStringProperty(GENERATOR, generated.generator());
							generator.writeNumberProperty(VERSION, generated.version());
							generator.writeNumberProperty(SEED, generated.seed());
							for (Map.Entry<String, Long> parameter : new TreeMap<>(generated.parameters()).entrySet())
								generator.writeNumberProperty(parameter.getKey(), parameter.getValue().longValue());
							generator.writeEndObject();
						}
					}
				}
				generator.writeEndObject();
				generator.writeStringProperty(TestCase.EXPECTED_OUTCOME, testCase.expectedOutcome());
				generator.writeEndObject();
			}
			generator.writeEndArray();
			generator.writeEndObject();
		}
		catch (JacksonException e)
		{
			if (e.getCause() instanceof IOException cause)
				throw cause;
			throw e;
		}
		out.write('\n');
	}
}
