package io.github.cowwoc.cat.common.optimizer.candidate;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.testng.annotations.Test;

/**
 * Tests {@link CandidateDelivery}.
 */
public final class CandidateDeliveryTest
{
	/**
	 * Creates the test fixture.
	 */
	public CandidateDeliveryTest()
	{
	}

	/**
	 * Applying a selected candidate makes its content and structural prompt edits reviewable while preserving an
	 * unrelated dirty file.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void appliesPromptStructuralChanges() throws IOException
	{
		Path project = Files.createTempDirectory("candidate-delivery");
		try
		{
			write(project, "prompts/keep.md", "before");
			write(project, "prompts/rename.md", "rename");
			write(project, "prompts/delete.md", "delete");
			write(project, "notes.txt", "user bytes\u0000remain");
			PromptSnapshot baseline = PromptSnapshot.capture(project, List.of(Path.of("prompts")));

			Path candidateProject = Files.createTempDirectory("candidate-source");
			try
			{
				write(candidateProject, "prompts/keep.md", "after");
				write(candidateProject, "prompts/renamed.md", "rename");
				write(candidateProject, "prompts/new.md", "new");
				PromptSnapshot candidate = PromptSnapshot.capture(candidateProject, List.of(Path.of("prompts")));

				try (CandidateDeliveryFixture fixture = new CandidateDeliveryFixture(project,
					Map.of(baseline.id(), baseline, candidate.id(), candidate), List.of(Path.of("prompts"))))
				{
					CandidateApplication result = fixture.delivery().reconcile(baseline.id(), null, candidate.id());

				requireThat(result.diff().changes().size(), "changeCount").isEqualTo(5);
				requireThat(Files.readString(project.resolve("prompts/keep.md")), "changedPrompt").isEqualTo("after");
				requireThat(Files.exists(project.resolve("prompts/rename.md")), "oldName").isFalse();
				requireThat(Files.readString(project.resolve("prompts/renamed.md")), "newName").isEqualTo("rename");
				requireThat(Files.exists(project.resolve("prompts/delete.md")), "deletedPrompt").isFalse();
				requireThat(Files.readAllBytes(project.resolve("notes.txt")), "unrelatedDirtyBytes").isEqualTo(
					"user bytes\u0000remain".getBytes(UTF_8));
				}
			}
			finally
			{
				TemporaryDirectories.deleteRecursively(candidateProject);
			}
		}
		finally
		{
			TemporaryDirectories.deleteRecursively(project);
		}
	}

	/**
	 * A stale predecessor rejects the complete replacement and leaves every prompt byte untouched.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*predecessor.*")
	public void rejectsStalePredecessor() throws IOException
	{
		Path project = Files.createTempDirectory("candidate-stale");
		try
		{
			write(project, "prompts/first.md", "first");
			write(project, "prompts/second.md", "second");
			PromptSnapshot baseline = PromptSnapshot.capture(project, List.of(Path.of("prompts")));
			write(project, "prompts/first.md", "concurrent");

			Path source = Files.createTempDirectory("candidate-stale-source");
			try
			{
				write(source, "prompts/first.md", "next first");
				write(source, "prompts/second.md", "next second");
				PromptSnapshot candidate = PromptSnapshot.capture(source, List.of(Path.of("prompts")));
				try (CandidateDeliveryFixture fixture = new CandidateDeliveryFixture(project,
					Map.of(baseline.id(), baseline, candidate.id(), candidate), List.of(Path.of("prompts"))))
				{
					fixture.delivery().reconcile(baseline.id(), null, candidate.id());
				}
				finally
				{
					requireThat(Files.readString(project.resolve("prompts/first.md")), "concurrentContent").isEqualTo(
						"concurrent");
					requireThat(Files.readString(project.resolve("prompts/second.md")), "secondContent").isEqualTo("second");
				}
			}
			finally
			{
				TemporaryDirectories.deleteRecursively(source);
			}
		}
		finally
		{
			TemporaryDirectories.deleteRecursively(project);
		}
	}

	/**
	 * Replacing one selected revision changes only optimizer paths, so plateau, formal failure, and interruption can
	 * retain the selected revision for user Git actions.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void replacesOnlyPreviousRevision() throws IOException
	{
		Path project = Files.createTempDirectory("candidate-revision");
		try
		{
			write(project, "prompts/rule.md", "base");
			write(project, "user.txt", "dirty");
			PromptSnapshot baseline = PromptSnapshot.capture(project, List.of(Path.of("prompts")));
			PromptSnapshot first = snapshot("candidate-first", "first");
			PromptSnapshot second = snapshot("candidate-second", "second");
			try (CandidateDeliveryFixture fixture = new CandidateDeliveryFixture(project,
				Map.of(baseline.id(), baseline, first.id(), first, second.id(), second), List.of(Path.of("prompts"))))
			{
				fixture.delivery().reconcile(baseline.id(), null, first.id());
				fixture.delivery().reconcile(baseline.id(), first.id(), second.id());
			}

			requireThat(Files.readString(project.resolve("prompts/rule.md")), "visibleSecondRevision").isEqualTo("second");
			requireThat(Files.readString(project.resolve("user.txt")), "preExistingDirtyContent").isEqualTo("dirty");
		}
		finally
		{
			TemporaryDirectories.deleteRecursively(project);
		}
	}

	/**
	 * Captures one isolated prompt revision.
	 *
	 * @param prefix the temporary-directory prefix
	 * @param content the prompt content
	 * @return the captured revision
	 */
	private static PromptSnapshot snapshot(String prefix, String content) throws IOException
	{
		Path source = Files.createTempDirectory(prefix);
		try
		{
			write(source, "prompts/rule.md", content);
			return PromptSnapshot.capture(source, List.of(Path.of("prompts")));
		}
		finally
		{
			TemporaryDirectories.deleteRecursively(source);
		}
	}

	/**
	 * Writes a fixture file.
	 *
	 * @param root the fixture root
	 * @param relativePath the fixture-relative file path
	 * @param content the exact file content
	 */
	private static void write(Path root, String relativePath, String content) throws IOException
	{
		Path file = root.resolve(relativePath);
		Files.createDirectories(file.getParent());
		Files.writeString(file, content, UTF_8);
	}
}
