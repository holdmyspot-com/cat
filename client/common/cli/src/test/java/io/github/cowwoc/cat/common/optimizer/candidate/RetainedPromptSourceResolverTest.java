package io.github.cowwoc.cat.common.optimizer.candidate;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.optimizer.contract.PromptSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import org.testng.annotations.Test;

/** Verifies retained prompt paths across directory links without consulting a later worktree. */
public final class RetainedPromptSourceResolverTest
{
	/** A chain of directory links retains each link's exact text and the original file bytes. */
	@Test
	public void resolvesDirectoryLinkChain() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("retained-rule-links-")).toRealPath();
			Path actual = Files.createDirectory(project.resolve("actual-rules"));
			Files.writeString(actual.resolve("rule.md"), "retained rule\n");
			PromptSnapshot snapshot = PromptSnapshot.capture(project, List.of(Path.of("actual-rules", "rule.md")));
			String hash = snapshot.entries().get(Path.of("actual-rules", "rule.md")).hash();
			List<LiveGitInventory.Entry> entries = List.of(
				new LiveGitInventory.Entry(Path.of("rules"), LiveGitInventory.Entry.Kind.SYMLINK,
					PromptSnapshot.sha256("alias".getBytes(StandardCharsets.UTF_8)), "", "alias",
					actual.toString()),
				new LiveGitInventory.Entry(Path.of("alias"), LiveGitInventory.Entry.Kind.SYMLINK,
					PromptSnapshot.sha256("actual-rules".getBytes(StandardCharsets.UTF_8)), "", "actual-rules",
					actual.toString()),
				new LiveGitInventory.Entry(Path.of("actual-rules"), LiveGitInventory.Entry.Kind.DIRECTORY,
					"", "rwxr-xr-x", "", ""),
				new LiveGitInventory.Entry(Path.of("actual-rules", "rule.md"),
					LiveGitInventory.Entry.Kind.REGULAR, hash, "rw-r--r--", "", "")).stream().sorted(
					Comparator.comparing(entry -> entry.path().toString())).toList();
			LiveGitInventory inventory = new LiveGitInventory(LiveGitInventory.SCHEMA_VERSION, project,
				"a".repeat(40), "b".repeat(64), entries);

			PromptSource source = new RetainedPromptSourceResolver(inventory, snapshot).resolve(
				Path.of("rules", "rule.md"));

			requireThat(source, "retainedSource").isEqualTo(new PromptSource(Path.of("rules", "rule.md"),
				Path.of("actual-rules", "rule.md"), "", List.of(
					new PromptSource.DirectoryLink(Path.of("rules"), "alias", Path.of("actual-rules")),
					new PromptSource.DirectoryLink(Path.of("alias"), "actual-rules", Path.of("actual-rules"))), hash));
		}
	}

	/** A directory link to the project root can reach a retained file there. */
	@Test
	public void resolvesDirectoryLinkToProjectRoot() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("retained-root-link-")).toRealPath();
			Files.writeString(project.resolve("rule.md"), "retained rule\n");
			PromptSnapshot snapshot = PromptSnapshot.capture(project, List.of(Path.of("rule.md")));
			String hash = snapshot.entries().get(Path.of("rule.md")).hash();
			LiveGitInventory.Entry link = new LiveGitInventory.Entry(Path.of("rules"),
				LiveGitInventory.Entry.Kind.SYMLINK,
				PromptSnapshot.sha256(".".getBytes(StandardCharsets.UTF_8)), "", ".", project.toString());
			LiveGitInventory.Entry file = new LiveGitInventory.Entry(Path.of("rule.md"),
				LiveGitInventory.Entry.Kind.REGULAR, hash, "rw-r--r--", "", "");
			LiveGitInventory inventory = new LiveGitInventory(LiveGitInventory.SCHEMA_VERSION, project,
				"a".repeat(40), "b".repeat(64), List.of(file, link));

			PromptSource source = new RetainedPromptSourceResolver(inventory, snapshot).resolve(
				Path.of("rules", "rule.md"));

			requireThat(source, "rootLinkedSource").isEqualTo(new PromptSource(Path.of("rules", "rule.md"),
				Path.of("rule.md"), "", List.of(new PromptSource.DirectoryLink(Path.of("rules"), ".",
					Path.of(""))), hash));
		}
	}

	/** A project link whose target lies outside the project needs the separate external-source path. */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*outside the project.*")
	public void rejectsExternalDirectoryLink() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("retained-external-link-")).toRealPath();
			PromptSnapshot snapshot = PromptSnapshot.capture(project, List.of());
			LiveGitInventory.Entry link = new LiveGitInventory.Entry(Path.of("rules"),
				LiveGitInventory.Entry.Kind.SYMLINK,
				PromptSnapshot.sha256("../outside".getBytes(StandardCharsets.UTF_8)), "", "../outside",
				project.getParent().resolve("outside").toString());
			LiveGitInventory inventory = new LiveGitInventory(LiveGitInventory.SCHEMA_VERSION, project,
				"a".repeat(40), "b".repeat(64), List.of(link));
			new RetainedPromptSourceResolver(inventory, snapshot).resolve(Path.of("rules", "rule.md"));
		}
	}
}
