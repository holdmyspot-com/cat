package io.github.cowwoc.cat.common.evaluation;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Coordinates independent validator verdicts over opaque executor evidence.
 *
 * <p>
 * The supplied access seam owns runner-specific staging and record projection. This lifecycle owns the shared
 * scheduling, verdict-shape validation, failure routing, and stable merge policy.
 */
public final class ValidatorLifecycle
{
	/**
	 * Prevents instantiation.
	 */
	private ValidatorLifecycle()
	{
	}

	/**
	 * Records validator verdicts for all semantic evidence in one suite round.
	 *
	 * @param access the runner-specific evidence access seam
	 * @param evidence the completed executor evidence for the round
	 * @param workspaceRoot the evaluator-owned round workspace
	 * @param maximumConcurrency the maximum concurrent validator groups
	 * @param <T> the opaque evidence type
	 * @throws IOException if a terminal validator result cannot be retained
	 */
	public static <T> void recordRound(Access<T> access, List<T> evidence, Path workspaceRoot, int maximumConcurrency)
		throws IOException
	{
		List<T> semanticEvidence = semanticEvidence(access, evidence);
		if (semanticEvidence.isEmpty())
			return;

		List<ValidatorSchedule.Group<T>> groups = ValidatorSchedule.groups(semanticEvidence,
			maximumConcurrency, access::key);
		Path roundWorkspace = ValidatorSchedule.workspace(workspaceRoot, semanticEvidence, access::key);
		try (ExecutorService validators = Executors.newFixedThreadPool(groups.size()))
		{
			CompletionService<GroupResult<T>> completed = new ExecutorCompletionService<>(validators);
			for (ValidatorSchedule.Group<T> group : groups)
			{
				completed.submit(() -> executeGroup(access, group,
					roundWorkspace.resolve("validator-group-" + group.ordinal())));
			}

			List<GroupResult<T>> results = new ArrayList<>();
			for (ValidatorSchedule.Group<T> ignored : groups)
				try
				{
					GroupResult<T> result = completed.take().get();
					if (result != null)
						results.add(result);
				}
				catch (ExecutionException e)
				{
					throw new IOException("Batch validator did not retain a terminal result", e.getCause());
				}
			mergeGroups(access, results);
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted while waiting for suite validators", e);
		}
	}

	/**
	 * Records one validator verdict map across isolated trial evidence.
	 *
	 * @param access the runner-specific evidence access seam
	 * @param evidence the completed executor evidence
	 * @param validatorWorkspace the initially absent validator workspace
	 * @param <T> the opaque evidence type
	 * @throws IOException if a terminal validator result cannot be retained
	 */
	public static <T> void recordCrossTrial(Access<T> access, List<T> evidence, Path validatorWorkspace)
		throws IOException
	{
		List<T> semanticEvidence = semanticEvidence(access, evidence);
		if (semanticEvidence.isEmpty())
			return;

		ValidatorSchedule.Group<T> group = new ValidatorSchedule.Group<>(1, semanticEvidence);
		GroupResult<T> result = executeGroup(access, group, validatorWorkspace);
		if (result != null)
			mergeGroups(access, List.of(result));
	}

	/**
	 * Creates the exact verdict-map skeleton for an assigned group.
	 *
	 * @param evidence the group evidence
	 * @param key the stable key for one evidence item
	 * @param assertionIds the semantic assertion identifiers for one evidence item
	 * @param <T> the opaque evidence type
	 * @return the incomplete verdict map
	 * @throws IOException if evidence has duplicate verdict keys
	 */
	public static <T> ObjectNode newVerdicts(List<T> evidence, Key<T> key, Assertions<T> assertionIds)
		throws IOException
	{
		ObjectNode verdicts = JsonNodeFactory.instance.objectNode();
		for (T item : evidence)
		{
			String verdictKey = key.value(item);
			if (verdicts.has(verdictKey))
				throw new IOException("Validator group has duplicate verdict key: " + verdictKey);
			ObjectNode verdict = verdicts.putObject(verdictKey);
			verdict.put("status", "");
			ObjectNode assertions = verdict.putObject("assertions");
			for (String assertionId : assertionIds.values(item))
			{
				ObjectNode assertion = assertions.putObject(assertionId);
				assertion.put("status", "");
				assertion.put("evidence", "");
			}
		}
		return verdicts;
	}

	/**
	 * Executes one group and records its controlled failure when its runner does not retain a valid verdict map.
	 *
	 * @param access the runner-specific evidence access seam
	 * @param group the assigned evidence group
	 * @param validatorWorkspace the initially absent group workspace
	 * @param <T> the opaque evidence type
	 * @return the retained group result, or {@code null} after controlled failure recording
	 * @throws IOException if controlled failure recording cannot complete
	 */
	private static <T> GroupResult<T> executeGroup(Access<T> access, ValidatorSchedule.Group<T> group,
		Path validatorWorkspace) throws IOException
	{
		try
		{
			access.publishActiveGroup(group.ordinal());
			GroupResult<T> result = access.execute(group, validatorWorkspace);
			validateGroupVerdicts(access, result);
			return result;
		}
		catch (IOException e)
		{
			access.recordFailure(group.evidence(), validatorWorkspace, e);
			return null;
		}
	}

	/**
	 * Selects semantic evidence and records the deterministic remainder.
	 *
	 * @param access the runner-specific evidence access seam
	 * @param evidence the completed executor evidence
	 * @param <T> the opaque evidence type
	 * @return the evidence requiring semantic review
	 * @throws IOException if a deterministic result cannot be recorded
	 */
	private static <T> List<T> semanticEvidence(Access<T> access, List<T> evidence) throws IOException
	{
		List<T> result = new ArrayList<>();
		for (T item : evidence)
			if (access.requiresSemanticReview(item))
				result.add(item);
			else
				access.recordDeterministicVerdict(item);
		return result;
	}

	/**
	 * Merges completed validator groups in stable verdict-key order.
	 *
	 * @param access the runner-specific evidence access seam
	 * @param groupResults the completed groups
	 * @param <T> the opaque evidence type
	 * @throws IOException if groups overlap or a verdict cannot be retained
	 */
	private static <T> void mergeGroups(Access<T> access, List<GroupResult<T>> groupResults) throws IOException
	{
		Map<String, GroupResult<T>> ownerByVerdict = new TreeMap<>();
		for (GroupResult<T> groupResult : groupResults)
			for (T item : groupResult.group().evidence())
			{
				String key = access.key(item);
				GroupResult<T> previous = ownerByVerdict.putIfAbsent(key, groupResult);
				if (previous != null)
					throw new IOException("Validator groups overlap on " + key + ": " + groupResult.validatorResult());
			}

		for (Map.Entry<String, GroupResult<T>> entry : ownerByVerdict.entrySet())
		{
			GroupResult<T> groupResult = entry.getValue();
			T item = groupResult.group().evidence().stream().filter(candidate -> access.key(candidate).equals(
				entry.getKey())).findFirst().orElseThrow();
			access.recordVerdict(item, groupResult.verdicts(), groupResult.validatorResult(),
				groupResult.validationStartedAtNanos(), groupResult.validationCompletedAtNanos(), System.nanoTime());
		}
	}

	/**
	 * Verifies one runner-returned verdict map against its assigned opaque evidence.
	 *
	 * @param access the runner-specific evidence access seam
	 * @param result the runner-returned group result
	 * @param <T> the opaque evidence type
	 * @throws IOException if the map is incomplete or malformed
	 */
	private static <T> void validateGroupVerdicts(Access<T> access, GroupResult<T> result) throws IOException
	{
		Set<String> expected = new HashSet<>();
		for (T item : result.group().evidence())
			if (!expected.add(access.key(item)))
				throw new IOException("Validator group has duplicate assigned worker verdicts: " + result.validatorResult());

		Set<String> actual = new HashSet<>();
		result.verdicts().propertyNames().forEach(actual::add);
		if (!actual.equals(expected))
			throw new IOException("Validator group verdict keys do not match its assigned workers: " +
				result.validatorResult() + " expected=" + expected + " actual=" + actual);

		for (T item : result.group().evidence())
		{
			String key = access.key(item);
			ObjectNode verdict = object(result.verdicts().path(key), "batch validator verdict for " + key,
				result.validatorResult());
			status(verdict, "status", "Batch validator", key, result.validatorResult());
			ObjectNode assertions = object(verdict.path("assertions"), "assertions for " + key,
				result.validatorResult());
			Set<String> expectedAssertions = access.assertionIds(item);
			Set<String> actualAssertions = new HashSet<>();
			assertions.propertyNames().forEach(actualAssertions::add);
			if (!actualAssertions.equals(expectedAssertions))
				throw new IOException("Batch validator assertions for " + key + " do not match the testcase assertions");
			for (String assertionId : expectedAssertions)
			{
				ObjectNode assertion = object(assertions.path(assertionId), "assertion verdict " + assertionId +
					" for " + key, result.validatorResult());
				status(assertion, "status", "Batch validator", "assertion " + assertionId + " of " + key,
					result.validatorResult());
				if (!assertion.path("evidence").isString() || assertion.path("evidence").asString().isBlank())
					throw new IOException("Batch validator must return evidence for assertion " + assertionId + " of " + key);
			}
		}
	}

	/**
	 * Requires one object-valued validator response element.
	 *
	 * @param node the element to inspect
	 * @param description its reader-facing description
	 * @param source the retained verdict map
	 * @return the validated object
	 * @throws IOException if the element is not an object
	 */
	private static ObjectNode object(tools.jackson.databind.JsonNode node, String description, Path source)
		throws IOException
	{
		if (node instanceof ObjectNode result)
			return result;
		throw new IOException("Expected object " + description + " in " + source);
	}

	/**
	 * Requires one passed-or-failed validator status.
	 *
	 * @param record the verdict or assertion record
	 * @param field the status field
	 * @param owner the validating component name
	 * @param subject the reviewed subject
	 * @param source the retained verdict map
	 * @throws IOException if the status is not terminal
	 */
	private static void status(ObjectNode record, String field, String owner, String subject, Path source)
		throws IOException
	{
		if (!record.path(field).isString())
			throw new IOException(owner + " must return passed or failed for " + subject + ": " + source);
		String value = record.path(field).asString();
		if (!value.equals("passed") && !value.equals("failed"))
			throw new IOException(owner + " must return passed or failed for " + subject + ": " + source);
	}

	/**
	 * Supplies the runner-specific operations that the generic lifecycle cannot determine from opaque evidence.
	 *
	 * @param <T> the opaque evidence type
	 */
	public interface Access<T>
	{
		/**
		 * Determines whether one completed executor result requires independent semantic review.
		 *
		 * @param evidence one completed executor result
		 * @return {@code true} if a validator must review the result
		 * @throws IOException if deterministic evidence cannot be inspected
		 */
		boolean requiresSemanticReview(T evidence) throws IOException;

		/**
		 * Records an executor result decided without semantic review.
		 *
		 * @param evidence one deterministically decided executor result
		 * @throws IOException if the result cannot be recorded
		 */
		void recordDeterministicVerdict(T evidence) throws IOException;

		/**
		 * Returns the durable verdict key for one evidence item.
		 *
		 * @param evidence one completed executor result
		 * @return its stable verdict key
		 */
		String key(T evidence);

		/**
		 * Returns the semantic assertion identifiers expected for one evidence item.
		 *
		 * @param evidence one completed executor result
		 * @return its expected semantic assertion identifiers
		 * @throws IOException if the assertion contract cannot be read
		 */
		Set<String> assertionIds(T evidence) throws IOException;

		/**
		 * Publishes the active lifecycle stage for one validator group.
		 *
		 * @param ordinal the stable validator-group order
		 * @throws IOException if the lifecycle status cannot be retained
		 */
		void publishActiveGroup(int ordinal) throws IOException;

		/**
		 * Stages and invokes one validator group through the runner-specific boundary.
		 *
		 * @param group the assigned evidence
		 * @param workspace the initially absent group workspace
		 * @return the retained verdict result
		 * @throws IOException if the runner cannot retain a result
		 */
		GroupResult<T> execute(ValidatorSchedule.Group<T> group, Path workspace) throws IOException;

		/**
		 * Records a terminal validator failure for every still-unrecorded group item.
		 *
		 * @param evidence the affected executor results
		 * @param workspace the group workspace that owns diagnostics
		 * @param failure the observed validator failure
		 * @throws IOException if the blocked outcomes cannot be retained
		 */
		void recordFailure(List<T> evidence, Path workspace, IOException failure) throws IOException;

		/**
		 * Projects one validated semantic verdict into the owner-specific durable record.
		 *
		 * @param evidence the completed executor result
		 * @param verdicts the complete group verdict map
		 * @param validatorResult the retained validator result
		 * @param validationStartedAtNanos the validator start time
		 * @param validationCompletedAtNanos the validator completion time
		 * @param mergedAtNanos the stable merge time
		 * @throws IOException if the durable record cannot be updated
		 */
		void recordVerdict(T evidence, ObjectNode verdicts, Path validatorResult, long validationStartedAtNanos,
			long validationCompletedAtNanos, long mergedAtNanos) throws IOException;
	}

	/**
	 * Retains one completed validator group until stable merging projects its verdicts.
	 *
	 * @param group the assigned evidence group
	 * @param verdicts the complete group verdict map
	 * @param validatorResult the retained verdict-map path
	 * @param validationStartedAtNanos the monotonic validator start time
	 * @param validationCompletedAtNanos the monotonic validator completion time
	 * @param <T> the opaque evidence type
	 */
	public record GroupResult<T>(ValidatorSchedule.Group<T> group, ObjectNode verdicts, Path validatorResult,
		long validationStartedAtNanos, long validationCompletedAtNanos)
	{
	}

	/**
	 * Resolves one stable opaque-evidence key.
	 *
	 * @param <T> the opaque evidence type
	 */
	@FunctionalInterface
	public interface Key<T>
	{
		/**
		 * Returns one stable evidence key.
		 *
		 * @param evidence the opaque evidence
		 * @return its stable key
		 */
		String value(T evidence);
	}

	/**
	 * Resolves the semantic assertions expected for opaque evidence.
	 *
	 * @param <T> the opaque evidence type
	 */
	@FunctionalInterface
	public interface Assertions<T>
	{
		/**
		 * Returns semantic assertion identifiers for one evidence item.
		 *
		 * @param evidence the opaque evidence
		 * @return its semantic assertion identifiers
		 * @throws IOException if the assertion contract cannot be read
		 */
		Set<String> values(T evidence) throws IOException;
	}
}
