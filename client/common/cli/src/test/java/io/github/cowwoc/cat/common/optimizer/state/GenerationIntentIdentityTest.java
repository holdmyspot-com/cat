package io.github.cowwoc.cat.common.optimizer.state;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import java.io.IOException;
import java.nio.file.Path;
import org.testng.annotations.Test;

/** Checks exact generation-intent identity independently of current worktree authorization. */
public final class GenerationIntentIdentityTest
{
	/** Creates the identity test. */
	public GenerationIntentIdentityTest()
	{
	}

	/**
	 * Equal intent values pass the identity check.
	 *
	 * @throws IOException if matching intents are rejected
	 */
	@Test
	public void acceptsMatchingIntent() throws IOException
	{
		GenerationAttemptIntentService.requireMatchingIntent(intent("generation-1"), intent("generation-1"));
	}

	/**
	 * An intent for another call cannot substitute for the retained intent.
	 *
	 * @throws IOException if the supplied intent differs
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*differs from its retained reference.*")
	public void rejectsForeignIntent() throws IOException
	{
		GenerationAttemptIntentService.requireMatchingIntent(intent("generation-1"), intent("generation-2"));
	}

	/**
	 * Builds an intent without creating its source records or worktrees.
	 *
	 * @param callId call identity being compared
	 * @return typed intent with fixed source identities
	 */
	private static GenerationAttemptIntent intent(String callId)
	{
		Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
		return new GenerationAttemptIntent(GenerationAttemptIntent.SCHEMA_VERSION, "campaign",
			new LineageKey("lineage", "contract"),
			new EvidenceRef("development-round-starts", "a".repeat(64)), "first", callId, "b".repeat(64),
			new EvidenceRef("measurement-epochs", "c".repeat(64)),
			new EvidenceRef("confirmed-editable-roots", "d".repeat(64)),
			new EvidenceRef("generation-selected-inventories", "e".repeat(64)),
			root.resolve("selected"), root.resolve("private"));
	}
}
