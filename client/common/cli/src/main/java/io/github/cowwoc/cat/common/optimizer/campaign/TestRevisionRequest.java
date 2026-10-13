package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A request to examine, and if needed revise, one prompt test. While a request is pending, no new trial starts.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param test the identifier of the test
 * @param trigger what requested the revision
 * @param kinds the prompt author's kinds of failure that concern the test, in report order; empty if none
 * @param failures the failures of those kinds by the prompt author's failure identifier, in report order
 */
public record TestRevisionRequest(String test, Trigger trigger, List<PromptAuthorKind> kinds,
	Map<String, Failure> failures)
{
	/**
	 * Creates a request.
	 *
	 * @param test the identifier of the test
	 * @param trigger what requested the revision
	 * @param kinds the prompt author's kinds of failure that concern the test, in report order; empty if none
	 * @param failures the failures of those kinds by the prompt author's failure identifier, in report order
	 * @throws NullPointerException if any argument, collection element, key or value is null
	 * @throws IllegalArgumentException if {@code test} or a failure identifier is blank
	 */
	public TestRevisionRequest
	{
		requireThat(test, "test").isNotBlank();
		requireThat(trigger, "trigger").isNotNull();
		kinds = List.copyOf(kinds);
		for (String id : failures.keySet())
			requireThat(id, "failureId").isNotBlank();
		failures = Collections.unmodifiableMap(new LinkedHashMap<>(failures));
		for (Failure failure : failures.values())
			requireThat(failure, "failure").isNotNull();
	}

	/**
	 * Returns a request that adds the kinds and failures of another request for the same test to this one's.
	 *
	 * @param other the other request
	 * @return the merged request; it keeps this request's trigger unless the other one names a test defect
	 * @throws NullPointerException if {@code other} is null
	 * @throws IllegalArgumentException if {@code other} names another test
	 */
	public TestRevisionRequest merge(TestRevisionRequest other)
	{
		requireThat(other.test(), "other.test()").isEqualTo(test, "test");
		Trigger merged = trigger;
		if (other.trigger() == Trigger.TEST_DEFECT)
			merged = Trigger.TEST_DEFECT;
		List<PromptAuthorKind> mergedKinds = new ArrayList<>(kinds);
		for (PromptAuthorKind kind : other.kinds())
		{
			if (!mergedKinds.contains(kind))
				mergedKinds.add(kind);
		}
		Map<String, Failure> mergedFailures = new LinkedHashMap<>(failures);
		mergedFailures.putAll(other.failures());
		return new TestRevisionRequest(test, merged, mergedKinds, mergedFailures);
	}

	/**
	 * What requested a test revision.
	 */
	public enum Trigger
	{
		/**
		 * The prompt author classified a kind of failure of the test as a test defect.
		 */
		TEST_DEFECT,
		/**
		 * A cell of the test reached its review checkpoint, and no test revision examined the test since it last
		 * changed.
		 */
		REVIEW_CHECKPOINT
	}
}
