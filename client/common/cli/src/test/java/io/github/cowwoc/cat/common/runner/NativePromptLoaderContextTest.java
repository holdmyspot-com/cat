package io.github.cowwoc.cat.common.runner;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.testng.annotations.Test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Tests {@link NativePromptLoaderContext} selector and ownership validation.
 */
public final class NativePromptLoaderContextTest
{
	/**
	 * Creates the test fixture.
	 */
	public NativePromptLoaderContextTest()
	{
	}

	/**
	 * Records known values and explicit unavailability with immutable selector order.
	 */
	@Test
	public void retainsTypedSelectorFacts()
	{
		List<NativePromptLoaderContext.SelectorFact> mutable = new ArrayList<>();
		mutable.add(new NativePromptLoaderContext.SelectorFact(
			NativePromptLoaderContext.SelectorKind.CODEX_FALLBACK_FILENAMES,
			NativePromptLoaderContext.SelectorStatus.KNOWN, "generated config",
			Optional.of(new NativePromptLoaderContext.Names(List.of("AGENTS.md", "OTHER.md")))));
		mutable.add(new NativePromptLoaderContext.SelectorFact(
			NativePromptLoaderContext.SelectorKind.CLAUDE_PROJECT_INSTRUCTION_MODE,
			NativePromptLoaderContext.SelectorStatus.UNAVAILABLE, "isolated settings", Optional.empty()));
		NativePromptLoaderContext context = new NativePromptLoaderContext(1, "codex-run",
			NativePromptLoaderContext.Harness.CODEX,
			Optional.empty(), Path.of("/tmp/project"), Path.of("/tmp/project"), "a".repeat(40),
			List.of(Path.of("/tmp/project")), mutable);
		mutable.clear();

		requireThat(context.orderedSelectorFacts().size(), "facts.size").isEqualTo(2);
		requireThat(context.observedNativeVersion(), "observedNativeVersion").isEqualTo(Optional.empty());
	}

	/**
	 * Seals and rereads exactly the effective selector values, preserving list order across a restart.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void sealsAndReadsContext() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("native-loader-context-"));
			Path artifact = root.resolve("artifacts/loader-context.json");
			NativePromptLoaderContext context = new NativePromptLoaderContext(1, "codex-run",
				NativePromptLoaderContext.Harness.CODEX, Optional.of("1.2.3"), root.resolve("project"),
				root.resolve("project"), "a".repeat(40), List.of(root.resolve("project")), List.of(
				new NativePromptLoaderContext.SelectorFact(
					NativePromptLoaderContext.SelectorKind.CODEX_FALLBACK_FILENAMES,
					NativePromptLoaderContext.SelectorStatus.KNOWN, "generated config",
					Optional.of(new NativePromptLoaderContext.Names(List.of("AGENTS.md", "OTHER.md")))),
				new NativePromptLoaderContext.SelectorFact(
					NativePromptLoaderContext.SelectorKind.CODEX_DOCUMENT_BYTE_CAP,
					NativePromptLoaderContext.SelectorStatus.KNOWN, "generated config",
					Optional.of(new NativePromptLoaderContext.ByteCap(4096)))));
			try (HarnessRunnerScope scope = new HarnessRunnerScope())
			{
				context.seal(scope.json(), artifact);
				requireThat(NativePromptLoaderContext.read(scope.json(), artifact), "reloaded").isEqualTo(context);
				context.seal(scope.json(), artifact);
			}
		}
	}

	/**
	 * Rejects a value whose shape does not match its selector kind.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class,
		expectedExceptionsMessageRegExp = "selector value does not match kind CODEX_DOCUMENT_BYTE_CAP")
	public void rejectsWrongSelectorShape()
	{
		new NativePromptLoaderContext.SelectorFact(NativePromptLoaderContext.SelectorKind.CODEX_DOCUMENT_BYTE_CAP,
			NativePromptLoaderContext.SelectorStatus.KNOWN, "generated config",
			Optional.of(new NativePromptLoaderContext.Names(List.of("AGENTS.md"))));
	}

	/**
	 * Refuses a missing value for a known selector.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class,
		expectedExceptionsMessageRegExp = "value must be present exactly when selector is known")
	public void rejectsKnownSelectorWithoutValue()
	{
		new NativePromptLoaderContext.SelectorFact(NativePromptLoaderContext.SelectorKind.CLAUDE_MEMORY_LOADING,
			NativePromptLoaderContext.SelectorStatus.KNOWN, "native default", Optional.empty());
	}
}
