package io.github.cowwoc.cat.common.optimizer.candidate;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.testng.annotations.Test;

/** Tests exact frozen editable and read-only source states before a project trial. */
public final class FrozenCandidateVerifierTest
{
	/** A candidate preserves known read-only bytes and an explicit absent source. */
	@Test
	public void verifiesCompleteFrozenState() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("frozen-project-"));
			Files.createDirectories(project.resolve("prompts/edit"));
			Files.writeString(project.resolve("prompts/edit/rule.md"), "before");
			Files.writeString(project.resolve("prompts/read-only.md"), "source");
			List<Path> known = List.of(Path.of("prompts/edit/rule.md"), Path.of("prompts/lazy.md"),
				Path.of("prompts/read-only.md"));
			PromptSnapshot before = PromptSnapshot.capture(project, known);
			ConfirmedSourceBaseline baseline = ConfirmedSourceBaseline.capture(project, known, before);
			Files.writeString(project.resolve("prompts/edit/rule.md"), "candidate");
			DeliveryWriteAuthority authority = new DeliveryWriteAuthority(List.of(
				new EditableRoot(Path.of("prompts/edit"), EditableRoot.Kind.DIRECTORY)));
			PromptSnapshot candidate = PromptSnapshot.capture(project, List.of(Path.of("prompts/edit"),
				Path.of("prompts/read-only.md")));
			RootInventory inventory = RootInventory.capture(authority, candidate);

			FrozenCandidateVerifier.verify(project, candidate, authority, inventory, baseline);
			requireThat(inventory.roots().getFirst().manifestEntries(), "frozenPaths").isEqualTo(
				List.of(Path.of("prompts/edit/rule.md")));
		}
	}

	/** An added file below a DIRECTORY root invalidates the staged copy. */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*frozen root.*")
	public void rejectsExtraDirectoryFile() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("frozen-project-"));
			Files.createDirectories(project.resolve("prompts/edit"));
			Files.writeString(project.resolve("prompts/edit/rule.md"), "candidate");
			List<Path> known = List.of(Path.of("prompts/edit/rule.md"));
			PromptSnapshot candidate = PromptSnapshot.capture(project, known);
			ConfirmedSourceBaseline baseline = ConfirmedSourceBaseline.capture(project, known, candidate);
			DeliveryWriteAuthority authority = new DeliveryWriteAuthority(List.of(
				new EditableRoot(Path.of("prompts/edit"), EditableRoot.Kind.DIRECTORY)));
			RootInventory inventory = RootInventory.capture(authority, candidate);
			Files.writeString(project.resolve("prompts/edit/extra.md"), "extra");
			FrozenCandidateVerifier.verify(project, candidate, authority, inventory, baseline);
		}
	}

	/** A changed read-only known source invalidates the candidate before a trial. */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*read-only.*")
	public void rejectsChangedReadOnlySource() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("frozen-project-"));
			Files.createDirectories(project.resolve("prompts"));
			Files.writeString(project.resolve("prompts/edit.md"), "edit");
			Files.writeString(project.resolve("prompts/read-only.md"), "source");
			List<Path> known = List.of(Path.of("prompts/edit.md"), Path.of("prompts/read-only.md"));
			PromptSnapshot candidate = PromptSnapshot.capture(project, known);
			ConfirmedSourceBaseline baseline = ConfirmedSourceBaseline.capture(project, known, candidate);
			DeliveryWriteAuthority authority = new DeliveryWriteAuthority(List.of(
				new EditableRoot(Path.of("prompts/edit.md"), EditableRoot.Kind.FILE)));
			RootInventory inventory = RootInventory.capture(authority, candidate);
			Files.writeString(project.resolve("prompts/read-only.md"), "changed");
			FrozenCandidateVerifier.verify(project, candidate, authority, inventory, baseline);
		}
	}

	/** A frozen manifest cannot smuggle an unconfirmed noneditable source into a candidate. */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*noneditable.*")
	public void rejectsUnconfirmedSourceEntry() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("frozen-extra-source-"));
			Files.createDirectories(project.resolve("prompts"));
			Files.writeString(project.resolve("prompts/edit.md"), "edit");
			Files.writeString(project.resolve("prompts/read-only.md"), "source");
			PromptSnapshot before = PromptSnapshot.capture(project, List.of(Path.of("prompts/edit.md")));
			ConfirmedSourceBaseline baseline = ConfirmedSourceBaseline.capture(project,
				List.of(Path.of("prompts/edit.md")), before);
			PromptSnapshot candidate = PromptSnapshot.capture(project, List.of(Path.of("prompts/edit.md"),
				Path.of("prompts/read-only.md")));
			DeliveryWriteAuthority authority = new DeliveryWriteAuthority(List.of(
				new EditableRoot(Path.of("prompts/edit.md"), EditableRoot.Kind.FILE)));
			RootInventory inventory = RootInventory.capture(authority, candidate);

			FrozenCandidateVerifier.verify(project, candidate, authority, inventory, baseline);
		}
	}
}
