package io.github.cowwoc.cat.common.optimization.promptsystem;

import java.util.List;
import org.testng.annotations.Test;

import static org.testng.Assert.assertThrows;

/** Verifies prompt-system contract value boundaries. */
public final class PromptSystemDocumentTest
{
	/** Creates the test owner. */
	public PromptSystemDocumentTest()
	{
	}

	/** Verifies the five owners accept their declared domains. */
	@Test
	public void acceptsDeclaredDomains()
	{
		String sha = "a".repeat(64);
		ObservationInventoryDocument.Artifact artifact = new ObservationInventoryDocument.Artifact("a", "AGENTS.md",
			sha, "always", true, List.of(), List.of());
		new ObservationInventoryDocument(1, "inventory", sha,
			List.of(new ObservationInventoryDocument.Invocation("invocation", "codex", "AGENTS.md", List.of("a"))),
			List.of(artifact));
		new OptimizationTargetDocument(1, "target", "AGENTS.md", sha, List.of("prompt-test"));
		new EditBoundaryDocument(1, "boundary", List.of("a"), List.of("."), List.of());
		new RelatedPromptEvidenceDocument(1, "evidence", "a", "target", "loads_with", List.of("a"), List.of(),
			sha);
		new PromptSystemChangeManifest(1, "candidate", "target", "boundary",
			List.of(new PromptSystemChangeManifest.Change("modify", "AGENTS.md", "AGENTS.md", sha, sha,
				List.of("evidence"))));
	}

	/** Verifies closed external domains reject unsupported values. */
	@Test
	public void rejectsUnsupportedDomains()
	{
		String sha = "a".repeat(64);
		assertThrows(IllegalArgumentException.class, () -> new ObservationInventoryDocument.Artifact("a", "AGENTS.md",
			sha, "sometimes", true, List.of(), List.of()));
		assertThrows(IllegalArgumentException.class, () -> new RelatedPromptEvidenceDocument(1, "e", "a", "t",
			"depends_on", List.of(), List.of(), sha));
		assertThrows(IllegalArgumentException.class, () -> new PromptSystemChangeManifest.Change("replace", "a", null,
			sha, null, List.of()));
	}
}
