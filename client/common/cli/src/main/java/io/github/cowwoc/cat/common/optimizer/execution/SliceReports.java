package io.github.cowwoc.cat.common.optimizer.execution;

import io.github.cowwoc.cat.common.optimizer.campaign.Applicability;
import io.github.cowwoc.cat.common.optimizer.delivery.DeliveryResult;
import io.github.cowwoc.cat.common.optimizer.inventory.DeliveryConflict;
import io.github.cowwoc.cat.common.optimizer.inventory.PathChange;
import io.github.cowwoc.cat.common.optimizer.report.ReportStatus;
import io.github.cowwoc.cat.common.optimizer.report.StatusReport;

import java.nio.file.Path;
import java.util.List;
import java.util.StringJoiner;

/**
 * The parts of a slice's status that the {@link Driver} knows and {@link StatusReport} does not: a revision that waits
 * for confirmation, a delivery that stopped, and the applicability of a delivered candidate.
 */
final class SliceReports
{
	/**
	 * The decision that a campaign waits for after a revision request, once its in-flight trials drained.
	 */
	static final String REVISION_PENDING = "A contract revision waits for confirmation";

	/**
	 * Prevents instantiation.
	 */
	private SliceReports()
	{
	}

	/**
	 * Returns a report with a different status and pending decision.
	 *
	 * @param report the report
	 * @param status the status
	 * @param pending the decision that the campaign waits for
	 * @return the report
	 */
	static StatusReport awaiting(StatusReport report, ReportStatus status, String pending)
	{
		return new StatusReport(status, report.campaignId(), report.lifecycle(), report.phase(), report.attempt(),
			report.candidate(), report.snapshot(), report.outcome(), report.outcomeDetail(), report.delivery(),
			report.deliveryAttempt(), pending, report.choices(), report.categories(), report.allocation(),
			report.benchmark(), report.remaining(), report.tokens(), report.concurrency(), report.assumptions(),
			report.attempts(), report.keepOffers(), report.claim(), report.limitations(), report.conditions());
	}

	/**
	 * Describes why a delivery stopped before it completed.
	 *
	 * @param result the delivery's result, which is not {@link DeliveryResult.Delivered}
	 * @return the description
	 */
	static String describe(DeliveryResult result)
	{
		return switch (result)
		{
			case DeliveryResult.Delivered _ -> throw new AssertionError("The delivery completed: " + result);
			case DeliveryResult.UnownedChange unowned ->
				"The optimization worktree has changes that CAT did not make, so the delivery stopped: " +
					changedPaths(unowned.changes());
			case DeliveryResult.Conflicted conflicted ->
			{
				StringJoiner targets = new StringJoiner(", ");
				for (DeliveryConflict conflict : conflicted.conflicts())
					targets.add(conflict.target().toString());
				yield "The base worktree changed files that the candidate also changes, so the delivery stopped: " +
					targets;
			}
			case DeliveryResult.ConcurrentEdit edit -> "A file changed while CAT delivered the candidate, so the " +
				"delivery stopped after " + edit.applied() + " changes: " + edit.change().target();
			case DeliveryResult.VerificationFailed failed ->
			{
				StringJoiner paths = new StringJoiner(", ");
				for (Path path : failed.mismatched())
					paths.add(path.toString());
				yield "The delivered files do not match the candidate, so the delivery is incomplete: " + paths;
			}
		};
	}

	/**
	 * Combines the prompt-content side of a delivered candidate's applicability with its measurement side.
	 *
	 * @param content the delivery's applicability, which covers base-only changes to prompt sources
	 * @param measurement the applicability of the delivered result's measurement conditions
	 * @return the combined applicability
	 */
	static Applicability combine(Applicability content, Applicability measurement)
	{
		Applicability.Status status = Applicability.Status.CURRENT;
		if (content.status() == Applicability.Status.HISTORICAL ||
			measurement.status() == Applicability.Status.HISTORICAL)
		{
			status = Applicability.Status.HISTORICAL;
		}
		return new Applicability(status, content.promptContentChanged() || measurement.promptContentChanged(),
			measurement.changed());
	}

	/**
	 * Lists the paths of inventory changes.
	 *
	 * @param changes the changes
	 * @return the comma-separated paths
	 */
	private static String changedPaths(List<PathChange> changes)
	{
		StringJoiner paths = new StringJoiner(", ");
		for (PathChange change : changes)
			paths.add(change.path().toString());
		return paths.toString();
	}
}
