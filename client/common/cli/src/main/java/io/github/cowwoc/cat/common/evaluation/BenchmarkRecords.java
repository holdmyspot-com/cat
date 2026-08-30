package io.github.cowwoc.cat.common.evaluation;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Owns preparation and resumption of the retained records used by paired benchmarks.
 */
public final class BenchmarkRecords
{
	private final Access access;

	/**
	 * Creates the paired-benchmark record owner.
	 *
	 * @param access the evaluator persistence and preparation operations used by benchmark records
	 */
	public BenchmarkRecords(Access access)
	{
		this.access = access;
	}

	/**
	 * Reconstructs one retained paired-benchmark record after its original process ended.
	 *
	 * @param runPath the retained candidate record
	 * @param candidate the immutable bundle supplied by the resuming operation
	 * @param trialCount the fixed evidence budget the record must retain
	 * @return the fields required to resume the retained record
	 * @throws IOException if the retained record differs from the requested benchmark
	 */
	public ObjectNode existing(Path runPath, CandidateBundle.Candidate candidate, int trialCount) throws IOException
	{
		if (!Files.isRegularFile(runPath, NOFOLLOW_LINKS))
			throw new IOException("Paired benchmark is missing its retained candidate record: " + runPath);
		ObjectNode run = access.object(access.read(runPath), "retained paired benchmark record");
		access.ensureFixedBenchmarkRun(runPath);
		if (run.path("fixed_trial_budget").asInt(-1) != trialCount)
			throw new IOException("Paired benchmark trial count differs from its retained record: " + runPath);
		if (!access.text(run, "candidate_bundle_sha256", runPath).equals(candidate.sha256()))
			throw new IOException("Paired benchmark candidate bundle differs from its retained record: " + runPath);
		access.validateRunInputs(run, runPath);
		Path workspace = access.path(access.text(run, "workspace", runPath));
		Path plan = access.path(access.text(run, "execution_plan", runPath));
		if (!Files.isRegularFile(plan, NOFOLLOW_LINKS))
			throw new IOException("Paired benchmark is missing its retained execution plan: " + runPath);
		ObjectNode prepared = access.object();
		prepared.put("run", runPath.toString());
		prepared.put("workspace", workspace.toString());
		prepared.put("execution_plan", plan.toString());
		return prepared;
	}

	/**
	 * Prepares one candidate record that must retain every trial in a paired comparison.
	 *
	 * @param temporaryRoot the CAT work temporary root
	 * @param suitePath the durable scenario suite
	 * @param candidate the immutable candidate bundle
	 * @param harness the selected harness
	 * @param contextPath the independent-validator contract
	 * @param runner the selected runner
	 * @param assembly the shared assembled runner evidence
	 * @param retainedDirectory the initially absent candidate evidence directory
	 * @param trialCount the fixed evidence budget
	 * @return the prepared paired-benchmark record
	 * @throws IOException if preparation cannot publish a usable record
	 */
	public ObjectNode prepare(Path temporaryRoot, Path suitePath, CandidateBundle.Candidate candidate, String harness,
		Path contextPath, Path runner, ObjectNode assembly, Path retainedDirectory, int trialCount) throws IOException
	{
		ObjectNode prepared = access.prepareCandidate(temporaryRoot, suitePath, candidate, harness, contextPath, runner,
			assembly, retainedDirectory);
		Path run = access.path(prepared.path("run").asString());
		access.withLock(run, () ->
		{
			ObjectNode record = access.object(access.read(run), "prepared paired benchmark record");
			record.put("mode", "benchmark");
			record.put("fixed_trial_budget", trialCount);
			access.write(run, record);
		});
		access.bindExecutionPlan(run, access.path(prepared.path("execution_plan").asString()));
		return prepared;
	}

	/**
	 * Supplies the evaluator operations needed to prepare and recover paired benchmark records.
	 */
	public interface Access
	{
		/**
		 * Reads one retained evaluator record.
		 *
		 * @param runPath the record path
		 * @return the parsed record
		 * @throws IOException if the record cannot be read
		 */
		JsonNode read(Path runPath) throws IOException;

		/**
		 * Validates a JSON object value.
		 *
		 * @param value the candidate value
		 * @param description the value's diagnostic description
		 * @return the validated object
		 * @throws IOException if the value is not an object
		 */
		ObjectNode object(JsonNode value, String description) throws IOException;

		/**
		 * Creates one mutable evaluator record object.
		 *
		 * @return a new object
		 */
		ObjectNode object();

		/**
		 * Returns a required text field.
		 *
		 * @param record the record containing the field
		 * @param field the required field name
		 * @param source the record path for diagnostics
		 * @return the field value
		 * @throws IOException if the field is absent or blank
		 */
		String text(ObjectNode record, String field, Path source) throws IOException;

		/**
		 * Validates immutable evaluator inputs before a record resumes.
		 *
		 * @param run the retained record
		 * @param runPath the retained record path
		 * @throws IOException if an immutable input changed
		 */
		void validateRunInputs(ObjectNode run, Path runPath) throws IOException;

		/**
		 * Converts a stored path string to its normalized path.
		 *
		 * @param value the stored path
		 * @return the normalized path
		 */
		Path path(String value);

		/**
		 * Validates that a record is a fixed-budget paired benchmark.
		 *
		 * @param runPath the record path
		 * @throws IOException if the record is not a paired benchmark
		 */
		void ensureFixedBenchmarkRun(Path runPath) throws IOException;

		/**
		 * Creates a candidate evaluation record.
		 *
		 * @param temporaryRoot the CAT work temporary root
		 * @param suitePath the durable scenario suite
		 * @param candidate the immutable candidate bundle
		 * @param harness the selected harness
		 * @param contextPath the validator contract
		 * @param runner the selected runner
		 * @param assembly the shared assembled runner evidence
		 * @param retainedDirectory the candidate evidence directory
		 * @return the prepared record fields
		 * @throws IOException if preparation fails
		 */
		ObjectNode prepareCandidate(Path temporaryRoot, Path suitePath, CandidateBundle.Candidate candidate,
			String harness, Path contextPath, Path runner, ObjectNode assembly, Path retainedDirectory) throws IOException;

		/**
		 * Runs an operation while holding the record lock.
		 *
		 * @param runPath the record whose lock is required
		 * @param operation the record update
		 * @throws IOException if the lock or update fails
		 */
		void withLock(Path runPath, IoOperation operation) throws IOException;

		/**
		 * Atomically writes one retained evaluator record.
		 *
		 * @param runPath the record path
		 * @param record the record content
		 * @throws IOException if persistence fails
		 */
		void write(Path runPath, ObjectNode record) throws IOException;

		/**
		 * Binds a record to its immutable execution plan.
		 *
		 * @param runPath the record path
		 * @param planPath the execution plan
		 * @throws IOException if the plan cannot be bound
		 */
		void bindExecutionPlan(Path runPath, Path planPath) throws IOException;
	}

	/**
	 * Runs one locked paired-benchmark record update.
	 */
	@FunctionalInterface
	public interface IoOperation
	{
		/**
		 * Updates the currently locked record.
		 *
		 * @throws IOException if the update fails
		 */
		void run() throws IOException;
	}
}
