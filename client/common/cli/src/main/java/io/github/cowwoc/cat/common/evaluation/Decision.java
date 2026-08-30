package io.github.cowwoc.cat.common.evaluation;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Produces the persisted certification decision from immutable whole-suite outcomes and established evidence.
 */
public final class Decision
{
	/**
	 * Describes one completed whole-suite outcome.
	 *
	 * @param passed whether every testcase passed in the completed trial
	 */
	public record Outcome(boolean passed)
	{
	}

	/**
	 * Supplies the immutable values from which one certification decision is derived.
	 *
	 * @param parameters the fixed certification policy
	 * @param outcomes completed whole-suite outcomes
	 * @param infrastructureFailure whether retained execution evidence is invalid
	 * @param routingDeliveryFailure whether retained routing evidence establishes a delivery failure
	 * @param fixedBenchmarkInProgress whether a fixed-budget benchmark must remain running
	 */
	public record Input(Statistics.Parameters parameters, List<Outcome> outcomes, boolean infrastructureFailure,
		boolean routingDeliveryFailure, boolean fixedBenchmarkInProgress)
	{
	}

	/**
	 * Supplies durable-record operations required to project one certification decision.
	 */
	public interface Access
	{
		/**
		 * Runs one decision operation while the record is exclusively locked.
		 *
		 * @param runPath the persistent record
		 * @param operation the locked operation
		 * @return the operation result
		 * @throws IOException if locking or the operation fails
		 */
		ObjectNode withLock(Path runPath, LockedOperation operation) throws IOException;

		/**
		 * Reads the current evaluation record.
		 *
		 * @param runPath the persistent record
		 * @return the parsed record
		 * @throws IOException if the record cannot be read
		 */
		ObjectNode read(Path runPath) throws IOException;

		/**
		 * Writes a complete replacement evaluation record.
		 *
		 * @param runPath the persistent record
		 * @param run the replacement content
		 * @throws IOException if the record cannot be written
		 */
		void write(Path runPath, ObjectNode run) throws IOException;

		/**
		 * Validates immutable evaluation inputs.
		 *
		 * @param run the current record
		 * @param runPath the persistent record
		 * @throws IOException if an input changed
		 */
		void validateInputs(ObjectNode run, Path runPath) throws IOException;

		/**
		 * Validates retained execution evidence.
		 *
		 * @param run the current record
		 * @param runPath the persistent record
		 * @throws IOException if evidence is invalid
		 */
		void validateEvidence(ObjectNode run, Path runPath) throws IOException;

		/**
		 * Builds the immutable statistical input from retained run evidence.
		 *
		 * @param run the current record
		 * @param runPath the persistent record
		 * @return the immutable decision input
		 * @throws IOException if retained evidence is invalid
		 */
		Input input(ObjectNode run, Path runPath) throws IOException;

		/**
		 * Indicates whether the evaluator ended before recording a terminal decision.
		 *
		 * @param runPath the persistent record
		 * @return whether interruption evidence exists
		 */
		boolean interrupted(Path runPath);

		/**
		 * Determines whether fixed-budget benchmark policy requires another trial.
		 *
		 * @param run the current record
		 * @param completeTrials the completed trial count
		 * @param infrastructureFailure whether execution evidence failed
		 * @return whether benchmark work remains
		 * @throws IOException if benchmark state is invalid
		 */
		boolean fixedBenchmarkInProgress(ObjectNode run, int completeTrials, boolean infrastructureFailure)
			throws IOException;
	}

	/**
	 * Performs one result-producing operation under the evaluation-record lock.
	 */
	@FunctionalInterface
	public interface LockedOperation
	{
		/**
		 * Runs the locked decision operation.
		 *
		 * @return the operation result
		 * @throws IOException if the operation fails
		 */
		ObjectNode run() throws IOException;
	}

	/**
	 * Prevents construction of this static decision owner.
	 */
	private Decision()
	{
	}

	/**
	 * Produces the complete persisted decision from the supplied immutable evidence.
	 *
	 * @param json the caller-owned JSON mapper
	 * @param input immutable certification decision inputs
	 * @return the decision record ready for persistence
	 */
	public static ObjectNode evaluate(JsonMapper json, Input input)
	{
		int passingTrials = (int) input.outcomes().stream().filter(Outcome::passed).count();
		Statistics.Result statistics = Statistics.evaluate(input.parameters(), input.outcomes().size(), passingTrials,
			input.infrastructureFailure(), input.routingDeliveryFailure());
		ObjectNode result = json.createObjectNode();
		if (input.fixedBenchmarkInProgress())
			result.put("overall_decision", Statistics.OverallDecision.RUNNING.name());
		else
			result.put("overall_decision", statistics.overallDecision().name());
		result.put("complete_suite_runs", statistics.completeTrials());
		result.put("complete_suite_passes", statistics.passingTrials());
		result.put("observed_compliance", statistics.observedCompliance());
		result.put("required_compliance", input.parameters().requiredCompliance());
		result.put("confidence_level", 1 - input.parameters().falseAcceptRate());
		result.put("sequential_lower_confidence_bound", statistics.lowerConfidenceBound());
		result.put("sprt_decision", statistics.sprtDecision().name());
		result.put("certification_unreachable", statistics.certificationUnreachable());
		result.put("infrastructure_failure", input.infrastructureFailure());
		result.put("sprt_log_likelihood_ratio", statistics.sprtLogLikelihoodRatio());
		return result;
	}

	/**
	 * Projects and persists the current decision under the evaluation-record lock.
	 *
	 * @param json the caller-owned JSON mapper
	 * @param runPath the persistent evaluation record
	 * @param verifyInputs whether immutable inputs must be revalidated before projection
	 * @param access the record and evidence collaborators
	 * @return the current persisted decision
	 * @throws IOException if the record, inputs, or evidence are invalid
	 */
	public static ObjectNode update(JsonMapper json, Path runPath, boolean verifyInputs, Access access)
		throws IOException
	{
		Path normalizedRunPath = runPath.toAbsolutePath().normalize();
		return access.withLock(normalizedRunPath, () -> updateLocked(json, normalizedRunPath, verifyInputs, access));
	}

	/**
	 * Projects a decision while the caller owns the evaluation-record lock.
	 *
	 * @param json the caller-owned JSON mapper
	 * @param runPath the locked persistent evaluation record
	 * @param verifyInputs whether immutable inputs must be revalidated before projection
	 * @param access the record and evidence collaborators
	 * @return the current persisted decision
	 * @throws IOException if the record, inputs, or evidence are invalid
	 */
	public static ObjectNode updateLocked(JsonMapper json, Path runPath, boolean verifyInputs, Access access)
		throws IOException
	{
		ObjectNode run = access.read(runPath);
		if (run.path("decision").isObject())
		{
			ObjectNode recorded = (ObjectNode) run.path("decision");
			boolean benchmarkRunning = access.fixedBenchmarkInProgress(run,
				recorded.path("complete_suite_runs").asInt(), recorded.path("infrastructure_failure").asBoolean());
			if (isReusable(recorded, benchmarkRunning))
			{
				access.validateEvidence(run, runPath);
				return recorded.deepCopy();
			}
		}

		Input input = access.input(run, runPath);
		boolean interrupted = access.interrupted(runPath);
		if (verifyInputs && !interrupted)
			access.validateInputs(run, runPath);
		ObjectNode result = evaluate(json, input);
		if (!input.fixedBenchmarkInProgress() && isTerminal(json, input))
		{
			if (!verifyInputs)
				access.validateInputs(run, runPath);
			if (!interrupted)
				access.validateEvidence(run, runPath);
		}
		run.set("decision", result);
		access.write(runPath, run);
		return result;
	}

	/**
	 * Indicates whether immutable certification evidence establishes a terminal decision.
	 *
	 * @param json the caller-owned JSON mapper
	 * @param input immutable certification decision inputs
	 * @return {@code true} when no additional trial may be allocated
	 */
	public static boolean isTerminal(JsonMapper json, Input input)
	{
		return !evaluate(json, input).path("overall_decision").asString().equals("RUNNING");
	}

	/**
	 * Indicates whether a retained decision may be reused after its referenced evidence is revalidated.
	 *
	 * @param decision the retained decision record
	 * @param fixedBenchmarkInProgress whether benchmark policy still requires additional fixed trials
	 * @return {@code true} when the retained decision is terminal and no benchmark work remains
	 */
	public static boolean isReusable(ObjectNode decision, boolean fixedBenchmarkInProgress)
	{
		return !decision.path("overall_decision").asString().equals("RUNNING") && !fixedBenchmarkInProgress;
	}
}
