package io.github.cowwoc.cat.common.optimizer.candidate;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.testng.annotations.Test;

/** Verifies the exact FILE and DIRECTORY candidate write boundary. */
public final class DeliveryWriteAuthorityTest
{
	/** An unchanged read-only source remains in a candidate snapshot without becoming editable. */
	@Test
	public void permitsUnchangedReadOnlySource() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("candidate-authority-"));
			Files.createDirectories(project.resolve("prompts"));
			Files.writeString(project.resolve("prompts/edit.md"), "before");
			Files.writeString(project.resolve("prompts/read-only.md"), "source");
			PromptSnapshot before = PromptSnapshot.capture(project, List.of(Path.of("prompts")));
			Files.writeString(project.resolve("prompts/edit.md"), "after");
			PromptSnapshot after = PromptSnapshot.capture(project, List.of(Path.of("prompts")));
			DeliveryWriteAuthority authority = new DeliveryWriteAuthority(List.of(
				new EditableRoot(Path.of("prompts/edit.md"), EditableRoot.Kind.FILE)));

			CandidateDiff diff = CandidateDiff.validate(before, after, authority);

			requireThat(diff.changes().size(), "changedPathCount").isEqualTo(1);
			requireThat(diff.changes().getFirst().path(), "changedPath").isEqualTo(Path.of("prompts/edit.md"));
		}
	}

	/** A FILE root never grants its sibling or child pathname. */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*outside confirmed editable roots.*")
	public void rejectsFileRootSibling() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("candidate-authority-"));
			Files.createDirectories(project.resolve("prompts"));
			Files.writeString(project.resolve("prompts/sibling.md"), "before");
			PromptSnapshot before = PromptSnapshot.capture(project, List.of(Path.of("prompts")));
			Files.writeString(project.resolve("prompts/sibling.md"), "after");
			PromptSnapshot after = PromptSnapshot.capture(project, List.of(Path.of("prompts")));
			DeliveryWriteAuthority authority = new DeliveryWriteAuthority(List.of(
				new EditableRoot(Path.of("prompts/edit.md"), EditableRoot.Kind.FILE)));
			CandidateDiff.validate(before, after, authority);
		}
	}

	/** A DIRECTORY root grants descendants, not the root pathname itself. */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*outside confirmed editable roots.*")
	public void rejectsDirectoryRootPathname() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("candidate-authority-"));
			Files.writeString(project.resolve("prompt"), "before");
			PromptSnapshot before = PromptSnapshot.capture(project, List.of(Path.of("prompt")));
			Files.writeString(project.resolve("prompt"), "after");
			PromptSnapshot after = PromptSnapshot.capture(project, List.of(Path.of("prompt")));
			DeliveryWriteAuthority authority = new DeliveryWriteAuthority(List.of(
				new EditableRoot(Path.of("prompt"), EditableRoot.Kind.DIRECTORY)));
			CandidateDiff.validate(before, after, authority);
		}
	}
}
