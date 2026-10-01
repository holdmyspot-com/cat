package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.contract.DrawStream;

import java.util.List;
import java.util.Map;

/**
 * One frozen candidate tested on fresh formal trials under one frozen procedure.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param index the certification index {@code i}, assigned at commit and never changed
 * @param lineage the lineage that the attempt was committed under
 * @param candidate the frozen candidate
 * @param identity the measurement conditions that the attempt was measured under
 * @param procedure the frozen procedure
 * @param counts the adopted formal outcomes per category
 * @param failures the formal failures, in adoption order
 * @param formalStream the attempt's formal draw stream
 * @param outcome the attempt's outcome, or {@code null} while it runs
 */
public record CertificationAttempt(int index, int lineage, Candidate candidate, MeasurementIdentity identity,
	CertificationProcedure procedure, Map<String, CategoryCounts> counts, List<Failure> failures,
	DrawStream.State formalStream, Outcome outcome)
{
	/**
	 * Creates an attempt.
	 *
	 * @param index the certification index {@code i}, assigned at commit and never changed
	 * @param lineage the lineage that the attempt was committed under
	 * @param candidate the frozen candidate
	 * @param identity the measurement conditions that the attempt was measured under
	 * @param procedure the frozen procedure
	 * @param counts the adopted formal outcomes per category
	 * @param failures the formal failures, in adoption order
	 * @param formalStream the attempt's formal draw stream
	 * @param outcome the attempt's outcome, or {@code null} while it runs
	 * @throws NullPointerException if any argument other than {@code outcome} is null
	 * @throws IllegalArgumentException if the stream is not an open formal stream, {@code index} or
	 *   {@code lineage} are not positive, or a category exceeds the cap
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
		for (Map.Entry<String, CategoryCounts> entry : counts.entrySet())
		{
			requireThat(entry.getValue().outcomes(), "counts[" + entry.getKey() + "].outcomes()").
				isLessThanOrEqualTo(cap, "cap");
		}
		counts = Map.copyOf(counts);
		failures = List.copyOf(failures);
	}

	/**
	 * Returns a category's outcomes.
	 *
	 * @param category the category
	 * @return the outcomes, empty if the category has none
	 * @throws NullPointerException if {@code category} is null
	 */
	public CategoryCounts count(String category)
	{
		requireThat(category, "category").isNotNull();
		return counts.getOrDefault(category, CategoryCounts.EMPTY);
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
	 * Returns this attempt with an outcome. An ended attempt never reopens.
	 *
	 * @param ending the outcome
	 * @return the ended attempt
	 * @throws IllegalStateException if the attempt has ended
	 */
	CertificationAttempt end(Outcome ending)
	{
		if (outcome != null)
			throw new IllegalStateException("Certification attempt " + index + " already ended " + outcome.label());
		return new CertificationAttempt(index, lineage, candidate, identity, procedure, counts, failures,
			formalStream, ending);
	}
}
