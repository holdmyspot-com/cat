package io.github.cowwoc.cat.common.optimizer.api;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import org.testng.annotations.Test;

/**
 * Tests evidence references used at the runner boundary.
 */
public final class EvidenceRefTest
{
	/**
	 * Creates a test instance.
	 */
	public EvidenceRefTest()
	{
	}

	/**
	 * Verifies a lowercase collection and exact SHA-256 identity are accepted.
	 */
	@Test
	public void acceptsCanonicalEvidenceAddress()
	{
		String id = "a".repeat(64);
		EvidenceRef reference = new EvidenceRef("native-events", id);
		requireThat(reference.collection(), "collection").isEqualTo("native-events");
		requireThat(reference.evidenceId(), "evidenceId").isEqualTo(id);
	}

	/**
	 * Verifies collection names outside the declared grammar are rejected.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void rejectsUppercaseCollection()
	{
		new EvidenceRef("NativeEvents", "a".repeat(64));
	}

	/**
	 * Verifies shortened content identities cannot address evidence.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void rejectsShortEvidenceIdentity()
	{
		new EvidenceRef("native-events", "a".repeat(63));
	}
}
