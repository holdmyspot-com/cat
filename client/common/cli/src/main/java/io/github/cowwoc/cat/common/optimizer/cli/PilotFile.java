package io.github.cowwoc.cat.common.optimizer.cli;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.execution.PilotResult;
import io.github.cowwoc.cat.common.optimizer.execution.PilotTrial;
import io.github.cowwoc.cat.common.optimizer.inventory.Sha256;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Persists the guard pilot of the latest proposal in the campaign's {@code held_out} directory, so that a resumed
 * campaign presents the same results and a revision reruns only the prompt tests that changed.
 * <p>
 * The file holds held-out content: the values of the development test cases that the trials ran and the judges'
 * rationales.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 */
final class PilotFile
{
	/**
	 * The file's name.
	 */
	static final String NAME = "pilot.json";
	/**
	 * The version of the file's layout.
	 */
	private static final int FORMAT_VERSION = 2;
	private final Path file;
	private final JsonMapper json;

	/**
	 * Creates a pilot file.
	 *
	 * @param heldOut the campaign's {@code held_out} directory
	 * @param json the operation-owned JSON mapper
	 * @throws NullPointerException if any of the arguments are null
	 */
	PilotFile(Path heldOut, JsonMapper json)
	{
		requireThat(heldOut, "heldOut").isNotNull();
		requireThat(json, "json").isNotNull();
		this.file = heldOut.resolve(NAME);
		this.json = json;
	}

	/**
	 * Replaces the stored pilots.
	 *
	 * @param pilots the pilot of each prompt test that has one, by prompt test
	 * @throws NullPointerException if {@code pilots} is null
	 * @throws IOException if the file cannot be written
	 */
	void write(Map<String, PilotResult> pilots) throws IOException
	{
		requireThat(pilots, "pilots").isNotNull();
		ObjectNode root = json.createObjectNode();
		root.put("format_version", FORMAT_VERSION);
		ArrayNode tests = root.putArray("tests");
		for (PilotResult pilot : pilots.values())
		{
			ObjectNode test = tests.addObject();
			test.put("test", pilot.testId());
			test.put("definition", pilot.definition().hex());
			test.put("cell", pilot.cellId());
			test.put("control", pilot.control().hex());
			ArrayNode failedRuns = test.putArray("failed_runs");
			pilot.failedRuns().forEach(failedRuns::add);
			if (pilot.stopReason() == null)
				test.putNull("stop_reason");
			else
				test.put("stop_reason", pilot.stopReason());
			ArrayNode trials = test.putArray("trials");
			for (PilotTrial trial : pilot.trials())
			{
				ObjectNode node = trials.addObject();
				ObjectNode values = node.putObject("values");
				trial.values().forEach(values::put);
				node.put("expected_outcome", trial.expectedOutcome());
				ObjectNode assertions = node.putObject("assertions");
				trial.assertions().forEach(assertions::put);
				node.put("record", trial.record());
			}
		}
		Files.createDirectories(file.getParent());
		Path temporary = file.resolveSibling(NAME + ".tmp");
		Files.writeString(temporary, json.writerWithDefaultPrettyPrinter().writeValueAsString(root),
			StandardCharsets.UTF_8);
		Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}

	/**
	 * Reads the stored pilots.
	 *
	 * @return the pilot of each prompt test, by prompt test; empty if no pilot is stored
	 * @throws IOException if the file cannot be read or is not a pilot file of a readable version
	 */
	Map<String, PilotResult> read() throws IOException
	{
		Map<String, PilotResult> pilots = new LinkedHashMap<>();
		if (!Files.exists(file))
			return pilots;
		JsonNode root = json.readTree(Files.readString(file, StandardCharsets.UTF_8));
		int version = root.path("format_version").asInt();
		if (version != FORMAT_VERSION)
		{
			throw new IOException(file + " is a version " + version + " pilot file, but this version of CAT reads " +
				"version " + FORMAT_VERSION + ". Run `start` again to rerun the pilot.");
		}
		try
		{
			for (JsonNode test : root.path("tests"))
			{
				PilotResult pilot = pilot(test);
				pilots.put(pilot.testId(), pilot);
			}
		}
		catch (IllegalArgumentException e)
		{
			throw new IOException(file + " is not a valid version " + version + " pilot file: " + e.getMessage(), e);
		}
		return pilots;
	}

	/**
	 * Reads the pilot of one prompt test.
	 *
	 * @param test the stored pilot
	 * @return the pilot
	 * @throws IllegalArgumentException if a property is missing or invalid
	 */
	private static PilotResult pilot(JsonNode test)
	{
		List<PilotTrial> trials = new ArrayList<>();
		for (JsonNode node : test.path("trials"))
		{
			Map<String, String> values = new LinkedHashMap<>();
			for (Map.Entry<String, JsonNode> value : node.path("values").properties())
				values.put(value.getKey(), text(value.getValue()));
			Map<String, Boolean> assertions = new LinkedHashMap<>();
			for (Map.Entry<String, JsonNode> assertion : node.path("assertions").properties())
			{
				if (!assertion.getValue().isBoolean())
					throw new IllegalArgumentException("A trial's assertions must be booleans: " + node);
				assertions.put(assertion.getKey(), assertion.getValue().asBoolean());
			}
			trials.add(new PilotTrial(values, text(node.path("expected_outcome")), assertions,
				text(node.path("record"))));
		}
		String stopReason = null;
		if (!test.path("stop_reason").isNull())
			stopReason = text(test.path("stop_reason"));
		if (!test.path("failed_runs").isArray())
			throw new IllegalArgumentException("\"failed_runs\" must be an array: " + test);
		List<String> failedRuns = new ArrayList<>();
		for (JsonNode reason : test.get("failed_runs"))
			failedRuns.add(text(reason));
		return new PilotResult(text(test.path("test")), new Sha256(text(test.path("definition"))),
			text(test.path("cell")), new Sha256(text(test.path("control"))), trials, failedRuns, stopReason);
	}

	/**
	 * Reads a string property.
	 *
	 * @param node the property's value
	 * @return the string
	 * @throws IllegalArgumentException if the value is not a string
	 */
	private static String text(JsonNode node)
	{
		if (!node.isString())
			throw new IllegalArgumentException("Expected a string, not " + node);
		return node.asString();
	}
}
