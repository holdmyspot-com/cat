package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.contract.AddedCombination;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The formal failures of an ended certification attempt that share a cell, a test, a failed requirement and the
 * behavior through which it failed, and their reproduction in development.
 * <p>
 * Before any author works on a group, CAT reproduces it: a deriver writes development combinations aimed at the
 * behavior, and reproduction trials of the working candidate run them in the round's cell. A round ends once
 * {@value #FAILURES} trials fail the requirement through the behavior, which reproduces the group, or after
 * {@value #TRIALS} trials. A round that does not reproduce the group is repeated under a stress setting when one
 * exists, the next lower reasoning effort of the cell's model, with the same combinations; otherwise its combinations
 * are replaced. After the group's round limit, the group is not reproduced until the user asks for more rounds.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param cellId the cell in which the formal failures occurred
 * @param testId the identifier of the test definition that the failed instances instantiate
 * @param failed the failed requirement and the behavior through which it failed
 * @param formalFailures the attempt's formal failures in the group
 * @param roundLimit the most rounds that the group runs before it is not reproduced
 * @param rounds the group's rounds, in the order they ran; the last one is the current round
 * @param status the group's reproduction status
 */
public record FailureGroup(String cellId, String testId, FailedRequirement failed, int formalFailures,
	int roundLimit, List<ReproductionRound> rounds, Status status)
{
	/**
	 * The rounds that a group runs before it is not reproduced, and that each request for more rounds adds.
	 */
	public static final int ROUNDS = 4;
	/**
	 * The most reproduction trials per round.
	 */
	public static final int TRIALS = 100;
	/**
	 * The failures through the group's behavior that reproduce the group within one round.
	 */
	public static final int FAILURES = 2;

	/**
	 * Creates a group.
	 *
	 * @param cellId the cell in which the formal failures occurred
	 * @param testId the identifier of the test definition that the failed instances instantiate
	 * @param failed the failed requirement and the behavior through which it failed
	 * @param formalFailures the attempt's formal failures in the group
	 * @param roundLimit the most rounds that the group runs before it is not reproduced
	 * @param rounds the group's rounds, in the order they ran
	 * @param status the group's reproduction status
	 * @throws NullPointerException if any of the arguments are null or {@code rounds} contains null
	 * @throws IllegalArgumentException if {@code cellId} or {@code testId} are blank, {@code formalFailures} is not
	 *   positive, {@code roundLimit} is less than {@value #ROUNDS}, {@code rounds} is empty or exceeds
	 *   {@code roundLimit}, or a round exceeds {@value #TRIALS} trials
	 */
	public FailureGroup
	{
		requireThat(cellId, "cellId").isNotBlank();
		requireThat(testId, "testId").isNotBlank();
		requireThat(failed, "failed").isNotNull();
		requireThat(formalFailures, "formalFailures").isPositive();
		requireThat(roundLimit, "roundLimit").isGreaterThanOrEqualTo(ROUNDS);
		requireThat(rounds, "rounds").isNotEmpty();
		requireThat(rounds.size(), "rounds.size()").isLessThanOrEqualTo(roundLimit, "roundLimit");
		for (ReproductionRound round : rounds)
			requireThat(round.trials(), "round.trials()").isLessThanOrEqualTo(TRIALS);
		requireThat(status, "status").isNotNull();
		rounds = List.copyOf(rounds);
	}

	/**
	 * Groups an ended attempt's formal failures. A failure that failed several requirements belongs to one group per
	 * failed requirement.
	 *
	 * @param failures the formal failures, in adoption order
	 * @return the groups, in the order of their first failure, each with one round in its cell that awaits values
	 * @throws NullPointerException if {@code failures} is null
	 */
	public static List<FailureGroup> of(List<Failure> failures)
	{
		requireThat(failures, "failures").isNotNull();
		Map<List<Object>, Integer> counts = new LinkedHashMap<>();
		for (Failure failure : failures)
		{
			String cellId = failure.instance().cellId();
			String testId = failure.instance().testId();
			for (FailedRequirement requirement : new LinkedHashSet<>(failure.failedRequirements()))
				counts.merge(List.of(cellId, testId, requirement), 1, Integer::sum);
		}
		List<FailureGroup> groups = new ArrayList<>();
		for (Map.Entry<List<Object>, Integer> entry : counts.entrySet())
		{
			List<Object> key = entry.getKey();
			String cellId = (String) key.get(0);
			groups.add(new FailureGroup(cellId, (String) key.get(1), (FailedRequirement) key.get(2), entry.getValue(),
				ROUNDS, List.of(new ReproductionRound(cellId, List.of())), Status.REPRODUCING));
		}
		return groups;
	}

	/**
	 * Returns the current round.
	 *
	 * @return the last round
	 */
	public ReproductionRound round()
	{
		return rounds.getLast();
	}

	/**
	 * Indicates whether the group's current round awaits the deriver's combinations.
	 *
	 * @return {@code true} if the group is reproducing and its current round has no combination
	 */
	public boolean awaitsValues()
	{
		return status == Status.REPRODUCING && round().awaitsValues();
	}

	/**
	 * Returns the reproduction trials of every round.
	 *
	 * @return the trials
	 */
	public int trials()
	{
		int trials = 0;
		for (ReproductionRound round : rounds)
			trials += round.trials();
		return trials;
	}

	/**
	 * Returns the reproduction trials of every round that failed the requirement through the group's behavior.
	 *
	 * @return the failures
	 */
	public int failures()
	{
		int failures = 0;
		for (ReproductionRound round : rounds)
			failures += round.failures();
		return failures;
	}

	/**
	 * Returns every combination that the group's rounds tried or try, each once.
	 *
	 * @return the combinations, in the order they were first tried
	 */
	public List<AddedCombination> combinations()
	{
		Set<AddedCombination> combinations = new LinkedHashSet<>();
		for (ReproductionRound round : rounds)
			combinations.addAll(round.added());
		return List.copyOf(combinations);
	}

	/**
	 * Returns this group with the deriver's combinations in its current round.
	 *
	 * @param combinations the combinations
	 * @return the updated group
	 * @throws NullPointerException if {@code combinations} is null or contains null
	 * @throws IllegalArgumentException if {@code combinations} is empty or a combination is of another test
	 * @throws IllegalStateException if the group's current round does not await values
	 */
	FailureGroup withValues(List<AddedCombination> combinations)
	{
		requireThat(combinations, "combinations").isNotEmpty();
		if (!awaitsValues())
			throw new IllegalStateException("The failure group does not await values: " + this);
		for (AddedCombination combination : combinations)
			requireThat(combination.testId(), "combination.testId()").isEqualTo(testId, "testId");
		List<TriedCombination> tried = combinations.stream().map(combination ->
			new TriedCombination(combination, 0, 0)).toList();
		return withRound(new ReproductionRound(round().cellId(), tried), status);
	}

	/**
	 * Returns this group after one more reproduction trial of its current round.
	 *
	 * @param index the position of the trial's combination in the current round
	 * @param matched {@code true} if the trial failed the requirement through the group's behavior
	 * @param stressCellId the cell of the stress setting of the group's cell, or {@code null} if none exists
	 * @return the updated group
	 * @throws IllegalStateException if the group is not reproducing
	 */
	FailureGroup adopt(int index, boolean matched, String stressCellId)
	{
		if (status != Status.REPRODUCING)
			throw new IllegalStateException("The failure group is " + status + ": " + this);
		ReproductionRound round = round();
		List<TriedCombination> combinations = new ArrayList<>(round.combinations());
		combinations.set(index, combinations.get(index).add(matched));
		ReproductionRound updated = new ReproductionRound(round.cellId(), combinations);
		if (updated.failures() >= FAILURES)
			return withRound(updated, Status.REPRODUCED);
		FailureGroup counted = withRound(updated, status);
		if (updated.trials() < TRIALS)
			return counted;
		if (rounds.size() >= roundLimit)
		{
			return new FailureGroup(cellId, testId, failed, formalFailures, roundLimit, counted.rounds,
				Status.NOT_REPRODUCED);
		}
		ReproductionRound next;
		if (stressCellId != null && updated.cellId().equals(cellId))
		{
			next = new ReproductionRound(stressCellId, updated.combinations().stream().map(combination ->
				new TriedCombination(combination.combination(), 0, 0)).toList());
		}
		else
			next = new ReproductionRound(cellId, List.of());
		return counted.withNextRound(next);
	}

	/**
	 * Returns this group with {@value #ROUNDS} more rounds, starting with a round in its cell that awaits values.
	 *
	 * @return the updated group
	 * @throws IllegalStateException if the group is not {@link Status#NOT_REPRODUCED not reproduced}
	 */
	FailureGroup moreRounds()
	{
		if (status != Status.NOT_REPRODUCED)
			throw new IllegalStateException("The failure group is " + status + ": " + this);
		List<ReproductionRound> next = new ArrayList<>(rounds);
		next.add(new ReproductionRound(cellId, List.of()));
		return new FailureGroup(cellId, testId, failed, formalFailures, roundLimit + ROUNDS, next,
			Status.REPRODUCING);
	}

	/**
	 * Returns this group after the user chose a new attempt without reproducing it.
	 *
	 * @return the updated group
	 * @throws IllegalStateException if the group is not {@link Status#NOT_REPRODUCED not reproduced}
	 */
	FailureGroup abandon()
	{
		if (status != Status.NOT_REPRODUCED)
			throw new IllegalStateException("The failure group is " + status + ": " + this);
		return new FailureGroup(cellId, testId, failed, formalFailures, roundLimit, rounds, Status.ABANDONED);
	}

	/**
	 * Returns this group with its current round replaced.
	 *
	 * @param round the updated current round
	 * @param next the group's status
	 * @return the updated group
	 */
	private FailureGroup withRound(ReproductionRound round, Status next)
	{
		List<ReproductionRound> updated = new ArrayList<>(rounds);
		updated.set(updated.size() - 1, round);
		return new FailureGroup(cellId, testId, failed, formalFailures, roundLimit, updated, next);
	}

	/**
	 * Returns this group with another round.
	 *
	 * @param round the next round
	 * @return the updated group
	 */
	private FailureGroup withNextRound(ReproductionRound round)
	{
		List<ReproductionRound> updated = new ArrayList<>(rounds);
		updated.add(round);
		return new FailureGroup(cellId, testId, failed, formalFailures, roundLimit, updated, status);
	}

	/**
	 * Indicates whether the group's reproduction still holds back development: it is reproducing, or not reproduced
	 * and waits for the user's choice.
	 *
	 * @return {@code true} if the group is {@link Status#REPRODUCING} or {@link Status#NOT_REPRODUCED}
	 */
	public boolean isOpen()
	{
		return status == Status.REPRODUCING || status == Status.NOT_REPRODUCED;
	}

	/**
	 * A failure group's reproduction status.
	 */
	public enum Status
	{
		/**
		 * Its rounds run or await values.
		 */
		REPRODUCING,
		/**
		 * A round reproduced it: its combinations joined development.
		 */
		REPRODUCED,
		/**
		 * Its round limit passed without a reproduction; CAT waits for the user's choice.
		 */
		NOT_REPRODUCED,
		/**
		 * It was not reproduced and the user chose a new attempt without reproducing it.
		 */
		ABANDONED
	}
}
