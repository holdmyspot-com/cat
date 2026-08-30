package io.github.cowwoc.cat.common.evaluation;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import io.github.cowwoc.cat.common.runner.HarnessEvaluationRunner;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Owns the bounded non-certifying comparison of a changed executor profile against the target validator profile.
 */
public final class CapabilityDiagnostic
{
	private static final int TRIAL_COUNT = 3;

	private final Access access;

	/**
	 * Creates a bounded capability-diagnostic lifecycle owner.
	 *
	 * @param access the evaluator operations that retain diagnostic evidence
	 */
	public CapabilityDiagnostic(Access access)
	{
		this.access = access;
	}

	/**
	 * Returns the fixed number of matched trials that every capability diagnostic retains.
	 *
	 * @return the fixed non-certifying trial count
	 */
	public static int trialCount()
	{
		return TRIAL_COUNT;
	}

	/**
	 * Verifies a generated diagnostic suite can isolate one capability question.
	 *
	 * @param suitePath the generated diagnostic suite
	 * @throws IOException if the suite is not a one-case direct-application or routing-only diagnostic
	 */
	public void validateSuite(Path suitePath) throws IOException
	{
		SuiteContract.validate(suitePath);
		ObjectNode suite = SuiteContract.read(suitePath);
		if (SuitePurpose.parse(access.text(suite, "purpose", suitePath)) != SuitePurpose.DIAGNOSTIC)
			throw new IOException("A capability diagnostic requires a generated diagnostic suite: " + suitePath);
		if (suite.path("source_root").isMissingNode())
		{
			throw new IOException("A capability diagnostic requires a diagnostic suite recreated from certification: " +
				suitePath);
		}
		SuiteContract.root(suitePath);
		DiagnosticKind kind = SuiteContract.diagnosticKind(suite, suitePath, SuitePurpose.DIAGNOSTIC);
		if (kind != DiagnosticKind.DIRECT_APPLICATION && kind != DiagnosticKind.ROUTING_ONLY)
		{
			throw new IOException("A capability diagnostic requires a direct-application or routing-only suite: " +
				suitePath);
		}
	}

	/**
	 * Reserves a diagnostic record for one changed executor profile before work begins.
	 *
	 * @param runPath the prepared diagnostic record
	 * @param targetProfile the profile ordinary readers receive
	 * @param diagnosticProfile the changed executor-only profile
	 * @throws IOException if the record is not a diagnostic, is already reserved, or does not change the executor profile
	 */
	public void start(Path runPath, Profile targetProfile, Profile diagnosticProfile) throws IOException
	{
		if (diagnosticProfile.equals(targetProfile))
		{
			throw new IOException("Profile-capability diagnostic must change the executor model or effort from the target " +
				"profile: " + targetProfile.label());
		}
		Path normalizedRunPath = runPath.toAbsolutePath().normalize();
		access.withRunLock(normalizedRunPath, () ->
		{
			ObjectNode run = access.readRun(normalizedRunPath);
			if (SuitePurpose.parse(access.text(run, "purpose", normalizedRunPath)) != SuitePurpose.DIAGNOSTIC)
				throw new IOException("A capability diagnostic requires a diagnostic run: " + normalizedRunPath);
			if (run.has("mode"))
				throw new IOException("Diagnostic record already has an execution mode: " + normalizedRunPath);
			run.put("mode", "profile_capability_diagnostic");
			ObjectNode diagnostic = run.putObject("profile_capability_diagnostic");
			diagnostic.put("classification", "non_certifying_profile_comparison");
			diagnostic.put("trial_count", TRIAL_COUNT);
			diagnostic.put("target_executor_profile", targetProfile.label());
			diagnostic.put("diagnostic_executor_profile", diagnosticProfile.label());
			diagnostic.put("validator_profile", "independent-" + targetProfile.label());
			access.writeRun(normalizedRunPath, run);
			return null;
		});
	}

	/**
	 * Runs and records the fixed profile comparison while the supplied workspace remains owned.
	 *
	 * @param runPath the reserved diagnostic record
	 * @param workspaceRoot the initially absent diagnostic workspace
	 * @param planPath the bound execution plan
	 * @param maximumConcurrency the shared worker capacity
	 * @param runner the runner retaining executor and validator evidence
	 * @return the terminal non-certifying diagnostic summary
	 * @throws IOException if the diagnostic cannot retain terminal evidence
	 */
	public ObjectNode run(Path runPath, Path workspaceRoot, Path planPath, int maximumConcurrency,
		HarnessEvaluationRunner runner) throws IOException
	{
		Path normalizedRunPath = runPath.toAbsolutePath().normalize();
		Path normalizedWorkspaceRoot = workspaceRoot.toAbsolutePath().normalize();
		Path normalizedPlanPath = planPath.toAbsolutePath().normalize();
		access.validateMaximumConcurrency(maximumConcurrency);
		access.bindExecutionPlan(normalizedRunPath, normalizedPlanPath);
		access.prepareOwnedWorkspace(normalizedRunPath, normalizedWorkspaceRoot);
		return access.runOwned(normalizedRunPath, normalizedWorkspaceRoot, "executing_capability_diagnostic", () ->
			execute(normalizedRunPath, normalizedWorkspaceRoot, normalizedPlanPath, maximumConcurrency, runner));
	}

	/**
	 * Executes the reserved diagnostic and publishes its result exactly once.
	 *
	 * @param runPath the reserved diagnostic record
	 * @param workspaceRoot the owned diagnostic workspace
	 * @param planPath the bound execution plan
	 * @param maximumConcurrency the shared worker capacity
	 * @param runner the runner retaining executor and validator evidence
	 * @return the terminal non-certifying diagnostic summary
	 * @throws IOException if the record is invalid, already complete, or cannot retain its evidence
	 */
	ObjectNode execute(Path runPath, Path workspaceRoot, Path planPath, int maximumConcurrency,
		HarnessEvaluationRunner runner) throws IOException
	{
		ObjectNode run = access.readRun(runPath);
		if (!run.path("mode").asString().equals("profile_capability_diagnostic"))
			throw new IOException("Run is not reserved for a profile-capability diagnostic: " + runPath);
		if (run.path("profile_capability_diagnostic").path("result").isObject())
			throw new IOException("Profile-capability diagnostic already completed: " + runPath);
		Path suitePath = access.path(access.text(run, "suite", runPath));
		validateSuite(suitePath);
		ObjectNode suite = SuiteContract.read(suitePath);
		ArrayNode cases = access.array(suite.path("cases"), "cases", suitePath);
		ObjectNode testCase = access.object(cases.get(0), "test case");
		String caseId = access.text(testCase, "id", suitePath);
		List<Integer> trials = access.claimTrials(runPath, TRIAL_COUNT);
		access.executeAndRecord(runPath, suitePath, testCase, trials, workspaceRoot, planPath, maximumConcurrency, runner);
		ObjectNode result = result(runPath, caseId, workspaceRoot, trials);
		access.withRunLock(runPath, () ->
		{
			ObjectNode record = access.readRun(runPath);
			access.object(record.path("profile_capability_diagnostic"), "profile capability diagnostic").set("result",
				result.deepCopy());
			access.writeRun(runPath, record);
			return null;
		});
		return result;
	}

	/**
	 * Summarizes bounded profile evidence without treating it as a compliance measurement.
	 *
	 * @param runPath the completed diagnostic record
	 * @param caseId the diagnostic testcase identifier
	 * @param workspaceRoot the retained diagnostic workspace
	 * @param trials the claimed fixed trial numbers
	 * @return the count-only terminal diagnostic summary
	 * @throws IOException if retained diagnostic inputs cannot be read
	 */
	ObjectNode result(Path runPath, String caseId, Path workspaceRoot, List<Integer> trials) throws IOException
	{
		ObjectNode run = access.readRun(runPath);
		ObjectNode counts = access.outcomeCounts(run, runPath);
		ObjectNode diagnostic = access.object(run.path("profile_capability_diagnostic"), "profile capability diagnostic");
		Path suitePath = access.path(access.text(run, "suite", runPath));
		ObjectNode suite = SuiteContract.read(suitePath);
		ObjectNode result = access.object();
		result.put("classification", "non_certifying_profile_comparison");
		result.put("diagnostic_kind", access.text(suite, "diagnostic_kind", suitePath));
		result.put("case_id", caseId);
		result.put("requested_trials", trials.size());
		result.put("completed_trials", counts.path("passed").asInt() + counts.path("failed").asInt());
		result.put("passed_trials", counts.path("passed").asInt());
		result.put("failed_trials", counts.path("failed").asInt());
		result.put("infrastructure_failed", access.hasInfrastructureFailure(runPath));
		result.put("target_executor_profile", access.text(diagnostic, "target_executor_profile", runPath));
		result.put("diagnostic_executor_profile", access.text(diagnostic, "diagnostic_executor_profile", runPath));
		result.put("validator_profile", access.text(diagnostic, "validator_profile", runPath));
		result.put("workspace", workspaceRoot.toString());
		result.put("completed_at_epoch_millis", System.currentTimeMillis());
		return result;
	}

	/**
	 * Supplies retained-record, workspace, and scheduler operations to a bounded capability diagnostic.
	 */
	public interface Access
	{
		/**
		 * Validates the shared worker capacity.
		 *
		 * @param maximumConcurrency the requested capacity
		 * @throws IOException if the capacity is invalid
		 */
		void validateMaximumConcurrency(int maximumConcurrency) throws IOException;

		/**
		 * Binds the exact plan to the retained record.
		 *
		 * @param runPath the diagnostic record
		 * @param planPath the execution plan
		 * @throws IOException if the plan cannot be bound
		 */
		void bindExecutionPlan(Path runPath, Path planPath) throws IOException;

		/**
		 * Prepares the owned diagnostic workspace.
		 *
		 * @param runPath the diagnostic record
		 * @param workspaceRoot the initially absent workspace
		 * @throws IOException if ownership cannot be established
		 */
		void prepareOwnedWorkspace(Path runPath, Path workspaceRoot) throws IOException;

		/**
		 * Runs an operation while publishing workspace ownership and terminal state.
		 *
		 * @param runPath the diagnostic record
		 * @param workspaceRoot the owned workspace
		 * @param phase the active lifecycle phase
		 * @param operation the bounded diagnostic work
		 * @return the retained operation result
		 * @throws IOException if ownership or work fails
		 */
		ObjectNode runOwned(Path runPath, Path workspaceRoot, String phase, Operation operation) throws IOException;

		/**
		 * Reads a retained diagnostic record.
		 *
		 * @param runPath the diagnostic record
		 * @return the mutable record content
		 * @throws IOException if the record cannot be read
		 */
		ObjectNode readRun(Path runPath) throws IOException;

		/**
		 * Publishes one retained diagnostic record.
		 *
		 * @param runPath the diagnostic record
		 * @param run the complete replacement content
		 * @throws IOException if the record cannot be written
		 */
		void writeRun(Path runPath, ObjectNode run) throws IOException;

		/**
		 * Runs one record mutation while holding its durable lock.
		 *
		 * @param runPath the record to lock
		 * @param operation the mutation to run
		 * @throws IOException if the lock or mutation fails
		 */
		void withRunLock(Path runPath, Operation operation) throws IOException;

		/**
		 * Reads a required text field.
		 *
		 * @param object the containing JSON object
		 * @param field the required field name
		 * @param source the retained source used in diagnostics
		 * @return the required text
		 * @throws IOException if the field is invalid
		 */
		String text(ObjectNode object, String field, Path source) throws IOException;

		/**
		 * Converts one retained path value to a normalized path.
		 *
		 * @param value the retained path text
		 * @return the normalized path
		 */
		Path path(String value);

		/**
		 * Validates a JSON object.
		 *
		 * @param value the value to validate
		 * @param description the diagnostic description
		 * @return the validated object
		 * @throws IOException if the value is not an object
		 */
		ObjectNode object(JsonNode value, String description) throws IOException;

		/**
		 * Validates a JSON array.
		 *
		 * @param value the value to validate
		 * @param description the diagnostic description
		 * @param source the retained source used in diagnostics
		 * @return the validated array
		 * @throws IOException if the value is not an array
		 */
		ArrayNode array(JsonNode value, String description, Path source) throws IOException;

		/**
		 * Creates one diagnostic result object.
		 *
		 * @return a new mutable result object
		 */
		ObjectNode object();

		/**
		 * Claims exactly the requested non-certifying diagnostic trials.
		 *
		 * @param runPath the diagnostic record
		 * @param count the fixed number of trials
		 * @return the claimed trial numbers
		 * @throws IOException if the trials cannot be claimed
		 */
		List<Integer> claimTrials(Path runPath, int count) throws IOException;

		/**
		 * Runs and validates one claimed testcase batch.
		 *
		 * @param runPath the diagnostic record
		 * @param suitePath the immutable diagnostic suite
		 * @param testCase the sole selected testcase
		 * @param trials the claimed trial numbers
		 * @param workspaceRoot the owned workspace
		 * @param planPath the bound execution plan
		 * @param maximumConcurrency the shared worker capacity
		 * @param runner the runner retaining executor and validator evidence
		 * @throws IOException if executor or validator evidence cannot be retained
		 */
		void executeAndRecord(Path runPath, Path suitePath, ObjectNode testCase, List<Integer> trials,
			Path workspaceRoot, Path planPath, int maximumConcurrency, HarnessEvaluationRunner runner)
			throws IOException;

		/**
		 * Counts retained terminal testcase outcomes.
		 *
		 * @param run the diagnostic record
		 * @param runPath the diagnostic record path
		 * @return counts indexed by terminal outcome status
		 * @throws IOException if retained attempts cannot be counted
		 */
		ObjectNode outcomeCounts(ObjectNode run, Path runPath) throws IOException;

		/**
		 * Indicates whether retained evidence contains an infrastructure failure.
		 *
		 * @param runPath the diagnostic record
		 * @return whether retained evidence blocks an ordinary result
		 * @throws IOException if evidence cannot be inspected
		 */
		boolean hasInfrastructureFailure(Path runPath) throws IOException;
	}

	/**
	 * Holds one resolved profile label retained by a capability diagnostic.
	 *
	 * @param label the nonblank owner-model-effort identity resolved by the workflow facade
	 */
	public record Profile(String label)
	{
		/**
		 * Validates a resolved profile label.
		 *
		 * @param label the nonblank resolved profile identity
		 */
		public Profile
		{
			if (label == null || label.isBlank())
				throw new IllegalArgumentException("label must not be blank");
		}
	}

	/**
	 * Runs one I/O operation under a diagnostic lifecycle boundary.
	 */
	@FunctionalInterface
	public interface Operation
	{
		/**
		 * Runs the operation.
		 *
		 * @return the retained result
		 * @throws IOException if the operation cannot retain its result
		 */
		ObjectNode run() throws IOException;
	}
}
