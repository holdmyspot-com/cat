package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.contract.Contract;
import io.github.cowwoc.cat.common.optimizer.contract.DrawStream;
import io.github.cowwoc.cat.common.optimizer.inventory.Sha256;

import java.util.List;
import java.util.Set;

/**
 * The evidence history under one confirmed contract.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param number the lineage's number within the campaign, from 1
 * @param contract the confirmed contract
 * @param contractHash the contract's identity
 * @param development the development draw stream
 * @param baseline the baseline-arm draw stream
 * @param regressionSet every instance that failed representatively under this lineage, in failure order
 * @param unavailable the known conditions that make certification unavailable under this lineage
 */
public record Lineage(int number, Contract contract, Sha256 contractHash, DrawStream.State development,
	DrawStream.State baseline, List<RegressionInstance> regressionSet, Set<Outcome> unavailable)
{
	/**
	 * Creates a lineage.
	 *
	 * @param number the lineage's number within the campaign, from 1
	 * @param contract the confirmed contract
	 * @param contractHash the contract's identity
	 * @param development the development draw stream
	 * @param baseline the baseline-arm draw stream
	 * @param regressionSet every instance that failed representatively under this lineage, in failure order
	 * @param unavailable the known conditions that make certification unavailable under this lineage
	 * @throws NullPointerException if any argument is null
	 * @throws IllegalArgumentException if {@code number} is not positive, a stream has the wrong kind, or
	 *   {@code unavailable} holds an outcome that does not report unavailability
	 */
	public Lineage
	{
		requireThat(number, "number").isPositive();
		requireThat(contract, "contract").isNotNull();
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
