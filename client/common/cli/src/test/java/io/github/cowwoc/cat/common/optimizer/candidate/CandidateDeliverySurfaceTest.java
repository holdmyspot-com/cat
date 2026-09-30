package io.github.cowwoc.cat.common.optimizer.candidate;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.lang.reflect.Constructor;
import java.util.Arrays;
import org.testng.annotations.Test;

/** Verifies obsolete direct-replacement entry points are not publicly reachable. */
public final class CandidateDeliverySurfaceTest
{
	/** Only the campaign-pinned delivery constructor may remain public. */
	@Test
	public void exposesOnlyCampaignBoundConstructor()
	{
		Constructor<?>[] constructors = CandidateDelivery.class.getConstructors();
		requireThat(Arrays.stream(constructors).noneMatch(constructor -> constructor.getParameterCount() == 3),
			"legacyConstructorAbsent").isTrue();
		requireThat(Arrays.stream(CandidateDelivery.class.getMethods()).noneMatch(method ->
			method.getName().equals("replace")), "legacyReplaceAbsent").isTrue();
	}
}
