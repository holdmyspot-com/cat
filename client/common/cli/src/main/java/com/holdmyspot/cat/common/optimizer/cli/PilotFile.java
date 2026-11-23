package com.holdmyspot.cat.common.optimizer.cli;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.FileReplacer;
import com.holdmyspot.cat.common.optimizer.campaign.Harness;
import com.holdmyspot.cat.common.optimizer.execution.GuardPilot;
import com.holdmyspot.cat.common.optimizer.execution.PilotResult;
import com.holdmyspot.cat.common.optimizer.execution.PilotTrial;
import com.holdmyspot.cat.common.optimizer.execution.PilotTrialRecovery;
import com.holdmyspot.cat.common.optimizer.execution.PilotTrialRecovery.Discard;
import com.holdmyspot.cat.common.optimizer.inventory.Sha256;
import com.holdmyspot.cat.common.optimizer.port.TurnObserver;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Persists the guard pilot of the latest proposal in the campaign's {@code held_out} directory, so that a resumed
 * campaign presents the same results and a revision reruns only the controls whose conditions changed.
 * <p>
 * The file holds held-out content: the values of the development test cases that the trials ran and the judges'
 * rationales, and each unfinished trial's recovery state.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
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
	private static final int FORMAT_VERSION = 6;
	/**
	 * The layout with trial terminology and no explicit evaluator.
	 */
	private static final int SINGLE_EVALUATOR_VERSION = 5;
	/**
	 * The property that qualifies a pilot in a shared campaign.
	 */
	private static final String EVALUATOR = "evaluator";
	/**
	 * The property that holds the number of trials that a pilot draws. A pilot stored without it drew
	 * {@value GuardPilot#TRIALS} trials.
	 */
	private static final String TRIAL_COUNT = "trial_count";
	/**
	 * The property that holds the boundary class of a trial's test case, which a trial of an untagged test case omits.
	 */
	private static final String BOUNDARY_CLASS = "boundary_class";
	/**
	 * The first version to store unfinished trials.
	 */
	private static final int PENDING_TRIALS_VERSION = 4;
	private final Path file;
	private final JsonMapper json;
	private final FileReplacer replacer;

	/**
	 * Creates a pilot file.
	 *
	 * @param heldOut the campaign's {@code held_out} directory
	 * @param json the operation-owned JSON mapper
	 * @param replacer replaces the file
	 * @throws NullPointerException if any of the arguments are null
	 */
	PilotFile(Path heldOut, JsonMapper json, FileReplacer replacer)
	{
		requireThat(heldOut, "heldOut").isNotNull();
		requireThat(json, "json").isNotNull();
		requireThat(replacer, "replacer").isNotNull();
		this.file = heldOut.resolve(NAME);
		this.json = json;
		this.replacer = replacer;
	}

	/**
	 * Replaces the stored pilots.
	 *
	 * @param pilots the pilots, by test and, in shared campaigns, evaluator
	 * @throws NullPointerException if {@code pilots} is null
	 * @throws IOException if the file cannot be written
	 */
	void write(Map<String, PilotResult> pilots) throws IOException
	{
		requireThat(pilots, "pilots").isNotNull();
		ObjectNode root = json.createObjectNode();
		int version = SINGLE_EVALUATOR_VERSION;
		if (pilots.values().stream().anyMatch(pilot -> pilot.evaluator() != null))
			version = FORMAT_VERSION;
		root.put("format_version", version);
		ArrayNode tests = root.putArray("tests");
		for (PilotResult pilot : pilots.values())
		{
			ObjectNode test = tests.addObject();
			test.put("test", pilot.testId());
			if (pilot.evaluator() != null)
				test.put(EVALUATOR, pilot.evaluator().commandName());
			test.put("definition", pilot.definition().hex());
			test.put("cell", pilot.cellId());
			test.put("control", pilot.control().hex());
			test.put("seed", pilot.seed());
			test.put(TRIAL_COUNT, pilot.trialCount());
			test.put("paused", pilot.paused());
			ArrayNode failedTrials = test.putArray("failed_trials");
			pilot.failedTrials().forEach(failedTrials::add);
			if (pilot.stopReason() == null)
				test.putNull("stop_reason");
			else
				test.put("stop_reason", pilot.stopReason());
			ArrayNode trials = test.putArray("trials");
			for (PilotTrial trial : pilot.trials())
			{
				ObjectNode node = trials.addObject();
				node.put("position", trial.position());
				ObjectNode values = node.putObject("values");
				trial.values().forEach(values::put);
				node.put("expected_outcome", trial.expectedOutcome());
				if (!trial.boundaryClass().isEmpty())
					node.put(BOUNDARY_CLASS, trial.boundaryClass());
				ObjectNode assertions = node.putObject("assertions");
				trial.assertions().forEach(assertions::put);
				node.put("record", trial.record());
				ArrayNode attempts = node.putArray("infrastructure_attempts");
				trial.infrastructureAttempts().forEach(attempts::add);
			}
			ArrayNode pending = test.putArray("pending_trials");
			for (Map.Entry<Integer, PilotTrialRecovery> entry : pilot.pendingTrials().entrySet())
				writePending(pending.addObject(), entry.getKey(), entry.getValue());
		}
		Files.createDirectories(file.getParent());
		Path temporary = file.resolveSibling(NAME + ".tmp");
		Files.writeString(temporary, json.writerWithDefaultPrettyPrinter().writeValueAsString(root),
			StandardCharsets.UTF_8);
		replacer.replace(temporary, file);
	}

	/**
	 * Writes one undecided trial.
	 *
	 * @param node where to write the recovery state
	 * @param position the trial's position in the pilot
	 * @param trial the trial's recovery state
	 */
	private static void writePending(ObjectNode node, int position, PilotTrialRecovery trial)
	{
		node.put("position", position);
		trial.requestId().ifPresent(id -> node.put("request_id", id.toString()));
		ArrayNode attempts = node.putArray("infrastructure_attempts");
		trial.infrastructureAttempts().forEach(attempts::add);
		node.put("completed_turn", trial.progress().completedTurn());
		node.put("ended_early", trial.progress().endedEarly());
		ObjectNode settled = node.putObject("settled_assertions");
		trial.progress().settledAssertions().forEach(settled::put);
		trial.discard().ifPresent(discard -> node.putObject("discard").put("reason", discard.reason()).
			put("charged", discard.charged()));
	}

	/**
	 * Reads the stored pilots.
	 *
	 * @return the pilots by test and, in shared campaigns, evaluator; empty if no pilot is stored
	 * @throws IOException if the file cannot be read or is not a pilot file of a readable version
	 */
	Map<String, PilotResult> read() throws IOException
	{
		Map<String, PilotResult> pilots = new LinkedHashMap<>();
		if (!Files.exists(file))
			return pilots;
		JsonNode root = json.readTree(Files.readString(file, StandardCharsets.UTF_8));
		int version = root.path("format_version").asInt();
		if (version < 3 || version > FORMAT_VERSION)
		{
			throw new IOException(file + " is a version " + version + " pilot file, but this version of CAT reads " +
				"versions 3 through " + FORMAT_VERSION + ". Run `start` again to rerun the pilot.");
		}
		try
		{
			if (!root.path("tests").isArray())
				throw new IllegalArgumentException("A pilot file must hold an array of tests");
			for (JsonNode test : root.get("tests"))
			{
				PilotResult pilot = pilot(test, version);
				String key = pilot.key();
				if (pilots.put(key, pilot) != null)
					throw new IllegalArgumentException("Two stored pilots use the key " + key);
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
	 * @param version the stored layout
	 * @return the pilot
	 * @throws IllegalArgumentException if a property is missing or invalid
	 */
	private static PilotResult pilot(JsonNode test, int version)
	{
		int trialCount = GuardPilot.TRIALS;
		if (test.has(TRIAL_COUNT))
		{
			if (!test.get(TRIAL_COUNT).isInt() || test.get(TRIAL_COUNT).asInt() < GuardPilot.TRIALS)
			{
				throw new IllegalArgumentException("\"" + TRIAL_COUNT + "\" must be an integer of at least " +
					GuardPilot.TRIALS + ": " + test);
			}
			trialCount = test.get(TRIAL_COUNT).asInt();
		}
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
			if (!node.path("position").isInt() || node.get("position").asInt() >= trialCount)
			{
				throw new IllegalArgumentException("A trial's \"position\" must be an integer below " + trialCount +
					": " + node);
			}
			String boundaryClass = "";
			if (node.has(BOUNDARY_CLASS))
				boundaryClass = text(node.get(BOUNDARY_CLASS));
			List<String> attempts = infrastructureAttempts(node);
			trials.add(new PilotTrial(node.get("position").asInt(), values, text(node.path("expected_outcome")),
				boundaryClass, assertions, text(node.path("record")), attempts));
		}
		String stopReason = null;
		if (!test.path("stop_reason").isNull())
			stopReason = text(test.path("stop_reason"));
		String failedTrialsField = "failed_trials";
		if (version == 3 || version == 4)
			failedTrialsField = "failed_runs";
		JsonNode failures = test.path(failedTrialsField);
		if (!failures.isArray())
			throw new IllegalArgumentException("\"" + failedTrialsField + "\" must be an array: " + test);
		List<String> failedTrials = new ArrayList<>();
		for (JsonNode reason : failures)
			failedTrials.add(text(reason));
		if (!test.path("seed").isIntegralNumber())
			throw new IllegalArgumentException("\"seed\" must be an integer: " + test);
		if (!test.path("paused").isBoolean())
			throw new IllegalArgumentException("\"paused\" must be a boolean: " + test);
		Map<Integer, PilotTrialRecovery> pending = new LinkedHashMap<>();
		if (version >= PENDING_TRIALS_VERSION)
		{
			if (!test.path("pending_trials").isArray())
				throw new IllegalArgumentException("A pilot's pending trials must be an array: " + test);
			for (JsonNode node : test.get("pending_trials"))
			{
				if (!node.path("position").isInt())
					throw new IllegalArgumentException("An undecided trial must have an integer position: " + node);
				int position = node.get("position").asInt();
				if (pending.put(position, pendingTrial(node)) != null)
					throw new IllegalArgumentException("Two undecided trials hold position " + position);
			}
		}
		Harness evaluator = null;
		if (version > SINGLE_EVALUATOR_VERSION && test.has(EVALUATOR))
		{
			String name = text(test.get(EVALUATOR));
			evaluator = Harness.fromCommandName(name).orElseThrow(() ->
				new IllegalArgumentException("Unknown pilot evaluator " + name));
		}
		return new PilotResult(text(test.path("test")), new Sha256(text(test.path("definition"))),
			text(test.path("cell")), new Sha256(text(test.path("control"))), test.get("seed").asLong(), trials,
			failedTrials, stopReason, test.get("paused").asBoolean(), pending, evaluator, trialCount);
	}

	/**
	 * Reads the recovery state of one undecided trial.
	 *
	 * @param node the stored trial
	 * @return its recovery state
	 * @throws IllegalArgumentException if its recovery state is invalid
	 */
	private static PilotTrialRecovery pendingTrial(JsonNode node)
	{
		Optional<UUID> requestId = Optional.empty();
		if (node.has("request_id"))
		{
			String id = text(node.get("request_id"));
			UUID parsed = UUID.fromString(id);
			if (!parsed.toString().equals(id))
				throw new IllegalArgumentException("A pilot trial request ID must be a canonical UUID");
			requestId = Optional.of(parsed);
		}
		if (!node.path("completed_turn").isInt() || !node.path("ended_early").isBoolean() ||
			!node.path("settled_assertions").isObject())
		{
			throw new IllegalArgumentException("Invalid observer progress in undecided trial: " + node);
		}
		Map<String, Integer> settled = new LinkedHashMap<>();
		for (Map.Entry<String, JsonNode> entry : node.get("settled_assertions").properties())
		{
			if (!entry.getValue().isInt())
				throw new IllegalArgumentException("An assertion settlement must name an integer turn: " + entry);
			settled.put(entry.getKey(), entry.getValue().asInt());
		}
		TurnObserver.Progress progress = new TurnObserver.Progress(node.get("completed_turn").asInt(), settled,
			node.get("ended_early").asBoolean());
		Optional<Discard> discard = Optional.empty();
		if (node.has("discard"))
		{
			JsonNode value = node.get("discard");
			if (!value.path("charged").isBoolean())
				throw new IllegalArgumentException("A discard must say whether it consumes an attempt: " + value);
			discard = Optional.of(new Discard(text(value.path("reason")), value.get("charged").asBoolean()));
		}
		return new PilotTrialRecovery(requestId, progress, infrastructureAttempts(node), discard);
	}

	/**
	 * Reads the infrastructure attempts of one trial.
	 *
	 * @param node the stored trial
	 * @return the reasons why the trial's earlier attempts ended without an outcome
	 * @throws IllegalArgumentException if the attempts are invalid
	 */
	private static List<String> infrastructureAttempts(JsonNode node)
	{
		if (!node.path("infrastructure_attempts").isArray())
			throw new IllegalArgumentException("A trial's infrastructure attempts must be an array: " + node);
		List<String> attempts = new ArrayList<>();
		for (JsonNode reason : node.get("infrastructure_attempts"))
			attempts.add(text(reason));
		return attempts;
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
