package io.github.cowwoc.cat.common.optimization;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.expectThrows;

import org.testng.annotations.Test;

/** Verifies exact conversion at the untyped ingress for closed domains. */
public final class ClosedDomainTypesTest
{
	/** Creates the test fixture. */
	public ClosedDomainTypesTest()
	{
	}

	/** Verifies exact accepted external values. */
	@Test
	public void acceptsExactValues()
	{
		assertEquals(HarnessKind.fromExternalValue("codex"), HarnessKind.CODEX);
		assertEquals(EvaluatorVerdict.fromExternalValue("pass"), EvaluatorVerdict.PASS);
		assertEquals(CertificationProcessState.fromExternalValue("active"), CertificationProcessState.ACTIVE);
		assertEquals(OperationState.fromExternalValue("completed"), OperationState.COMPLETED);
	}

	/** Verifies unsupported external values are rejected. */
	@Test
	public void rejectsExcludedValues()
	{
		expectThrows(IllegalArgumentException.class, () -> HarnessKind.fromExternalValue("CODEX"));
		expectThrows(IllegalArgumentException.class, () -> EvaluatorVerdict.fromExternalValue("unknown"));
		expectThrows(IllegalArgumentException.class, () -> CertificationProcessState.fromExternalValue("paused"));
		expectThrows(IllegalArgumentException.class, () -> OperationState.fromExternalValue("terminal"));
	}
}
