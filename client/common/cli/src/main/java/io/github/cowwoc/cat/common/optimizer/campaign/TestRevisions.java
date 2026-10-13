package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.ArrayList;
import java.util.List;

/**
 * The campaign's pending test revision requests, the revisions that it applied and the tests that a revision reported
 * sound.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param requests the pending requests, at most one per test, in the order they arose
 * @param revisions the applied revisions, in the order they were applied
 * @param sound the reports that a test needs no change, in the order they were made
 */
public record TestRevisions(List<TestRevisionRequest> requests, List<TestRevision> revisions, List<SoundTest> sound)
{
	/**
	 * No requests, revisions or reports.
	 */
	public static final TestRevisions NONE = new TestRevisions(List.of(), List.of(), List.of());

	/**
	 * Creates the test revisions.
	 *
	 * @param requests the pending requests, at most one per test, in the order they arose
	 * @param revisions the applied revisions, in the order they were applied
	 * @param sound the reports that a test needs no change, in the order they were made
	 * @throws NullPointerException if any argument or element is null
	 * @throws IllegalArgumentException if two requests name the same test
	 */
	public TestRevisions
	{
		requests = List.copyOf(requests);
		revisions = List.copyOf(revisions);
		sound = List.copyOf(sound);
		requireThat(requests.stream().map(TestRevisionRequest::test).distinct().count(), "distinctTests").
			isEqualTo((long) requests.size(), "requests.size()");
	}

	/**
	 * Indicates whether a test revision reported a test sound in a lineage.
	 *
	 * @param lineage the lineage's number
	 * @param test the identifier of the test
	 * @return {@code true} if a report covers the test in the lineage
	 */
	public boolean isSound(int lineage, String test)
	{
		for (SoundTest report : sound)
		{
			if (report.lineage() == lineage && report.test().equals(test))
				return true;
		}
		return false;
	}

	/**
	 * Returns these test revisions with a request added, merged into any pending request for the same test.
	 *
	 * @param request the request
	 * @return the updated test revisions
	 * @throws NullPointerException if {@code request} is null
	 */
	public TestRevisions request(TestRevisionRequest request)
	{
		List<TestRevisionRequest> updated = new ArrayList<>(requests);
		for (int i = 0; i < updated.size(); ++i)
		{
			if (updated.get(i).test().equals(request.test()))
			{
				updated.set(i, updated.get(i).merge(request));
				return new TestRevisions(updated, revisions, sound);
			}
		}
		updated.add(request);
		return new TestRevisions(updated, revisions, sound);
	}

	/**
	 * Returns these test revisions without the pending request for a test.
	 *
	 * @param test the identifier of the test
	 * @return the updated test revisions
	 */
	public TestRevisions withoutRequest(String test)
	{
		List<TestRevisionRequest> updated = new ArrayList<>(requests);
		updated.removeIf(request -> request.test().equals(test));
		return new TestRevisions(updated, revisions, sound);
	}

	/**
	 * Returns these test revisions with a report that a test needs no change.
	 *
	 * @param report the report
	 * @return the updated test revisions, without the pending request for the report's test
	 */
	public TestRevisions withSound(SoundTest report)
	{
		TestRevisions answered = withoutRequest(report.test());
		List<SoundTest> updated = new ArrayList<>(answered.sound);
		updated.add(report);
		return new TestRevisions(answered.requests, answered.revisions, updated);
	}
}
