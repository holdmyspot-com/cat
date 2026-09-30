package io.github.cowwoc.cat.common.optimizer.loading;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/** Tests exact-byte loading-evidence validation. */
public final class PromptLoadingEvidenceValidatorTest
{
	/** Creates the test fixture. */
	public PromptLoadingEvidenceValidatorTest()
	{
	}

	/**
	 * Accepts every supported production loading mode and retains ordered source paths.
	 *
	 * @param mode nested source loading mode
	 * @throws IOException if hashing fails
	 */
	@Test(dataProvider = "loadModes")
	public void acceptsSupportedLoadingMode(LoadMode mode) throws IOException
	{
		Fixture fixture = fixture(mode);

		ValidatedPromptLoading validated = PromptLoadingEvidenceValidator.validate(fixture.evidence(),
			fixture.content()::get);

		requireThat(validated.sources().stream().map(io.github.cowwoc.cat.common.optimizer.contract.PromptSource::path).
			toList(), "paths").isEqualTo(
			List.of(Path.of("prompt.md"), Path.of("rules.md")));
		requireThat(validated.loadingEvidenceId().length(), "loadingEvidenceIdLength").isEqualTo(64);
	}

	/**
	 * Gives caller bindings in a different order and derives the same receipt-ordered loading identity.
	 *
	 * @throws IOException if evidence hashing fails
	 */
	@Test
	public void canonicalizesBindingsIntoReceiptOrder() throws IOException
	{
		Fixture fixture = fixture(LoadMode.CONDITIONAL);
		ValidatedPromptLoading original = PromptLoadingEvidenceValidator.validate(fixture.evidence(),
			fixture.content()::get);
		List<PromptSourceBinding> reversed = List.of(fixture.evidence().bindings().get(1),
			fixture.evidence().bindings().getFirst());
		PromptLoadingEvidence reordered = new PromptLoadingEvidence(fixture.evidence().targetPath(),
			fixture.evidence().context(), reversed);

		ValidatedPromptLoading canonical = PromptLoadingEvidenceValidator.validate(reordered, fixture.content()::get);

		requireThat(canonical.loadingEvidenceId(), "loadingEvidenceId").isEqualTo(original.loadingEvidenceId());
		requireThat(canonical.sources(), "sources").isEqualTo(original.sources());
	}

	/**
	 * Supplies all supported loading modes.
	 *
	 * @return loading modes
	 */
	@DataProvider
	public Object[][] loadModes()
	{
		return new Object[][] {{LoadMode.DIRECT}, {LoadMode.CONDITIONAL}, {LoadMode.LAZY}};
	}

	/** Rejects a missing binding. */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void rejectsMissingBinding() throws IOException
	{
		Fixture fixture = fixture(LoadMode.CONDITIONAL);
		PromptLoadingEvidence changed = new PromptLoadingEvidence(Path.of("prompt.md"), fixture.evidence().context(),
			List.of(fixture.evidence().bindings().getFirst()));
		PromptLoadingEvidenceValidator.validate(changed, fixture.content()::get);
	}

	/** Rejects an unused extra binding. */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void rejectsExtraBinding() throws IOException
	{
		Fixture fixture = fixture(LoadMode.CONDITIONAL);
		List<PromptSourceBinding> bindings = List.of(fixture.evidence().bindings().getFirst(),
			fixture.evidence().bindings().get(1), new PromptSourceBinding("extra", Path.of("extra.md")));
		PromptLoadingEvidence changed = new PromptLoadingEvidence(Path.of("prompt.md"), fixture.evidence().context(),
			bindings);
		PromptLoadingEvidenceValidator.validate(changed, fixture.content()::get);
	}

	/** Rejects a duplicated binding. */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void rejectsDuplicateBinding() throws IOException
	{
		Fixture fixture = fixture(LoadMode.CONDITIONAL);
		PromptSourceBinding first = fixture.evidence().bindings().getFirst();
		PromptLoadingEvidence changed = new PromptLoadingEvidence(Path.of("prompt.md"), fixture.evidence().context(),
			List.of(first, first));
		PromptLoadingEvidenceValidator.validate(changed, fixture.content()::get);
	}

	/** Rejects a target receipt bound to a different path. */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void rejectsTargetPathMismatch() throws IOException
	{
		Fixture fixture = fixture(LoadMode.CONDITIONAL);
		PromptLoadingEvidence changed = new PromptLoadingEvidence(Path.of("other.md"), fixture.evidence().context(),
			fixture.evidence().bindings());
		PromptLoadingEvidenceValidator.validate(changed, fixture.content()::get);
	}

	/** Rejects retained bytes whose hash differs from the receipt. */
	@Test(expectedExceptions = IOException.class)
	public void rejectsContentHashMismatch() throws IOException
	{
		Fixture fixture = fixture(LoadMode.CONDITIONAL);
		Map<String, byte[]> changed = new HashMap<>(fixture.content());
		changed.put(fixture.evidence().context().receipts().getFirst().contentEvidenceId(),
			"changed".getBytes(StandardCharsets.UTF_8));
		PromptLoadingEvidenceValidator.validate(fixture.evidence(), changed::get);
	}

	/** Rejects an unavailable retained content blob. */
	@Test(expectedExceptions = IOException.class)
	public void rejectsMissingContent() throws IOException
	{
		Fixture fixture = fixture(LoadMode.CONDITIONAL);
		PromptLoadingEvidenceValidator.validate(fixture.evidence(), id ->
		{
			throw new IOException("missing: " + id);
		});
	}

	/** Rejects a graph with no target source. */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void rejectsMissingTarget() throws IOException
	{
		Fixture fixture = fixture(LoadMode.CONDITIONAL);
		List<PromptSourceReceipt> receipts = fixture.evidence().context().receipts();
		PromptSourceReceipt first = receipts.getFirst();
		PromptSourceReceipt changedFirst = new PromptSourceReceipt(first.conversationId(), first.sourceId(),
			SourceOrigin.PROJECT_RULE, first.sha256(), first.contentEvidenceId(), first.loadOrdinal(), first.parentSourceId(),
			first.loadMode());
		PromptLoadingEvidence changed = new PromptLoadingEvidence(Path.of("prompt.md"),
			new LoadedPromptContext("loaded", List.of(changedFirst, receipts.get(1))), fixture.evidence().bindings());
		PromptLoadingEvidenceValidator.validate(changed, fixture.content()::get);
	}

	/**
	 * Creates a two-source exact-byte loading fixture.
	 *
	 * @param mode nested loading mode
	 * @return fixture
	 * @throws IOException if SHA-256 is unavailable
	 */
	private static Fixture fixture(LoadMode mode) throws IOException
	{
		byte[] target = "target".getBytes(StandardCharsets.UTF_8);
		byte[] rule = "rule".getBytes(StandardCharsets.UTF_8);
		String targetHash = sha256(target);
		String ruleHash = sha256(rule);
		List<PromptSourceReceipt> receipts = List.of(
			new PromptSourceReceipt("conversation", "target", SourceOrigin.TARGET, targetHash, targetHash, 0, null,
				LoadMode.DIRECT),
			new PromptSourceReceipt("conversation", "rule", SourceOrigin.PROJECT_RULE, ruleHash, ruleHash, 1, "target",
				mode));
		PromptLoadingEvidence evidence = new PromptLoadingEvidence(Path.of("prompt.md"),
			new LoadedPromptContext("loaded", receipts), List.of(
				new PromptSourceBinding("target", Path.of("prompt.md")),
				new PromptSourceBinding("rule", Path.of("rules.md"))));
		return new Fixture(evidence, Map.of(targetHash, target, ruleHash, rule));
	}

	/**
	 * Computes a source byte identity.
	 *
	 * @param content source bytes
	 * @return lowercase SHA-256 identity
	 * @throws IOException if SHA-256 is unavailable
	 */
	private static String sha256(byte[] content) throws IOException
	{
		try
		{
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
		}
		catch (NoSuchAlgorithmException e)
		{
			throw new IOException("SHA-256 is unavailable", e);
		}
	}

	/**
	 * Exact-byte loading fixture.
	 *
	 * @param evidence captured loading evidence
	 * @param content content by SHA-256
	 */
	private record Fixture(PromptLoadingEvidence evidence, Map<String, byte[]> content)
	{
	}
}
