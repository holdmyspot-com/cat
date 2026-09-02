package io.github.cowwoc.cat.common.optimization.evaluator;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.testng.annotations.Test;

/** Tests that semantic formal evidence has an explicit calibration-bound protocol per assertion family. */
public final class EvaluatorProtocolRegistryTest
{
	/** Creates the test fixture. */
	public EvaluatorProtocolRegistryTest()
	{
	}

	/** Verifies missing calibration is explicitly uncertifiable. */
	@Test
	public void reportsMissingCalibration()
	{
		EvaluatorProtocolRegistry registry = new EvaluatorProtocolRegistry(List.of(
			new EvaluatorProtocolRegistry.Assignment("family-clear", Optional.empty())));

		EvaluatorProtocolRegistry.Preflight result = registry.preflight(Set.of("family-clear"));

		requireThat(result.status(), "status").isEqualTo(EvaluatorProtocolRegistry.Status.UNCERTIFIABLE);
		requireThat(result.unapprovedAssertionFamilyIdentifiers(), "unapprovedFamilies").
			containsExactly(List.of("family-clear"));
	}

	/** Verifies a family without an assignment cannot silently receive formal evidence. */
	@Test(expectedExceptions = IllegalArgumentException.class,
		expectedExceptionsMessageRegExp = ".*No evaluator protocol assignment: family-missing.*")
	public void rejectsUnassignedFamily()
	{
		new EvaluatorProtocolRegistry(List.of()).preflight(Set.of("family-missing"));
	}

	/** Verifies the initial policy encodes the release calibration and adjudication thresholds. */
	@Test
	public void exposesInitialPolicy()
	{
		EvaluatorPolicy.Configuration configuration = EvaluatorPolicy.initial().configuration();

		requireThat(configuration.minimumAccuracy(), "minimumAccuracy").isEqualTo(0.95);
		requireThat(configuration.maximumFalsePassRate(), "maximumFalsePassRate").isEqualTo(0.02);
		requireThat(configuration.minimumAdjudicationAccuracyImprovement(), "adjudicationImprovement").
			isEqualTo(0.02);
		requireThat(configuration.maximumAdjudicationTokenMultiplier(), "maximumTokenMultiplier").isEqualTo(2.5);
	}
}
