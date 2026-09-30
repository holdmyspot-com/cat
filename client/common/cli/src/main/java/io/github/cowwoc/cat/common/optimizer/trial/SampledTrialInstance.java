package io.github.cowwoc.cat.common.optimizer.trial;

import io.github.cowwoc.cat.common.optimizer.api.EvidencePurpose;
import io.github.cowwoc.cat.common.optimizer.contract.TestDefinition;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * One concrete instance drawn from a confirmed test definition before candidate assignment.
 *
 * @param drawId identity of this independent draw
 * @param purpose development or formal draw stream
 * @param generatorId sampler implementation recorded for evaluation
 * @param contractId confirmed contract that supplied the definition
 * @param definition selected frozen test definition
 * @param variations selected incidental values in confirmed dimension order
 * @param turns concrete turns after incidental values are filled
 */
public record SampledTrialInstance(String drawId, EvidencePurpose purpose, String generatorId, String contractId,
	TestDefinition definition, List<SelectedVariation> variations, List<String> turns)
{
	/**
	 * Retains a complete, stable instance before a candidate is assigned.
	 *
	 * @param drawId nonblank identity of this draw
	 * @param purpose development or formal stream
	 * @param generatorId nonblank sampler identity
	 * @param contractId nonblank confirmed contract identity
	 * @param definition selected frozen test definition
	 * @param variations selected incidental values in dimension order
	 * @param turns concrete task turns
	 */
	public SampledTrialInstance
	{
		Objects.requireNonNull(purpose, "purpose");
		Objects.requireNonNull(definition, "definition");
		variations = List.copyOf(variations);
		turns = List.copyOf(turns);
		if (drawId == null || drawId.isBlank() || generatorId == null || generatorId.isBlank() ||
			contractId == null || contractId.isBlank() || purpose == EvidencePurpose.VALIDATION ||
			turns.size() != definition.turnTemplates().size() || turns.stream().anyMatch(String::isBlank))
			throw new IllegalArgumentException("Sampled draw identity or concrete turns are invalid");
	}

	/**
	 * One selected incidental value and its frozen probability.
	 *
	 * @param dimensionId confirmed dimension identity
	 * @param placeholder template token filled by the value
	 * @param value selected incidental value
	 * @param probability confirmed probability of that value
	 */
	public record SelectedVariation(String dimensionId, String placeholder, String value,
		BigDecimal probability)
	{
		/**
		 * Retains the exact selected value and confirmed probability.
		 *
		 * @param dimensionId nonblank dimension identity
		 * @param placeholder confirmed template token
		 * @param value selected literal value
		 * @param probability positive confirmed probability at most one
		 */
		public SelectedVariation
		{
			if (dimensionId == null || dimensionId.isBlank() || placeholder == null ||
				placeholder.isBlank() || value == null || value.isBlank() || probability == null ||
				probability.signum() <= 0 || probability.compareTo(BigDecimal.ONE) > 0)
				throw new IllegalArgumentException("Selected variation is invalid");
		}
	}
}
