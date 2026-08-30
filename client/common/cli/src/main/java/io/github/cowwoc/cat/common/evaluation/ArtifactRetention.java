package io.github.cowwoc.cat.common.evaluation;

import io.github.cowwoc.cat.common.InvocationDirectory;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Retains runner artifacts and identifies the copies no retained evaluation still needs.
 */
public final class ArtifactRetention
{
	public static final String RECORD_MARKER = ".cat-prompt-evaluation-record";
	/**
	 * Prevents construction of this static utility.
	 */
	private ArtifactRetention()
	{
	}

	/**
	 * Marks a record directory as owned by the evaluation that created its run record.
	 *
	 * @param recordDirectory the existing evaluation record directory
	 * @param runPath the record stored in that directory
	 * @throws IOException if the ownership marker cannot be written
	 */
	public static void markRecord(Path recordDirectory, Path runPath) throws IOException
	{
		Files.writeString(recordDirectory.resolve(RECORD_MARKER), runPath.toAbsolutePath().normalize() +
			System.lineSeparator(), StandardCharsets.UTF_8);
	}

	/**
	 * Locates the directory containing terminal evidence owned by the selected evaluation.
	 *
	 * @param temporaryRoot the CAT work temporary directory
	 * @param runPath the selected evaluation record
	 * @return the owned directory containing the record and workspace
	 * @throws IOException if the record belongs to neither supported evaluator layout
	 */
	public static Path ownedEvidence(Path temporaryRoot, Path runPath) throws IOException
	{
		Path run = runPath.toAbsolutePath().normalize();
		Path recordRoot = run.getParent();
		if (recordRoot == null)
			throw new IOException("Evaluation record has no parent directory: " + runPath);
		Path marker = recordRoot.resolve(RECORD_MARKER);
		if (Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS))
			return ownedRecord(temporaryRoot, run);
		return InvocationDirectory.owningDirectory(temporaryRoot, recordRoot);
	}

	/**
	 * Verifies the directory containing a run record was created exclusively for that evaluation.
	 *
	 * @param temporaryRoot the CAT work temporary directory
	 * @param runPath the evaluation record selected for finalization
	 * @return the record directory that may be deleted after terminal verification
	 * @throws IOException if the record is not in an evaluator-owned CAT work temporary directory
	 */
	private static Path ownedRecord(Path temporaryRoot, Path runPath) throws IOException
	{
		Path temporary = temporaryRoot.toAbsolutePath().normalize();
		Path run = runPath.toAbsolutePath().normalize();
		Path recordRoot = run.getParent();
		if (recordRoot == null || !recordRoot.startsWith(temporary) || recordRoot.equals(temporary))
			throw new IOException("Evaluation record is not beneath the CAT work temp directory: " + run);
		Path marker = recordRoot.resolve(RECORD_MARKER);
		boolean recordOwned = Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS) &&
			Files.readString(marker, StandardCharsets.UTF_8).strip().equals(run.toString());
		if (!recordOwned)
			throw new IOException("Evaluation record directory is not owned by prompt-evaluation: " + recordRoot);
		return recordRoot;
	}

	/**
	 * Copies a verified runner runtime to the immutable store for use by an evaluation.
	 *
	 * @param workRoot the CAT work root that owns reusable evaluation artifacts
	 * @param source the verified packaged runner runtime
	 * @return the immutable runtime copy
	 * @throws IOException if the runtime is unsafe or cannot be retained
	 */
	public static Path retainRunnerArtifact(Path workRoot, Path source) throws IOException
	{
		return RunnerArtifactStore.retain(workRoot, source);
	}

	/**
	 * Collects runner-runtime copies that no retained evaluation record references.
	 *
	 * <p>
	 * A record marker is created only after the record contains an execution plan. Therefore every marked record
	 * names the runtime that a resumed evaluation needs. An unmarked record is not resumable and does not protect a
	 * runtime copy.
	 *
	 * @param json the mapper owned by the enclosing operation
	 * @param workRoot the CAT work root containing records and immutable runtime copies
	 * @param retainedUnreferencedBudget the newest unreferenced runtime copies to keep
	 * @param dryRun whether to report candidates without deleting them
	 * @return the collection decision
	 * @throws IOException if a marked record is incomplete or a referenced runtime is invalid
	 */
	public static RunnerArtifactStore.Collection collectRunnerArtifacts(JsonMapper json, Path workRoot,
		int retainedUnreferencedBudget, boolean dryRun) throws IOException
	{
		if (workRoot == null)
			throw new NullPointerException("workRoot must not be null");
		Path root = workRoot.toAbsolutePath().normalize();
		if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS))
			throw new IOException("CAT work root does not exist: " + root);

		Set<Path> references = referencedRunnerArtifacts(json, root);
		return RunnerArtifactStore.collect(root, references, retainedUnreferencedBudget, dryRun);
	}

	/**
	 * Collects shared prompt and context copies that no retained evaluation record references.
	 *
	 * @param json the mapper owned by the enclosing operation
	 * @param workRoot the CAT work root containing evaluation records and shared input copies
	 * @param retainedUnreferencedBudget the newest unreferenced input copies to keep
	 * @param dryRun whether to report candidates without deleting them
	 * @return the collection decision
	 * @throws IOException if a record or protected input copy is invalid
	 */
	public static FileStore.Collection collectInputArtifacts(JsonMapper json, Path workRoot,
		int retainedUnreferencedBudget, boolean dryRun) throws IOException
	{
		Path root = workRoot.toAbsolutePath().normalize();
		Path storeRoot = root.resolve(WorkDirectories.INPUT_ARTIFACTS);
		Set<Path> references = new HashSet<>();
		try (Stream<Path> paths = Files.walk(root))
		{
			for (Path marker : paths.filter(path -> path.getFileName().toString().equals(RECORD_MARKER)).
				filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)).toList())
			{
				ObjectNode record = record(json, marker);
				if (record == null)
					continue;
				Path run = recordPath(marker);
				for (String field : Set.of("prompt_artifact", "execution_context"))
				{
					JsonNode value = record.path(field);
					if (!value.isString() || value.asString().isBlank())
						throw new IOException("Evaluation record has no " + field + ": " + run);
					Path artifact = Path.of(value.asString()).toAbsolutePath().normalize();
					if (artifact.startsWith(storeRoot))
						references.add(artifact);
				}
			}
		}
		return FileStore.collect(root, references, retainedUnreferencedBudget, dryRun);
	}

	/**
	 * Collects content-addressed execution results that no retained evaluation attempt references.
	 *
	 * <p>
	 * A terminal handoff replaces each attempt's raw, validator, and deterministic result with an immutable copy. The
	 * attempt ledger is therefore the authoritative consumer list for this store; directory names and the lifetime of
	 * the native runner do not determine whether a result can be removed.
	 *
	 * @param json the mapper owned by the enclosing operation
	 * @param workRoot the CAT work root containing evaluation records and shared evidence copies
	 * @param retainedUnreferencedBudget the newest unreferenced evidence copies to keep
	 * @param dryRun whether to report candidates without deleting them
	 * @return the collection decision
	 * @throws IOException if a record, attempt ledger, or protected evidence copy is invalid
	 */
	public static FileStore.Collection collectExecutionEvidenceArtifacts(JsonMapper json, Path workRoot,
		int retainedUnreferencedBudget, boolean dryRun) throws IOException
	{
		Path root = workRoot.toAbsolutePath().normalize();
		Path storeRoot = root.resolve(WorkDirectories.EVIDENCE_ARTIFACTS);
		Set<Path> references = new HashSet<>();
		try (Stream<Path> paths = Files.walk(root))
		{
			for (Path marker : paths.filter(path -> path.getFileName().toString().equals(RECORD_MARKER)).
				filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)).toList())
			{
				ObjectNode record = record(json, marker);
				if (record == null)
					continue;
				Path run = recordPath(marker);
				Path ledger = Path.of(requiredText(record, "attempt_ledger", run)).toAbsolutePath().normalize();
				if (!Files.isRegularFile(ledger, LinkOption.NOFOLLOW_LINKS))
					throw new IOException("Evaluation record has no readable attempt ledger: " + run);
				try (BufferedReader reader = Files.newBufferedReader(ledger, StandardCharsets.UTF_8))
				{
					String line = reader.readLine();
					while (line != null)
					{
						if (!line.isBlank())
						{
							ObjectNode attempt = object(json.readTree(line), "evaluation attempt");
							for (String field : Set.of("raw_result", "validator_result", "deterministic_result"))
							{
								JsonNode value = attempt.path(field);
								if (value.isString() && !value.asString().isBlank())
								{
									Path artifact = Path.of(value.asString()).toAbsolutePath().normalize();
									if (artifact.startsWith(storeRoot))
										references.add(artifact);
								}
							}
						}
						line = reader.readLine();
					}
				}
			}
		}
		return FileStore.collectExecutionEvidence(root, references, retainedUnreferencedBudget,
			dryRun);
	}

	/**
	 * Collects shared suite copies that no retained evaluation record references.
	 *
	 * @param json the mapper owned by the enclosing operation
	 * @param workRoot the CAT work root containing evaluation records and shared suite copies
	 * @param retainedUnreferencedBudget the newest unreferenced suite copies to keep
	 * @param dryRun whether to report candidates without deleting them
	 * @return the collection decision
	 * @throws IOException if a record or protected suite copy is invalid
	 */
	public static SuiteStore.Collection collectSuiteArtifacts(JsonMapper json, Path workRoot,
		int retainedUnreferencedBudget, boolean dryRun) throws IOException
	{
		Path root = workRoot.toAbsolutePath().normalize();
		Path storeRoot = root.resolve(WorkDirectories.SUITE_ARTIFACTS);
		Set<Path> references = new HashSet<>();
		try (Stream<Path> paths = Files.walk(root))
		{
			for (Path marker : paths.filter(path -> path.getFileName().toString().equals(RECORD_MARKER)).
				filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)).toList())
			{
				ObjectNode record = record(json, marker);
				if (record == null)
					continue;
				Path run = recordPath(marker);
				JsonNode value = record.path("suite");
				if (!value.isString() || value.asString().isBlank())
					throw new IOException("Evaluation record has no suite: " + run);
				Path suite = Path.of(value.asString()).toAbsolutePath().normalize();
				if (suite.startsWith(storeRoot))
					references.add(suite);
			}
		}
		return SuiteStore.collect(root, references, retainedUnreferencedBudget, dryRun);
	}

	/**
	 * Reads the runner-runtime references from complete evaluation records.
	 *
	 * @param json the mapper owned by the enclosing operation
	 * @param workRoot the root containing evaluation records
	 * @return absolute immutable runtime paths protected from collection
	 * @throws IOException if a marked record has no valid execution plan or runtime reference
	 */
	private static Set<Path> referencedRunnerArtifacts(JsonMapper json, Path workRoot) throws IOException
	{
		Set<Path> references = new HashSet<>();
		try (Stream<Path> paths = Files.walk(workRoot))
		{
			for (Path marker : paths.filter(path -> path.getFileName().toString().equals(RECORD_MARKER)).
				filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)).toList())
			{
				ObjectNode run = record(json, marker);
				if (run == null)
					continue;
				JsonNode planValue = run.path("execution_plan");
				if (!planValue.isString() || planValue.asString().isBlank())
					continue;
				Path plan = Path.of(planValue.asString()).toAbsolutePath().normalize();
				if (!Files.isRegularFile(plan, LinkOption.NOFOLLOW_LINKS))
					throw new IOException("Evaluation execution plan is unavailable: " + plan);
				ObjectNode executionPlan = object(json, plan, "evaluation execution plan");
				JsonNode identityNode = executionPlan.path("identity");
				if (!(identityNode instanceof ObjectNode identity))
					continue;
				JsonNode runnerArtifact = identity.path("runner_artifact");
				if (!runnerArtifact.isString() || runnerArtifact.asString().isBlank())
					continue;
				references.add(Path.of(runnerArtifact.asString()).toAbsolutePath().normalize());
			}
		}
		return references;
	}

	/**
	 * Reads the durable record named by an ownership marker.
	 *
	 * <p>
	 * A marker whose named record was removed by an earlier interrupted cleanup cannot resume or consume an artifact.
	 * It therefore contributes no protected reference. Malformed markers still fail collection because their intended
	 * ownership cannot be determined safely.
	 *
	 * @param json the mapper owned by the enclosing operation
	 * @param marker the record-ownership marker
	 * @return the named record, or {@code null} when the named record no longer exists
	 * @throws IOException if the marker is malformed or its existing record is invalid
	 */
	private static ObjectNode record(JsonMapper json, Path marker) throws IOException
	{
		Path run = recordPath(marker);
		if (!Files.isRegularFile(run, LinkOption.NOFOLLOW_LINKS))
			return null;
		return object(json, run, "evaluation record");
	}

	/**
	 * Reads and validates the run path named by one record-ownership marker.
	 *
	 * @param marker the record-ownership marker
	 * @return the normalized named run path
	 * @throws IOException if the marker is blank
	 */
	private static Path recordPath(Path marker) throws IOException
	{
		String value = Files.readString(marker, StandardCharsets.UTF_8).trim();
		if (value.isBlank())
			throw new IOException("Evaluation record marker is blank: " + marker);
		return Path.of(value).toAbsolutePath().normalize();
	}

	/**
	 * Reads an object-valued JSON document.
	 *
	 * @param json the mapper owned by the enclosing operation
	 * @param path the JSON document
	 * @param description the reader-facing document description
	 * @return the parsed object
	 * @throws IOException if the document cannot be read or is not an object
	 */
	private static ObjectNode object(JsonMapper json, Path path, String description) throws IOException
	{
		JsonNode value = json.readTree(Files.readString(path, StandardCharsets.UTF_8));
		if (value instanceof ObjectNode result)
			return result;
		throw new IOException("Expected " + description + " to be a JSON object: " + path);
	}

	/**
	 * Reads one required nonblank string field.
	 *
	 * @param object the JSON object to inspect
	 * @param field the required field name
	 * @param source the source document used in a failure message
	 * @return the field's nonblank text
	 * @throws IOException if the field is missing or blank
	 */
	private static String requiredText(ObjectNode object, String field, Path source) throws IOException
	{
		JsonNode value = object.path(field);
		if (value.isString() && !value.asString().isBlank())
			return value.asString();
		throw new IOException("Evaluation record has no " + field + ": " + source);
	}

	/**
	 * Interprets an object-valued JSON node.
	 *
	 * @param value the parsed JSON value
	 * @param description the reader-facing description
	 * @return the object value
	 * @throws IOException if the value is not an object
	 */
	private static ObjectNode object(JsonNode value, String description) throws IOException
	{
		if (value instanceof ObjectNode result)
			return result;
		throw new IOException("Expected " + description + " to be a JSON object");
	}
}
