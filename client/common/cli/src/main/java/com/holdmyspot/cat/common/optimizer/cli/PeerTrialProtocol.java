package com.holdmyspot.cat.common.optimizer.cli;

import com.holdmyspot.cat.common.PortablePaths;
import com.holdmyspot.cat.common.optimizer.campaign.Harness;
import com.holdmyspot.cat.common.optimizer.contract.Part;
import com.holdmyspot.cat.common.optimizer.contract.TestValueJson;
import com.holdmyspot.cat.common.optimizer.contract.TestWorkspace;
import com.holdmyspot.cat.common.optimizer.contract.ToolCall;
import com.holdmyspot.cat.common.optimizer.contract.TrialProfile;
import com.holdmyspot.cat.common.optimizer.contract.WorkspaceFile;
import com.holdmyspot.cat.common.optimizer.port.RateLimitWindow;
import com.holdmyspot.cat.common.optimizer.port.SessionRequest;
import com.holdmyspot.cat.common.optimizer.port.SessionResult;
import com.holdmyspot.cat.common.optimizer.port.SessionRole;
import com.holdmyspot.cat.common.optimizer.port.TokenUsage;
import com.holdmyspot.cat.common.optimizer.port.TurnObserver;
import com.holdmyspot.cat.common.runner.HarnessCapacity.Hold;
import com.holdmyspot.cat.common.runner.RetainedTrialClaim;
import com.holdmyspot.cat.common.runner.RetainedTrialResponse.HarnessSession;
import com.holdmyspot.cat.common.runner.RetainedTrialResponse.Identity;
import com.holdmyspot.cat.common.runner.SessionRequestForwarder.Route;
import com.holdmyspot.cat.common.runner.TrialPluginInput;
import com.holdmyspot.cat.common.runner.policy.ExtraPermissions;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Encodes one trial between a campaign owner and a same-release installed evaluator.
 */
final class PeerTrialProtocol
{
	private static final int VERSION = 1;
	private static final int MAXIMUM_RECORD_CHARACTERS = 8_388_608;
	private static final TypeReference<List<String>> STRINGS = new TypeReference<>()
	{
	};
	private static final TypeReference<List<List<String>>> RESPONSES = new TypeReference<>()
	{
	};
	private static final TypeReference<List<ToolCall>> TOOLS = new TypeReference<>()
	{
	};
	private static final TypeReference<Map<String, Integer>> SETTLEMENTS = new TypeReference<>()
	{
	};
	private final JsonMapper json;

	/**
	 * Creates the protocol.
	 *
	 * @param json the process's mapper
	 */
	PeerTrialProtocol(JsonMapper json)
	{
		this.json = json;
	}

	/**
	 * Encodes a tracked trial, its owner broker and the observer's prior progress, if any.
	 *
	 * @param launch the owner-selected trial
	 * @return the start record
	 * @throws IOException if the request requires a callback other than the trial observer
	 */
	ObjectNode start(Launch launch) throws IOException
	{
		SessionRequest request = launch.request();
		if (request.role() != SessionRole.TRIAL || request.followUp().isPresent() ||
			request.evidencePrompt().isPresent() || !(request.files() instanceof TestWorkspace workspace))
		{
			throw new IOException("The evaluator accepts only prompt test trials and their completed-turn observer");
		}
		ObjectNode record = record("start").put("format_version", VERSION).
			put("harness", launch.harness().commandName()).put("route", launch.route().name()).
			put("requests", launch.requests().toString()).put("results", launch.results().toString()).
			put("project", launch.project().toString()).put("request_id", launch.requestId().toString()).
			put("part", request.part().name()).put("source", request.source().toString()).
			put("time_limit", request.timeLimit().toString()).put("control_source", request.controlSource());
		record.set("held_out", json.valueToTree(pathStrings(launch.heldOut())));
		record.set("turns", json.valueToTree(request.turns()));
		record.set("read_only", json.valueToTree(pathStrings(request.readOnly())));
		record.set("plugins", json.valueToTree(pathStrings(request.plugins())));
		TrialPluginInput.writeProvenance(request.pluginInputs(), record);
		record.set("profile", json.valueToTree(request.profile()));
		record.set("extra_permissions", request.extraPermissions().toDocument(json));
		ObjectNode files = record.putObject("workspace");
		files.set("files", json.valueToTree(workspace.files()));
		files.put("expected_outcome", workspace.expectedOutcome());
		TestValueJson.write(workspace.values(), files.putArray("values"));
		if (launch.claim() != null)
		{
			RetainedTrialClaim claim = launch.claim();
			HarnessSession retained = claim.identity().session().orElseThrow();
			record.putObject("claim").put("session_id", retained.sessionId()).put("harness", retained.harness()).
				put("slot_generation", claim.identity().slotGeneration().toString()).
				put("completed_turns", claim.completedTurns());
		}
		if (launch.progress() != null)
			record.set("progress", progress(launch.progress()));
		return record;
	}

	/**
	 * Decodes the start message.
	 *
	 * @param node the incoming owner request
	 * @return the tracked trial described by the owner
	 * @throws IOException if the record does not describe a supported trial
	 */
	Launch start(JsonNode node) throws IOException
	{
		if (!type(node).equals("start") || number(node, "format_version") != VERSION)
			throw new IOException("Expected an evaluator trial start record of the same CAT release");
		Harness harness = Harness.fromCommandName(text(node, "harness")).orElseThrow(() ->
			new IOException("Unknown evaluator harness"));
		UUID id = UUID.fromString(text(node, "request_id"));
		RetainedTrialClaim claim = null;
		if (node.has("claim"))
		{
			JsonNode saved = node.path("claim");
			HarnessSession retained = new HarnessSession(text(saved, "harness"), text(saved, "session_id"));
			claim = new RetainedTrialClaim(id, new Identity(UUID.fromString(text(saved, "slot_generation")),
				Optional.of(retained)), number(saved, "completed_turns"));
		}
		TurnObserver.Progress progress = null;
		if (node.has("progress"))
			progress = progress(node.path("progress"));
		SessionRequest request = new SessionRequest(SessionRole.TRIAL, Part.valueOf(text(node, "part")),
			absolute(text(node, "source")), json.convertValue(node.path("turns"), STRINGS), paths(node.path("read_only")),
			Duration.parse(text(node, "time_limit")), json.treeToValue(node.path("profile"), TrialProfile.class),
			workspace(node.path("workspace")), Optional.empty(), flag(node, "control_source"),
			ExtraPermissions.fromDocument(node.path("extra_permissions"), "evaluator trial"), Optional.empty(),
			Optional.empty(), TrialPluginInput.readProvenance(paths(node.path("plugins")),
				node.path(TrialPluginInput.PROVENANCE_FIELD)));
		return new Launch(harness, Route.valueOf(text(node, "route")), absolute(text(node, "requests")),
			absolute(text(node, "results")), absolute(text(node, "project")), paths(node.path("held_out")), request,
			id, claim, progress);
	}

	/**
	 * Encodes the state the owner durably decided through one turn.
	 *
	 * @param progress the observer's completed turn and settlement state
	 * @return the decision value
	 */
	ObjectNode progress(TurnObserver.Progress progress)
	{
		ObjectNode value = json.createObjectNode().put("completed_turn", progress.completedTurn()).
			put("ended_early", progress.endedEarly());
		value.set("settled_assertions", json.valueToTree(progress.settledAssertions()));
		return value;
	}

	/**
	 * Decodes an observer's state.
	 *
	 * @param node the decision value
	 * @return the observer's progress
	 * @throws IOException if the decision cannot be read
	 */
	TurnObserver.Progress progress(JsonNode node) throws IOException
	{
		return new TurnObserver.Progress(number(node, "completed_turn"),
			json.convertValue(node.path("settled_assertions"), SETTLEMENTS), flag(node, "ended_early"));
	}

	/**
	 * Encodes the peer's result.
	 *
	 * @param result the peer's typed result
	 * @return its wire representation
	 */
	ObjectNode result(SessionResult result)
	{
		ObjectNode value = switch (result)
		{
			case SessionResult.Completed completed -> completed(completed);
			case SessionResult.Interrupted _ -> record("interrupted");
			case SessionResult.RateLimited limited -> record("rate_limited").set("hold", hold(limited.hold()));
			case SessionResult.Refused refused -> record("refused").put("after_longest_hold", refused.afterLongestHold()).
				set("hold", hold(refused.hold()));
			case SessionResult.LaunchRefused refused -> record("launch_refused").put("reason", refused.reason());
			case SessionResult.Failed failed -> record("failed").put("reason", failed.reason());
		};
		ArrayNode windows = value.putArray("windows");
		for (RateLimitWindow window : result.windows())
		{
			ObjectNode item = windows.addObject().put("utilization", window.utilization());
			if (window.resetsAt() != null)
				item.put("resets_at", window.resetsAt().toString());
		}
		return value;
	}

	/**
	 * Decodes the peer's result.
	 *
	 * @param node the result value
	 * @return the peer's typed result
	 * @throws IOException if the result could not be read
	 */
	SessionResult result(JsonNode node) throws IOException
	{
		List<RateLimitWindow> windows = new ArrayList<>();
		JsonNode list = node.path("windows");
		if (!list.isArray())
			throw new IOException("Expected evaluator trial rate-limit windows");
		for (JsonNode item : list)
		{
			if (!item.path("utilization").isNumber())
				throw new IOException("Expected evaluator trial rate-limit utilization");
			Instant reset = null;
			if (item.has("resets_at"))
				reset = Instant.parse(text(item, "resets_at"));
			windows.add(new RateLimitWindow(item.path("utilization").doubleValue(), reset));
		}
		return switch (type(node))
		{
			case "completed" -> completed(node, windows);
			case "interrupted" -> new SessionResult.Interrupted(windows);
			case "rate_limited" -> new SessionResult.RateLimited(hold(node.path("hold")), windows);
			case "refused" -> new SessionResult.Refused(hold(node.path("hold")), flag(node, "after_longest_hold"), windows);
			case "launch_refused" -> new SessionResult.LaunchRefused(text(node, "reason"), windows);
			case "failed" -> new SessionResult.Failed(text(node, "reason"), windows);
			default -> throw new IOException("Unknown evaluator trial result");
		};
	}

	/**
	 * Creates a protocol record of the named type.
	 *
	 * @param type the message or result type
	 * @return the record
	 */
	ObjectNode record(String type)
	{
		return json.createObjectNode().put("type", type);
	}

	/**
	 * Writes and flushes one UTF-8 line through the caller's writer.
	 *
	 * @param output the protocol output
	 * @param node the record to send
	 * @throws IOException if the record is too large or its pipe fails
	 */
	void send(Writer output, ObjectNode node) throws IOException
	{
		String line = json.writer().without(SerializationFeature.INDENT_OUTPUT).writeValueAsString(node);
		if (line.length() > MAXIMUM_RECORD_CHARACTERS)
			throw new IOException("Evaluator trial record exceeds its size limit");
		synchronized (output)
		{
			output.write(line);
			output.write('\n');
			output.flush();
		}
	}

	/**
	 * Reads one bounded record.
	 *
	 * @param input the protocol input
	 * @return the record, or null at EOF before another record
	 * @throws IOException if the record cannot be read
	 */
	JsonNode receive(BufferedReader input) throws IOException
	{
		StringBuilder line = new StringBuilder();
		for (int next = input.read(); next != -1; next = input.read())
		{
			if (next == '\n')
			{
				try
				{
					return json.readerFor(JsonNode.class).with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
						DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).readValue(line.toString());
				}
				catch (JacksonException e)
				{
					throw new IOException("Malformed evaluator trial record", e);
				}
			}
			if (line.length() == MAXIMUM_RECORD_CHARACTERS)
				throw new IOException("Evaluator trial record exceeds its size limit");
			line.append((char) next);
		}
		if (!line.isEmpty())
			throw new IOException("Incomplete evaluator trial record");
		return null;
	}

	/**
	 * Reads a named protocol record type.
	 *
	 * @param node the record
	 * @return the type
	 * @throws IOException if it is missing or malformed
	 */
	static String type(JsonNode node) throws IOException
	{
		return text(node, "type");
	}

	/**
	 * Reads a protocol text property.
	 *
	 * @param node the object containing the field
	 * @param name the property name
	 * @return the property's text
	 * @throws IOException if the field does not contain text
	 */
	static String text(JsonNode node, String name) throws IOException
	{
		JsonNode value = node.path(name);
		if (!value.isString())
			throw new IOException("Expected evaluator trial text field " + name);
		return value.stringValue();
	}

	/**
	 * Reads a protocol integer property.
	 *
	 * @param node the object containing the field
	 * @param name the property name
	 * @return the property's integer
	 * @throws IOException if the field does not contain an integer
	 */
	static int number(JsonNode node, String name) throws IOException
	{
		JsonNode value = node.path(name);
		if (!value.isIntegralNumber() || !value.canConvertToInt())
			throw new IOException("Expected evaluator trial integer field " + name);
		return value.intValue();
	}

	/**
	 * Reads a protocol flag.
	 *
	 * @param node the object containing the field
	 * @param name the property name
	 * @return the property's flag
	 * @throws IOException if the field does not contain a boolean
	 */
	private static boolean flag(JsonNode node, String name) throws IOException
	{
		JsonNode value = node.path(name);
		if (!value.isBoolean())
			throw new IOException("Expected evaluator trial boolean field " + name);
		return value.booleanValue();
	}

	/**
	 * Converts a completed session to its protocol representation.
	 *
	 * @param completed the session evidence through its completed turns
	 * @return the value before its rate windows are added
	 */
	private ObjectNode completed(SessionResult.Completed completed)
	{
		ObjectNode value = record("completed").put("transcript", completed.transcript().toString()).
			put("effects", completed.effects().toString()).put("initial_project", completed.initialProject().toString()).
			put("model", completed.model()).put("reasoning", completed.reasoning()).
			put("prompt_sources", completed.promptSources()).put("requested_turns", completed.requestedTurns()).
			put("ended_early", completed.endedEarly());
		value.set("responses", json.valueToTree(completed.responses()));
		value.set("usage", json.valueToTree(completed.usage()));
		value.set("tool_calls", json.valueToTree(completed.toolCalls()));
		value.set("settled_assertions", json.valueToTree(completed.settledAssertions()));
		return value;
	}

	/**
	 * Reads the evidence references and normalized records in a completed result.
	 *
	 * @param node    the incoming value
	 * @param windows its rate-limit windows
	 * @return the peer's completed result
	 * @throws IOException if the result cannot be read
	 */
	private SessionResult.Completed completed(JsonNode node, List<RateLimitWindow> windows) throws IOException
	{
		return new SessionResult.Completed(absolute(text(node, "transcript")),
			json.convertValue(node.path("responses"), RESPONSES), absolute(text(node, "effects")),
			absolute(text(node, "initial_project")), node.path("model").stringValue(null),
			node.path("reasoning").stringValue(null), json.treeToValue(node.path("usage"), TokenUsage.class), windows,
			text(node, "prompt_sources"), json.convertValue(node.path("tool_calls"), TOOLS), number(node, "requested_turns"),
			flag(node, "ended_early"), json.convertValue(node.path("settled_assertions"), SETTLEMENTS));
	}

	/**
	 * Encodes a provider hold.
	 *
	 * @param hold the peer's provider hold
	 * @return the wire value
	 */
	private ObjectNode hold(Hold hold)
	{
		return json.createObjectNode().put("ends_at", hold.endsAt().toString()).put("backoffs", hold.backoffs());
	}

	/**
	 * Decodes a provider hold.
	 *
	 * @param node the hold value
	 * @return the peer's hold
	 * @throws IOException if a field cannot be read
	 */
	private static Hold hold(JsonNode node) throws IOException
	{
		return new Hold(Instant.parse(text(node, "ends_at")), number(node, "backoffs"));
	}

	/**
	 * Reads the trial's workspace declarations and the bound test values.
	 *
	 * @param node the workspace value
	 * @return the trial's workspace
	 * @throws IOException if the workspace cannot be decoded
	 */
	private TestWorkspace workspace(JsonNode node) throws IOException
	{
		JsonNode entries = node.path("files");
		if (!entries.isObject())
			throw new IOException("Expected evaluator trial workspace files");
		Map<String, WorkspaceFile> files = new LinkedHashMap<>();
		for (String key : entries.propertyNames())
		{
			Path path = Path.of(key);
			if (PortablePaths.isRooted(path) || !path.normalize().equals(path))
				throw new IOException("Expected a workspace-relative evaluator trial path");
			WorkspaceFile file = json.treeToValue(entries.path(key), WorkspaceFile.class);
			// Validate the resolved path, which may differ from the file's template.
			files.put(key, new WorkspaceFile(key, file.content(), file.source(), file.link()));
		}
		return new TestWorkspace(files, TestValueJson.read(node.path("values")), text(node, "expected_outcome"));
	}

	/**
	 * Encodes absolute local paths.
	 *
	 * @param paths the paths
	 * @return the paths as text
	 */
	private static List<String> pathStrings(List<Path> paths)
	{
		return paths.stream().map(Path::toString).toList();
	}

	/**
	 * Decodes a list of absolute local paths.
	 *
	 * @param node the array
	 * @return the decoded paths
	 * @throws IOException if a path is relative
	 */
	private List<Path> paths(JsonNode node) throws IOException
	{
		List<Path> paths = new ArrayList<>();
		for (String path : json.convertValue(node, STRINGS))
			paths.add(absolute(path));
		return List.copyOf(paths);
	}

	/**
	 * Reads an absolute path supplied on the protocol.
	 *
	 * @param text the supplied path
	 * @return the normalized path
	 * @throws IOException if the path is relative
	 */
	private static Path absolute(String text) throws IOException
	{
		Path path = Path.of(text);
		if (!path.isAbsolute())
			throw new IOException("Expected an absolute evaluator trial path");
		return path.normalize();
	}

	/**
	 * The complete launch input for one tracked trial.
	 *
	 * @param harness   the selected evaluator
	 * @param route     the owner's resolved broker mode
	 * @param requests  the owner's request directory
	 * @param results   the owner's results directory
	 * @param project   the owner's host project
	 * @param heldOut   the directories denied to development sessions
	 * @param request   the typed trial
	 * @param requestId its durable original request ID
	 * @param claim     the retained checkpoint, or null for a fresh launch
	 * @param progress  the owner observer's saved progress, or null without an observer
	 */
	record Launch(Harness harness, Route route, Path requests, Path results, Path project, List<Path> heldOut,
		SessionRequest request, UUID requestId, RetainedTrialClaim claim, TurnObserver.Progress progress)
	{
	}
}
