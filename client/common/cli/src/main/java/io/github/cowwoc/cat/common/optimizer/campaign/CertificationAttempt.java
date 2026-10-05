package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.contract.DrawStream;
import io.github.cowwoc.cat.common.optimizer.statistics.DecisionBoundaries;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The outcomes and fixed conditions for certifying one unchanged prompt snapshot on newly sampled trials.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param index the certification index {@code i}, assigned at commit and never changed
 * @param lineage the number of the confirmed-contract history that the attempt belongs to
 * @param candidate the frozen candidate
 * @param identity the measurement conditions that the attempt was measured under
 * @param procedure the frozen procedure
 * @param counts the certification outcomes included in evidence per cell (category and trial profile)
 * @param failures the formal failures, in adoption order
 * @param formalStream the attempt's formal draw stream
 * @param outcome the attempt's outcome, or {@code null} while it runs
 * @param groups the groups of the formal failures and their reproduction in development; empty unless the attempt
 *   ended {@link Outcome#BELOW_MINIMUM below minimum} or {@link Outcome#INCONCLUSIVE inconclusive} with formal
 *   failures
 */
public record CertificationAttempt(int index, int lineage, Candidate candidate, MeasurementIdentity identity,
	CertificationProcedure procedure, Map<String, CellCounts> counts, List<Failure> failures,
	DrawStream.State formalStream, Outcome outcome, List<FailureGroup> groups)
{
	/**
	 * Creates an attempt.
	 *
	 * @param index the certification index {@code i}, assigned at commit and never changed
	 * @param lineage the number of the confirmed-contract history that the attempt belongs to
	 * @param candidate the frozen candidate
	 * @param identity the measurement conditions that the attempt was measured under
	 * @param procedure the frozen procedure
	 * @param counts the certification outcomes included in evidence per cell (category and trial profile)
	 * @param failures the formal failures, in adoption order
	 * @param formalStream the attempt's formal draw stream
	 * @param outcome the attempt's outcome, or {@code null} while it runs
	 * @param groups the groups of the formal failures and their reproduction in development
	 * @throws NullPointerException if any argument other than {@code outcome} is null
	 * @throws IllegalArgumentException if {@code formalStream} is not an open formal stream, {@code index} or
	 *   {@code lineage} are not positive, a cell's counts exceed the cap, or a running attempt has groups
	 */
	public CertificationAttempt
	{
		requireThat(index, "index").isPositive();
		requireThat(lineage, "lineage").isPositive();
		requireThat(candidate, "candidate").isNotNull();
		requireThat(identity, "identity").isNotNull();
		requireThat(procedure, "procedure").isNotNull();
		requireThat(counts, "counts").isNotNull();
		requireThat(failures, "failures").isNotNull();
		requireThat(formalStream, "formalStream").isNotNull();
		requireThat(formalStream.kind(), "formalStream.kind()").isEqualTo(DrawStream.Kind.FORMAL);
		requireThat(formalStream.open(), "formalStream.open()").isTrue();
		int cap = procedure.boundaries().cap();
		for (Map.Entry<String, CellCounts> entry : counts.entrySet())
		{
			requireThat(entry.getValue().outcomes(), "counts[" + entry.getKey() + "].outcomes()").
				isLessThanOrEqualTo(cap, "cap");
		}
		requireThat(groups, "groups").isNotNull();
		if (outcome == null)
			requireThat(groups, "groups").isEmpty();
		counts = Map.copyOf(counts);
		failures = List.copyOf(failures);
		groups = List.copyOf(groups);
	}

	/**
	 * Returns a cell's outcomes included in certification evidence.
	 *
	 * @param cell the cell's identifier
	 * @return the counts, empty if the cell has no outcomes
	 * @throws NullPointerException if {@code cell} is null
	 */
	public CellCounts count(String cell)
	{
		requireThat(cell, "category").isNotNull();
		return counts.getOrDefault(cell, CellCounts.EMPTY);
	}

	/**
	 * Returns the parameters of the attempt's claim, which its frozen procedure records.
	 *
	 * @return the parameters
	 */
	public CertificationParameters parameters()
	{
		DecisionBoundaries boundaries = procedure.boundaries();
		return new CertificationParameters(boundaries.requiredPassRate(), boundaries.level());
	}

	/**
	 * Indicates whether the attempt is still running.
	 *
	 * @return {@code true} if it has no outcome
	 */
	public boolean isRunning()
	{
		return outcome == null;
	}

	/**
	 * Returns this attempt with an outcome. An ended attempt never reopens. An attempt that ends
	 * {@link Outcome#BELOW_MINIMUM below minimum} or {@link Outcome#INCONCLUSIVE inconclusive} groups its formal
	 * failures for reproduction.
	 *
	 * @param ending the outcome
	 * @return the ended attempt
	 * @throws NullPointerException if {@code ending} is null
	 * @throws IllegalStateException if the attempt has ended
	 */
	CertificationAttempt end(Outcome ending)
	{
		requireThat(ending, "ending").isNotNull();

		if (outcome != null)
			throw new IllegalStateException("Certification attempt " + index + " already ended " + outcome.label());
		List<FailureGroup> ended = List.of();
		if (ending == Outcome.BELOW_MINIMUM || ending == Outcome.INCONCLUSIVE)
			ended = FailureGroup.of(failures);
		return new CertificationAttempt(index, lineage, candidate, identity, procedure, counts, failures,
			formalStream, ending, ended);
	}

	/**
	 * Indicates whether the attempt's failure groups hold back development: a group is reproducing, or is not
	 * reproduced and waits for the user's choice.
	 *
	 * @return {@code true} if some group is open
	 */
	public boolean isReproducing()
	{
		return groups.stream().anyMatch(FailureGroup::isOpen);
	}

	/**
	 * Returns a failure group.
	 *
	 * @param number the group's 1-based position in {@link #groups()}
	 * @return the group
	 * @throws IllegalArgumentException if no group has the number
	 */
	public FailureGroup group(int number)
	{
		requireThat(number, "number").isBetween(1, true, groups.size(), true);
		return groups.get(number - 1);
	}

	/**
	 * Returns this attempt with an updated failure group.
	 *
	 * @param number the group's 1-based position in {@link #groups()}
	 * @param group the updated group
	 * @return the updated attempt
	 */
	CertificationAttempt withGroup(int number, FailureGroup group)
	{
		List<FailureGroup> updated = new ArrayList<>(groups);
		updated.set(number - 1, group);
		return new CertificationAttempt(index, lineage, candidate, identity, procedure, counts, failures,
			formalStream, outcome, updated);
	}

	/**
	 * Returns this running attempt with updated evidence.
	 *
	 * @param identity the measurement conditions that the attempt was measured under
	 * @param counts the certification outcomes included in evidence per cell
	 * @param failures the formal failures, in adoption order
	 * @param formalStream the attempt's formal draw stream
	 * @return the updated attempt
	 */
	CertificationAttempt withEvidence(MeasurementIdentity identity, Map<String, CellCounts> counts,
		List<Failure> failures, DrawStream.State formalStream)
	{
		return new CertificationAttempt(index, lineage, candidate, identity, procedure, counts, failures,
			formalStream, outcome, groups);
	}
}
