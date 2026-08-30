package io.github.cowwoc.cat.common.evaluation;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.ToolScope;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.testng.annotations.Test;

/**
 * Tests immutable candidate-bundle materialization.
 */
public final class CandidateBundleTest
{
	/**
	 * Creates the test fixture.
	 */
	public CandidateBundleTest()
	{
	}

	/**
	 * Verifies a candidate bundle contains only selected rules and the declared route replacements.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void materializesSelectedRules() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories(); ToolScope scope = new ToolScope())
		{
			Path root = cleanup.add(Files.createTempDirectory("candidate-bundle"));
			Path sourceRules = write(root, "source-rules/common/write-clearly.md", "Current rule\n").getParent().
				getParent();
			write(root, "source-rules/common/default-rules.md", "Router\n");
			write(root, "source-rules/include/common/unused.md", "Unused\n");
			Path bundle = root.resolve("candidate");

			CandidateBundle.Candidate candidate = CandidateBundle.materialize(scope.json(), sourceRules, bundle,
				"common/write-clearly.md", List.of("common/default-rules.md"),
				Map.of("common/target-audience.md", "common/write-clearly.md"));

			requireThat(Files.readString(bundle.resolve("rules/common/write-clearly.md")), "primaryContents").
				isEqualTo("Current rule\n");
			requireThat(Files.readString(bundle.resolve("rules/common/default-rules.md")), "routerContents").
				isEqualTo("Router\n");
			requireThat(Files.exists(bundle.resolve("rules/include/common/unused.md")), "unusedRule").isFalse();
			requireThat(candidate.routeReplacements().get("common/target-audience.md"), "routeReplacement").
				isEqualTo("common/write-clearly.md");
			requireThat(CandidateBundle.read(scope.json(), bundle).sha256(), "candidateFingerprint").
				isEqualTo(candidate.sha256());
		}
	}

	/**
	 * Writes one fixture file.
	 *
	 * @param root the fixture root
	 * @param name the root-relative file name
	 * @param contents the file content
	 * @return the written file
	 * @throws IOException if the fixture cannot be written
	 */
	private static Path write(Path root, String name, String contents) throws IOException
	{
		Path file = root.resolve(name);
		Files.createDirectories(file.getParent());
		Files.writeString(file, contents);
		return file;
	}
}
