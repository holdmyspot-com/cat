package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.inventory.FileDelta;
import io.github.cowwoc.cat.common.optimizer.inventory.Hunk;
import io.github.cowwoc.cat.common.optimizer.inventory.Hunks;
import io.github.cowwoc.cat.common.optimizer.inventory.Sha256;

import java.util.ArrayList;
import java.util.List;

/**
 * A resumable delta-debugging search over one prompt author's original hunks. A passing reduction replaces the retained
 * set; failed reductions split into progressively smaller groups until each retained hunk has been tested alone.
 * Promotion judges each reduction against every triggering test case fixed by the last accepted runs.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param files the original changes, including the working candidate's bytes
 * @param retained the hunks in the last passing attempt
 * @param groups the number of groups in the current partition
 * @param group the group currently being removed, from zero
 * @param testing the remaining hunks being tested, or {@code null} before the first pass or when finished
 * @param accepted the last passing snapshot
 * @param acceptedRuns its promotion evidence, whose fixed triggering test cases reductions must preserve; empty before
 *   the original attempt passes
 */
public record Minimality(List<FileDelta> files, List<String> retained, int groups, int group,
	List<String> testing, Sha256 accepted, List<PromotionRun> acceptedRuns)
{
	/**
	 * Creates a search state.
	 *
	 * @param files the original changes
	 * @param retained the last passing hunks
	 * @param groups the partition size
	 * @param group the group index
	 * @param testing the hunks being tested, or {@code null}
	 * @param accepted the last passing snapshot
	 * @param acceptedRuns its promotion runs
	 * @throws NullPointerException if a required argument or list element is null
	 * @throws IllegalArgumentException if a partition size or index is invalid
	 */
	public Minimality
	{
		files = List.copyOf(files);
		retained = List.copyOf(retained);
		requireThat(groups, "groups").isGreaterThanOrEqualTo(2);
		requireThat(group, "group").isNotNegative();
		if (testing != null)
			testing = List.copyOf(testing);
		requireThat(accepted, "accepted").isNotNull();
		acceptedRuns = List.copyOf(acceptedRuns);
	}

	/**
	 * Starts a search before the original attempt's promotion decision.
	 *
	 * @param files the original changes
	 * @param snapshot the prompt author's snapshot
	 * @return the initial state
	 */
	public static Minimality start(List<FileDelta> files, Sha256 snapshot)
	{
		List<String> identifiers = files.stream().flatMap(file -> Hunks.between(file).stream()).map(Hunk::id).
			toList();
		return new Minimality(files, identifiers, 2, 0, null, snapshot, List.of());
	}

	/**
	 * Advances after a completed promotion decision. The first decision must pass; later decisions concern the
	 * current reduction. This method schedules no trials and consumes no allowance.
	 *
	 * @param snapshot the tested snapshot
	 * @param runs the completed promotion runs
	 * @param decision their decision
	 * @return the next reduction or a finished state with {@code testing == null}
	 * @throws IllegalArgumentException if the decision is pending or the original attempt failed
	 */
	public Minimality advance(Sha256 snapshot, List<PromotionRun> runs, Promotion.Decision decision)
	{
		requireThat(decision, "decision").isNotEqualTo(Promotion.Decision.PENDING);
		if (testing == null && acceptedRuns.isEmpty() && decision != Promotion.Decision.PROMOTE)
			throw new IllegalArgumentException("The original attempt must pass before reduction");
		List<String> kept = retained;
		Sha256 passing = accepted;
		List<PromotionRun> evidence = acceptedRuns;
		int partitions = groups;
		int index = group;
		if (decision == Promotion.Decision.PROMOTE)
		{
			if (testing != null)
				kept = testing;
			passing = snapshot;
			evidence = runs;
			partitions = Math.max(2, partitions - 1);
			index = 0;
		}
		else
			++index;
		if (kept.isEmpty())
			return new Minimality(files, kept, 2, 0, null, passing, evidence);
		int count = Math.min(partitions, kept.size());
		if (index >= count)
		{
			if (count == kept.size())
				return new Minimality(files, kept, partitions, index, null, passing, evidence);
			partitions = Math.min(kept.size(), count * 2);
			count = partitions;
			index = 0;
		}
		int start = index * kept.size() / count;
		int end = (index + 1) * kept.size() / count;
		List<String> remaining = new ArrayList<>(kept.subList(0, start));
		remaining.addAll(kept.subList(end, kept.size()));
		return new Minimality(files, kept, partitions, index, remaining, passing, evidence);
	}

	/**
	 * Returns the original hunks absent from the last passing attempt.
	 *
	 * @return their stable identifiers
	 */
	public List<String> removed()
	{
		return files.stream().flatMap(file -> Hunks.between(file).stream()).map(Hunk::id).
			filter(identifier -> !retained.contains(identifier)).toList();
	}
}
