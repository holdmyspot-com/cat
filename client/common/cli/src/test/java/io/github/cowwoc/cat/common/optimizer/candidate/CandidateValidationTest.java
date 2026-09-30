package io.github.cowwoc.cat.common.optimizer.candidate;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.testng.annotations.Test;

/**
 * Tests candidate validation at the isolated-project boundary.
 */
public final class CandidateValidationTest
{
	/**
	 * Creates the test fixture.
	 */
	public CandidateValidationTest()
	{
	}

	/**
	 * A changed non-prompt file is rejected before the separate user worktree can be touched.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*non-prompt.*")
	public void rejectsNonPromptWriteBeforeDelivery() throws IOException
	{
		Path baseline = Files.createTempDirectory("candidate-baseline");
		Path candidate = Files.createTempDirectory("candidate-returned");
		Path userWorktree = Files.createTempDirectory("candidate-user-worktree");
		try
		{
			write(baseline, "prompts/rule.md", "base");
			write(baseline, "user.txt", "unchanged");
			write(candidate, "prompts/rule.md", "candidate");
			write(candidate, "user.txt", "candidate non-prompt write");
			write(userWorktree, "user.txt", "user bytes\u0000remain");
			try
			{
				CandidateDiff.validate(baseline, candidate, List.of(Path.of("prompts")));
			}
			finally
			{
				requireThat(Files.readAllBytes(userWorktree.resolve("user.txt")), "userWorktreeBytes").isEqualTo(
					"user bytes\u0000remain".getBytes(UTF_8));
			}
		}
		finally
		{
			TemporaryDirectories.deleteRecursively(baseline);
			TemporaryDirectories.deleteRecursively(candidate);
			TemporaryDirectories.deleteRecursively(userWorktree);
		}
	}

	/**
	 * Confirmed roots must be project-relative and cannot escape the project.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*project-relative.*")
	public void rejectsAbsoluteConfirmedRoot() throws IOException
	{
		Path project = Files.createTempDirectory("candidate-absolute-root");
		try
		{
			write(project, "prompts/rule.md", "base");
			PromptSnapshot snapshot = PromptSnapshot.capture(project, List.of(Path.of("prompts")));
			CandidateDiff.validate(snapshot, snapshot, List.of(project.resolve("prompts")));
		}
		finally
		{
			TemporaryDirectories.deleteRecursively(project);
		}
	}

	/**
	 * Capturing a confirmed file path after its deletion records absence without including its sibling.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void capturesDeletedFileRootAsAbsent() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("candidate-file-root-"));
			write(project, "prompts/rule.md", "original");
			write(project, "prompts/sibling.md", "untouched");
			Path fileRoot = Path.of("prompts/rule.md");
			PromptSnapshot before = PromptSnapshot.capture(project, List.of(fileRoot));

			Files.delete(project.resolve(fileRoot));
			PromptSnapshot after = PromptSnapshot.capture(project, List.of(fileRoot));

			requireThat(before.entries().keySet(), "beforePaths").isEqualTo(Set.of(fileRoot));
			requireThat(after.entries().isEmpty(), "afterEntriesEmpty").isTrue();
		}
	}

	/**
	 * Snapshot capture rejects a confirmed file whose parent redirects outside the project.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*Symbolic.*")
	public void rejectsSymbolicParentDuringCapture() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("candidate-snapshot-parent-"));
			Path outside = cleanup.add(Files.createTempDirectory("candidate-snapshot-outside-"));
			write(outside, "rule.md", "outside");
			Files.createSymbolicLink(project.resolve("prompts"), outside);
			PromptSnapshot.capture(project, List.of(Path.of("prompts/rule.md")));
		}
	}

	/**
	 * A symbolic prompt file is rejected rather than read through its target.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*Symbolic.*")
	public void rejectsSymbolicPromptFile() throws IOException
	{
		Path project = Files.createTempDirectory("candidate-symbolic-file");
		try
		{
			Path outside = project.resolve("outside.md");
			Files.writeString(outside, "outside", UTF_8);
			Path prompt = project.resolve("prompts/rule.md");
			Files.createDirectories(prompt.getParent());
			Files.createSymbolicLink(prompt, outside);
			PromptSnapshot.capture(project, List.of(Path.of("prompts")));
		}
		finally
		{
			TemporaryDirectories.deleteRecursively(project);
		}
	}

	/**
	 * A symbolic parent in the target worktree is rejected before delivery follows it.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*Symbolic links.*")
	public void rejectsSymbolicPromptParent() throws IOException
	{
		Path project = Files.createTempDirectory("candidate-symbolic-parent");
		Path baselineSource = Files.createTempDirectory("candidate-symbolic-baseline");
		Path source = Files.createTempDirectory("candidate-symbolic-source");
		try
		{
			write(project, "real/rule.md", "base");
			Files.createSymbolicLink(project.resolve("prompts"), project.resolve("real"));
			write(baselineSource, "prompts/rule.md", "base");
			PromptSnapshot baseline = PromptSnapshot.capture(baselineSource, List.of(Path.of("prompts")));
			write(source, "prompts/rule.md", "candidate");
			PromptSnapshot candidate = PromptSnapshot.capture(source, List.of(Path.of("prompts")));
			try (CandidateDeliveryFixture fixture = new CandidateDeliveryFixture(project,
				Map.of(baseline.id(), baseline, candidate.id(), candidate), List.of(Path.of("prompts"))))
			{
				fixture.delivery().reconcile(baseline.id(), null, candidate.id());
			}
		}
		finally
		{
			TemporaryDirectories.deleteRecursively(project);
			TemporaryDirectories.deleteRecursively(baselineSource);
			TemporaryDirectories.deleteRecursively(source);
		}
	}

	/**
	 * Snapshot registry keys must not alias a different immutable snapshot identity.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test(expectedExceptions = IllegalArgumentException.class, expectedExceptionsMessageRegExp = ".*key.*identity.*")
	public void rejectsSnapshotIdentityAlias() throws IOException
	{
		Path project = Files.createTempDirectory("candidate-identity");
		try
		{
			write(project, "prompts/rule.md", "base");
			PromptSnapshot snapshot = PromptSnapshot.capture(project, List.of(Path.of("prompts")));
			try (CandidateDeliveryFixture ignored = new CandidateDeliveryFixture(project,
				Map.of("not-the-snapshot-id", snapshot), List.of(Path.of("prompts"))))
			{
				ignored.delivery();
				throw new AssertionError("Expected a registry identity rejection");
			}
		}
		finally
		{
			TemporaryDirectories.deleteRecursively(project);
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
