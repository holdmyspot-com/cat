package io.github.cowwoc.cat.common.evaluation;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import io.github.cowwoc.cat.common.secrets.EvaluatorSecrets;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Replaces deleted terminal evidence with the candidate-state summary needed by later workflow transitions.
 */
public final class Tombstone
{
	/**
	 * Performs coordinator-owned persistence, locking, and record-rewrite operations.
	 */
	public interface Access
	{
		/**
		 * Runs one operation while the specified record is locked.
		 */
		@FunctionalInterface
		interface LockedOperation
		{
			/**
			 * Performs the locked operation.
			 *
			 * @throws IOException if the operation cannot complete
			 */
			void execute() throws IOException;
		}

		/**
		 * Reads a JSON record as an object.
		 *
		 * @param source the record location
		 * @param description the record role in malformed-content diagnostics
		 * @return the parsed record
		 * @throws IOException if the record cannot be read or is malformed
		 */
		ObjectNode readObject(Path source, String description) throws IOException;

		/**
		 * Reads a required text field.
		 *
		 * @param record the parsed record
		 * @param field the required field name
		 * @param source the record location used in diagnostics
		 * @return the non-blank field value
		 * @throws IOException if the field is unavailable or malformed
		 */
		String text(ObjectNode record, String field, Path source) throws IOException;

		/**
		 * Validates a JSON value as an object.
		 *
		 * @param value the value to validate
		 * @param description the value role in malformed-content diagnostics
		 * @return the object value
		 * @throws IOException if the value is not an object
		 */
		ObjectNode object(JsonNode value, String description) throws IOException;

		/**
		 * Validates a JSON value as an array.
		 *
		 * @param value the value to validate
		 * @param description the value role in malformed-content diagnostics
		 * @param source the record location used in diagnostics
		 * @return the array value
		 * @throws IOException if the value is not an array
		 */
		ArrayNode array(JsonNode value, String description, Path source) throws IOException;

		/**
		 * Hashes one persisted artifact.
		 *
		 * @param artifact the artifact to hash
		 * @return the artifact SHA-256
		 * @throws IOException if hashing fails
		 */
		String sha256(Path artifact) throws IOException;

		/**
		 * Hashes one text value.
		 *
		 * @param value the text to hash
		 * @return the text SHA-256
		 * @throws IOException if hashing fails
		 */
		String sha256Text(String value) throws IOException;

		/**
		 * Atomically persists one record.
		 *
		 * @param destination the record location
		 * @param record the replacement record
		 * @throws IOException if the record cannot be written
		 */
		void write(Path destination, ObjectNode record) throws IOException;

		/**
		 * Runs an operation while preserving every concurrent candidate-state update.
		 *
		 * @param recordPath the record to lock
		 * @param operation the update to perform while locked
		 * @throws IOException if the lock or update cannot complete
		 */
		void withLock(Path recordPath, LockedOperation operation) throws IOException;

		/**
		 * Replaces exact persisted references to a deleted source path.
		 *
		 * @param record the candidate-state record to update
		 * @param source the deleted record path
		 * @param replacement the tombstone path
		 */
		void rewriteExactPath(JsonNode record, Path source, Path replacement);
	}

	private static volatile Access access;

	/**
	 * Prevents construction of this static evidence owner.
	 */
	private Tombstone()
	{
	}

	/**
	 * Registers the coordinator-owned record operations.
	 *
	 * @param access the record operations
	 */
	public static void configure(Access access)
	{
		Tombstone.access = java.util.Objects.requireNonNull(access, "access may not be null");
	}

	/**
	 * Replaces a terminal run with a compact summary when its candidate state must survive evidence deletion.
	 *
	 * @param json the mapper that creates the tombstone summary
	 * @param runPath the terminal record about to be deleted
	 * @param recordRoot the evaluator-owned directory that contains the terminal record
	 * @return the tombstone path, or {@code null} when the record has no candidate-state ledger
	 * @throws IOException if the candidate state cannot safely outlive deleted evidence
	 */
	public static Path replace(JsonMapper json, Path runPath, Path recordRoot)
		throws IOException
	{
		Path normalizedRun = runPath.toAbsolutePath().normalize();
		Path normalizedRoot = recordRoot.toAbsolutePath().normalize();
		ObjectNode run = access().readObject(normalizedRun, "terminal evaluation run");
		if (!run.has("failure_signature_state"))
			return null;

		Path statePath = Path.of(access().text(run, "failure_signature_state", normalizedRun)).toAbsolutePath().
			normalize();
		if (!Files.isRegularFile(statePath, NOFOLLOW_LINKS))
			throw new IOException("Deleted evaluation names an unavailable candidate state: " + statePath);
		if (statePath.startsWith(normalizedRoot))
			throw new IOException("Candidate state must outlive the evaluation evidence it references: " + statePath);
		Path stateParent = statePath.getParent();
		if (stateParent == null)
			throw new IOException("Candidate state has no parent directory: " + statePath);

		Path tombstoneDirectory = stateParent.resolve(statePath.getFileName() + ".tombstones");
		Files.createDirectories(tombstoneDirectory);
		Path tombstone = tombstoneDirectory.resolve(normalizedRun.getFileName() + "-" +
			access().sha256Text(normalizedRun.toString()).substring(0, 12) + ".json");
		if (Files.exists(tombstone, NOFOLLOW_LINKS))
			throw new IOException("Evaluation tombstone already exists: " + tombstone);

		access().write(tombstone, summary(json, run, normalizedRun, statePath));
		access().withLock(statePath, () -> updateState(statePath, normalizedRun, tombstone));
		return tombstone;
	}

	/**
	 * Updates the candidate-state reference while its record is locked.
	 *
	 * @param statePath the candidate-state ledger
	 * @param runPath the deleted terminal record
	 * @param tombstone the durable replacement summary
	 * @throws IOException if the state cannot be read or persisted
	 */
	private static void updateState(Path statePath, Path runPath, Path tombstone) throws IOException
	{
		ObjectNode state = access().readObject(statePath, "candidate state");
		access().rewriteExactPath(state, runPath, tombstone);
		ArrayNode tombstones;
		if (state.path("tombstones").isMissingNode())
			tombstones = state.putArray("tombstones");
		else
			tombstones = access().array(state.path("tombstones"), "candidate tombstones", statePath);
		ObjectNode entry = tombstones.addObject();
		entry.put("source_run", runPath.toString());
		entry.put("record", tombstone.toString());
		entry.put("record_sha256", access().sha256(tombstone));
		entry.put("deleted_at_epoch_millis", System.currentTimeMillis());
		access().write(statePath, state);
	}

	/**
	 * Builds the immutable candidate identity and terminal decision information that outlives deleted evidence.
	 *
	 * @param json the mapper that creates the replacement summary
	 * @param run the terminal evaluation record
	 * @param runPath the deleted terminal-record location
	 * @param statePath the candidate-state ledger that refers to the record
	 * @return the compact replacement summary
	 * @throws IOException if a retained identity is malformed
	 */
	private static ObjectNode summary(JsonMapper json, ObjectNode run, Path runPath,
		Path statePath) throws IOException
	{
		ObjectNode result = json.createObjectNode();
		result.put("purpose", "prompt-evaluation-tombstone");
		result.put("source_run", runPath.toString());
		result.put("candidate_state", statePath.toString());
		for (String field : List.of("suite_sha256", "prompt_sha256", "evaluation_epoch_sha256",
			"candidate_identity_sha256", "assembled_prompt_route_sha256", "execution_context_sha256"))
			if (run.has(field))
				result.put(field, access().text(run, field, runPath));
		copyObject(run, "decision", result);
		copyObject(run, "regression_check", result);
		copyObject(run, "routing_matrix", result);
		result.put("deleted_at_epoch_millis", System.currentTimeMillis());
		return result;
	}

	/**
	 * Copies an optional structured terminal result into the immutable summary.
	 *
	 * @param source the terminal evaluation record
	 * @param field the optional structured result name
	 * @param destination the replacement summary
	 * @throws IOException if a present result is malformed
	 */
	private static void copyObject(ObjectNode source, String field, ObjectNode destination) throws IOException
	{
		if (source.path(field).isObject())
			destination.set(field, access().object(source.path(field), field).deepCopy());
	}

	/**
	 * Returns the configured coordinator operations.
	 *
	 * @return the coordinator operations
	 */
	private static Access access()
	{
		EvaluatorSecrets.access().initialize();
		Access result = access;
		if (result == null)
			throw new AssertionError("PromptEvaluation did not configure tombstone access");
		return result;
	}
}
