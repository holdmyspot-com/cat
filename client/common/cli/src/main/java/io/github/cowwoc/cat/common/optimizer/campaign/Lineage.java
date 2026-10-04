package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.contract.Contract;
import io.github.cowwoc.cat.common.optimizer.contract.DrawStream;
import io.github.cowwoc.cat.common.optimizer.inventory.Sha256;

import java.util.List;
import java.util.Set;

/**
 * The evidence history under one confirmed contract. Revising the contract starts a new history rather than reusing
 * earlier results for certification.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param number the lineage's number within the campaign, from 1
 * @param contract the confirmed contract, with the certification scope that the user chose
 * @param contractHash the contract's identity
 * @param development the development draw stream
 * @param baseline the baseline-arm draw stream
 * @param regressionSet the instances that failed sampled trials under this contract, replayed to check later edits,
 *   in failure order
 * @param unavailable the known conditions that make certification unavailable under this lineage
 */
public record Lineage(int number, Contract contract, Sha256 contractHash, DrawStream.State development,
	DrawStream.State baseline, List<RegressionInstance> regressionSet, Set<Outcome> unavailable)
{
	/**
	 * Creates a lineage.
	 *
	 * @param number the lineage's number within the campaign, from 1
	 * @param contract the confirmed contract, with the certification scope that the user chose
	 * @param contractHash the contract's identity
	 * @param development the development draw stream
	 * @param baseline the baseline-arm draw stream
	 * @param regressionSet the instances that failed sampled trials under this contract, replayed to check later edits,
	 *   in failure order
	 * @param unavailable the known conditions that make certification unavailable under this lineage
	 * @throws NullPointerException if any argument is null
	 * @throws IllegalArgumentException if {@code number} is not positive, {@code contract} has no certification
	 *   scope, a stream has the wrong kind, or {@code unavailable} holds an outcome that does not report
	 *   unavailability
	 */
	public Lineage
	{
		requireThat(number, "number").isPositive();
		requireThat(contract, "contract").isNotNull();
		if (contract.profiles().scope() == null)
			throw new IllegalArgumentException("A lineage's contract needs a certification scope");
		requireThat(contractHash, "contractHash").isNotNull();
		requireThat(development, "development").isNotNull();
		requireThat(development.kind(), "development.kind()").isEqualTo(DrawStream.Kind.DEVELOPMENT);
		requireThat(baseline, "baseline").isNotNull();
		requireThat(baseline.kind(), "baseline.kind()").isEqualTo(DrawStream.Kind.BASELINE);
		requireThat(regressionSet, "regressionSet").isNotNull();
		requireThat(unavailable, "unavailable").isNotNull();
		for (Outcome outcome : unavailable)
			requireThat(outcome.isUnavailable(), outcome + ".isUnavailable()").isTrue();
		regressionSet = List.copyOf(regressionSet);
		unavailable = Set.copyOf(unavailable);
	}
}
