package io.github.cowwoc.cat.common.optimizer.state;

import io.github.cowwoc.cat.common.optimizer.contract.Category;
import io.github.cowwoc.cat.common.optimizer.contract.ConfirmedContract;
import io.github.cowwoc.cat.common.optimizer.statistics.DevelopmentEvidenceDecision;
import java.io.IOException;
import java.util.Map;
import java.util.Objects;

/**
 * Stops new representative draws when counted current-candidate evidence is clearly weak.
 */
final class DevelopmentWeaknessGuard
{
	/** Prevents construction. */
	private DevelopmentWeaknessGuard()
	{
	}

	/**
	 * Applies the confirmed weakness rule to all measured categories.
	 *
	 * @param evidence current candidate and measurement-epoch evidence
	 * @param contract confirmed readiness rule and category set
	 * @throws IOException if a category needs diagnosis before another draw
	 */
	static void requireNoClearlyWeakCategory(DevelopmentEvidenceService.Snapshot evidence,
		ConfirmedContract contract) throws IOException
	{
		Objects.requireNonNull(evidence, "evidence");
		Objects.requireNonNull(contract, "contract");
		Map<String, DevelopmentEvidenceService.CategorySeries> series = evidence.categorySeries(
			contract.developmentAllocationPolicy().batchSize());
		for (Category category : contract.categories())
		{
			DevelopmentEvidenceService.CategorySeries categorySeries = series.get(category.id());
			if (categorySeries == null)
				continue;
			DevelopmentEvidenceDecision.Status status = DevelopmentEvidenceDecision.classify(category.id(),
				new DevelopmentEvidenceDecision.CategoryEvidence(categorySeries.total(),
					categorySeries.completedBatches(), false, false), contract.developmentReadiness(),
				contract.developmentAllocationPolicy());
			if (status == DevelopmentEvidenceDecision.Status.WEAK)
				throw new IOException("Clearly weak development category requires diagnosis before another draw: " +
					category.id());
		}
	}
}
