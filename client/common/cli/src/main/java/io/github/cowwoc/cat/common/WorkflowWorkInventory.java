package io.github.cowwoc.cat.common;

import io.github.cowwoc.cat.common.evaluation.EvaluationWorkspaceLifecycle;
import io.github.cowwoc.cat.common.evaluation.WorkDirectories;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Inspects recorded ownership for direct CAT work entries without deleting them.
 */
final class WorkflowWorkInventory
{
	private static final String TEMP_DIRECTORY = "temp";
	private static final String OWNER_MARKER = ".cat-workflow-temp";
	private static final String EVALUATION_RECORD_MARKER = ".cat-prompt-evaluation-record";

	/**
	 * Prevents construction of this static utility.
	 */
	private WorkflowWorkInventory()
	{
	}

	/**
	 * Reports every direct durable and invocation-local CAT work entry with the ownership evidence needed before cleanup.
	 *
	 * <p>
	 * An entry is not disposable merely because its creating process ended. This inventory calls the evaluator's status
	 * boundary for evaluator evidence and reports an unrecorded entry as unresolved instead of guessing that it is stale.
	 *
	 * @param workRoot the CAT work root to inspect
	 * @param json the mapper owned by the caller's operation scope
	 * @return one description for each direct durable entry and each direct temporary invocation
	 * @throws IOException if a recorded entry cannot be inspected safely
	 */
	static ArrayNode inventory(Path workRoot, JsonMapper json) throws IOException
	{
		workRoot = workRoot.toAbsolutePath().normalize();
		requireThat(workRoot, "workRoot").isDirectory();
		requireThat(json, "json").isNotNull();
		Map<Path, Integer> retainedReferences = retainedReferences(workRoot, json);
		Map<Path, WorkflowWorkOwnership.Entry> declarations = WorkflowWorkOwnership.read(workRoot, json);
		ArrayNode result = json.createArrayNode();
		try (DirectoryStream<Path> entries = Files.newDirectoryStream(workRoot))
		{
			for (Path entry : entries)
			{
				if (entry.getFileName().toString().equals(WorkflowWorkOwnership.fileName()) ||
					entry.getFileName().toString().equals(WorkflowWorkOwnership.lockFileName()))
					continue;
				if (entry.getFileName().toString().equals(TEMP_DIRECTORY))
				{
					inventoryTemporaryEntries(entry, retainedReferences, declarations, json, result);
					continue;
				}
				result.add(describe(entry, "durable", retainedReferences, declarations, json));
			}
		}
		return result;
	}

	/**
	 * Counts temporary invocations named by direct durable JSON workflow records.
	 *
	 * <p>
	 * Durable workflow state lives directly below the work root. Runner caches and copied projects can contain many
	 * unrelated JSON files, so inventory reads only direct state instead of scanning generated dependencies.
	 *
	 * @param workRoot the CAT work root
	 * @param json the caller-owned JSON mapper
	 * @return the number of durable references to each direct CAT work entry
	 * @throws IOException if a direct durable JSON record cannot be read
	 */
	private static Map<Path, Integer> retainedReferences(Path workRoot, JsonMapper json) throws IOException
	{
		Map<Path, Integer> result = new HashMap<>();
		try (DirectoryStream<Path> entries = Files.newDirectoryStream(workRoot, "*.json"))
		{
			for (Path entry : entries)
				countReferences(json.readTree(Files.readString(entry, StandardCharsets.UTF_8)), workRoot, result);
		}
		return result;
	}

	/**
	 * Counts absolute path strings that name a direct CAT work entry.
	 *
	 * @param value one retained workflow value
	 * @param workRoot the CAT work root
	 * @param result the counts keyed by direct CAT work entry
	 */
	private static void countReferences(JsonNode value, Path workRoot, Map<Path, Integer> result)
	{
		if (value instanceof ObjectNode object)
		{
			object.properties().forEach(entry -> countReferences(entry.getValue(), workRoot, result));
			return;
		}
		if (value instanceof ArrayNode array)
		{
			array.forEach(entry -> countReferences(entry, workRoot, result));
			return;
		}
		if (!value.isString())
			return;
		Path referenced;
		try
		{
			referenced = Path.of(value.asString()).toAbsolutePath().normalize();
		}
		catch (RuntimeException _)
		{
			return;
		}
		if (!referenced.startsWith(workRoot))
			return;
		Path relative = workRoot.relativize(referenced);
		if (relative.getNameCount() == 0)
			return;
		Path entry = workRoot.resolve(relative.getName(0));
		if (entry.getFileName().toString().equals(TEMP_DIRECTORY))
		{
			if (relative.getNameCount() == 1)
				return;
			entry = entry.resolve(relative.getName(1));
		}
		result.merge(entry, 1, Integer::sum);
	}

	/**
	 * Reports each direct invocation-local entry below CAT work temporary storage.
	 *
	 * @param temporaryRoot the CAT work temporary root
	 * @param retainedReferences durable references indexed by temporary invocation
	 * @param declarations recorded durable-entry ownership indexed by direct entry
	 * @param json the caller-owned mapper
	 * @param result the destination inventory
	 * @throws IOException if temporary storage cannot be inspected
	 */
	private static void inventoryTemporaryEntries(Path temporaryRoot, Map<Path, Integer> retainedReferences,
		Map<Path, WorkflowWorkOwnership.Entry> declarations, JsonMapper json, ArrayNode result)
		throws IOException
	{
		if (!Files.isDirectory(temporaryRoot, LinkOption.NOFOLLOW_LINKS))
			throw new IOException("CAT work temp path is not a directory: " + temporaryRoot);
		try (DirectoryStream<Path> entries = Files.newDirectoryStream(temporaryRoot))
		{
			for (Path entry : entries)
				result.add(describe(entry, "invocation", retainedReferences, declarations, json));
		}
	}

	/**
	 * Describes one work entry from its explicit ownership markers.
	 *
	 * @param entry the direct work entry
	 * @param location whether the entry is durable or invocation-local
	 * @param retainedReferences durable references indexed by temporary invocation
	 * @param declarations recorded durable-entry ownership indexed by direct entry
	 * @param json the caller-owned mapper
	 * @return the caller-readable inventory entry
	 * @throws IOException if a recorded evaluation cannot be inspected
	 */
	private static ObjectNode describe(Path entry, String location, Map<Path, Integer> retainedReferences,
		Map<Path, WorkflowWorkOwnership.Entry> declarations, JsonMapper json) throws IOException
	{
		ObjectNode result = json.createObjectNode();
		Path normalized = entry.toAbsolutePath().normalize();
		result.put("path", normalized.toString());
		result.put("location", location);
		Scan scan = scan(normalized);
		result.put("size_bytes", scan.sizeBytes());

		Markers markers = scan.markers();
		if (!markers.evaluations().isEmpty())
			return describeEvaluations(result, normalized, markers.evaluations(),
				retainedReferences.getOrDefault(normalized, 0), json);
		String durableConsumer = WorkDirectories.consumer(normalized);
		if (durableConsumer != null)
		{
			result.put("creator", "prompt-evaluation");
			result.put("declared_consumer", durableConsumer);
			result.put("active_owner", false);
			result.put("retained_references", retainedReferences.getOrDefault(normalized, 0));
			result.put("terminal_state", "durable shared store");
			result.put("safe_to_remove", false);
			return result;
		}
		WorkflowWorkOwnership.Entry declaration = declarations.get(normalized);
		if (declaration != null)
		{
			result.put("creator", declaration.creator());
			result.put("declared_consumer", declaration.consumer());
			result.put("active_owner", false);
			result.put("retained_references", retainedReferences.getOrDefault(normalized, 0));
			result.put("terminal_state", "retained by recorded consumer");
			result.put("safe_to_remove", false);
			return result;
		}
		if (markers.owner() != null)
		{
			result.put("creator", Files.readString(markers.owner(), StandardCharsets.UTF_8).strip());
			result.put("declared_consumer", "none recorded");
			result.put("active_owner", false);
			result.put("retained_references", retainedReferences.getOrDefault(normalized, 0));
			result.put("terminal_state", "unreferenced invocation");
			result.put("safe_to_remove", true);
			return result;
		}
		result.put("creator", "not recorded");
		result.put("declared_consumer", "not recorded");
		result.put("active_owner", false);
		result.put("retained_references", retainedReferences.getOrDefault(normalized, 0));
		result.put("terminal_state", "ownership unresolved");
		result.put("safe_to_remove", false);
		return result;
	}

	/**
	 * Describes every evaluator record below one direct work entry through the evaluator's authoritative status
	 * operation.
	 *
	 * @param result the partially populated inventory entry
	 * @param entry the direct work entry containing the evidence
	 * @param markers the evidence and durable-record ownership markers
	 * @param retainedReferences the durable records that name the direct work entry
	 * @param json the caller-owned mapper
	 * @return the completed inventory entry
	 */
	private static ObjectNode describeEvaluations(ObjectNode result, Path entry, List<Path> markers,
		int retainedReferences, JsonMapper json)
	{
		result.put("creator", "prompt-evaluation");
		ArrayNode evaluations = result.putArray("evaluations");
		boolean activeOwner = false;
		boolean statusUnavailable = false;
		List<String> consumers = new ArrayList<>();
		List<String> states = new ArrayList<>();
		Set<Path> recordedRuns = new HashSet<>();
		for (Path marker : markers)
		{
			ObjectNode evaluation = evaluations.addObject();
			evaluation.put("marker", marker.toString());
			try
			{
				Path run = Path.of(Files.readString(marker, StandardCharsets.UTF_8).strip()).toAbsolutePath().normalize();
				if (!recordedRuns.add(run))
				{
					evaluations.remove(evaluations.size() - 1);
					continue;
				}
				evaluation.put("run", run.toString());
				ObjectNode record = object(json.readTree(Files.readString(run, StandardCharsets.UTF_8)),
					"evaluation record", run);
				Path workspace = workspace(record, entry);
				ObjectNode status = PromptEvaluation.status(run, workspace, true);
				String consumer = status.path("next_action").asString();
				String state = status.path("evaluation_state").asString();
				evaluation.put("declared_consumer", consumer);
				evaluation.put("active_owner", status.path("owner_active").asBoolean());
				evaluation.put("terminal_state", state);
				evaluation.set("status", status);
				activeOwner |= status.path("owner_active").asBoolean();
				consumers.add(consumer);
				states.add(state);
			}
			catch (IOException e)
			{
				evaluation.put("declared_consumer", "evaluation status requires repair");
				evaluation.put("active_owner", false);
				evaluation.put("terminal_state", "status unavailable");
				evaluation.put("status_error", e.getMessage());
				statusUnavailable = true;
				consumers.add("evaluation status requires repair");
				states.add("status unavailable");
			}
		}
		result.put("record_count", evaluations.size());
		result.put("retained_references", retainedReferences);
		consumers = consumers.stream().distinct().sorted().toList();
		states = states.stream().distinct().sorted().toList();
		String declaredConsumer = "multiple recorded consumers";
		if (consumers.size() == 1)
			declaredConsumer = consumers.getFirst();
		result.put("declared_consumer", declaredConsumer);
		ArrayNode declaredConsumers = result.putArray("declared_consumers");
		consumers.forEach(declaredConsumers::add);
		result.put("active_owner", activeOwner);
		String terminalState = "multiple recorded states";
		if (states.size() == 1)
			terminalState = states.getFirst();
		if (statusUnavailable)
			terminalState = "status unavailable";
		result.put("terminal_state", terminalState);
		result.put("safe_to_remove", false);
		return result;
	}

	/**
	 * Selects the workspace required by the evaluator status boundary.
	 *
	 * @param record the evaluation record
	 * @param fallback the direct work entry containing the evidence
	 * @return the recorded workspace, or the evidence entry for a finalized workspace
	 */
	private static Path workspace(ObjectNode record, Path fallback)
	{
		for (String field : new String[]{"workspace", "retained_workspace"})
		{
			JsonNode value = record.path(field);
			if (value.isString() && !value.asString().isBlank())
				return Path.of(value.asString()).toAbsolutePath().normalize();
		}
		return fallback;
	}

	/**
	 * Finds ownership markers and counts bytes in one traversal without following symbolic links.
	 *
	 * @param root the direct work entry to inspect
	 * @return the discovered markers and regular-file byte count
	 * @throws IOException if the entry cannot be inspected
	 */
	private static Scan scan(Path root) throws IOException
	{
		Markers[] markers = {new Markers(null, new ArrayList<>())};
		long[] sizeBytes = {0};
		Files.walkFileTree(root, new SimpleFileVisitor<>()
		{
			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
			{
				if (attributes.isRegularFile())
					sizeBytes[0] = Math.addExact(sizeBytes[0], attributes.size());
				String name = file.getFileName().toString();
				if (file.equals(root.resolve(OWNER_MARKER)))
					markers[0] = markers[0].withOwner(file);
				else if (name.equals(EvaluationWorkspaceLifecycle.EVIDENCE_MARKER) || name.equals(EVALUATION_RECORD_MARKER))
					markers[0] = markers[0].withEvaluation(file);
				return FileVisitResult.CONTINUE;
			}
		});
		return new Scan(markers[0].sorted(), sizeBytes[0]);
	}

	/**
	 * Interprets a JSON object read from one owned record.
	 *
	 * @param value the parsed JSON value
	 * @param description the reader-facing document description
	 * @param source the document path
	 * @return the object value
	 * @throws IOException if the value is not an object
	 */
	private static ObjectNode object(JsonNode value, String description, Path source) throws IOException
	{
		if (value instanceof ObjectNode result)
			return result;
		throw new IOException("Expected " + description + " to be a JSON object: " + source);
	}

	/**
	 * Holds the ownership markers discovered below one direct work entry.
	 *
	 * @param owner the invocation-owner marker, if present
	 * @param evaluations the evaluator-evidence and durable-record markers, if present
	 */
	private record Markers(Path owner, List<Path> evaluations)
	{
		/**
		 * Replaces the invocation-owner marker.
		 *
		 * @param value the discovered marker
		 * @return a marker set containing the value
		 */
		private Markers withOwner(Path value)
		{
			return new Markers(value, evaluations);
		}

		/**
		 * Adds one evaluator ownership marker.
		 *
		 * @param value the discovered marker
		 * @return a marker set containing the value
		 */
		private Markers withEvaluation(Path value)
		{
			List<Path> updated = new ArrayList<>(evaluations);
			updated.add(value);
			return new Markers(owner, updated);
		}

		/**
		 * Sorts evaluator ownership markers into stable inventory order.
		 *
		 * @return a marker set with stable marker ordering
		 */
		private Markers sorted()
		{
			return new Markers(owner, evaluations.stream().sorted(Comparator.comparing(Path::toString)).toList());
		}
	}

	/**
	 * Holds the ownership markers and measured size for one direct work entry.
	 *
	 * @param markers the ownership markers discovered in the entry
	 * @param sizeBytes the regular-file bytes measured in the same traversal
	 */
	private record Scan(Markers markers, long sizeBytes)
	{
	}
}
