package io.github.cowwoc.cat.common;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Verifies that an explicitly selected directory contains only substantive prompt files with Design Goals.
 */
public final class PromptFileCheck
{
	private static final String DESIGN_GOALS = "## Design Goals";
	private static final String INCLUDE_PREFIX = "<!-- cat:include ";
	private static final String README = "README.md";
	private static final Set<String> GENERATED_ROUTING_INDEXES = Set.of(".cat-lazy-rule-dependencies.md",
		".cat-path-scoped-rules.md");
	private static final String FIRST_USE_ROUTE = """
		If you have not already read this skill's `first-use.md` in the current
		conversation context, read it now and follow it exactly. Otherwise, reuse its guidance.
		Read `first-use.md` again after the context resets or is compacted.
		""";

	/**
	 * Verifies the substantive prompt files below one directory.
	 *
	 * @param args one or more directories or Markdown files containing substantive prompt guidance
	 * @throws IOException if a prompt file cannot be read or lacks Design Goals
	 */
	public static void main(String[] args) throws IOException
	{
		requireThat(args, "args").isNotNull();
		if (args.length == 0)
			throw new IllegalArgumentException("Usage: prompt-file-check [--inventory <output>] " +
				"<substantive-prompt-path>...");
		int firstDirectory = 0;
		Path inventory = null;
		if (args[0].equals("--inventory"))
		{
			if (args.length < 3)
				throw new IllegalArgumentException("Usage: prompt-file-check --inventory <output> " +
					"<substantive-prompt-path>...");
			inventory = Path.of(args[1]).toAbsolutePath().normalize();
			firstDirectory = 2;
		}
		List<InventoryEntry> entries = new ArrayList<>();
		for (int index = firstDirectory; index < args.length; ++index)
			entries.addAll(checkPath(Path.of(args[index]).toAbsolutePath().normalize()));
		if (inventory != null)
			writeInventory(inventory, entries);
	}

	/**
	 * Checks one explicitly selected Markdown file or every Markdown file beneath one selected directory.
	 *
	 * @param path a directory or Markdown file containing substantive prompt guidance
	 * @return the included files and explicit exclusions
	 * @throws IOException if a prompt file cannot be read or lacks Design Goals
	 */
	private static List<InventoryEntry> checkPath(Path path) throws IOException
	{
		requireThat(path, "substantivePromptPath").isNotNull();
		if (Files.isDirectory(path))
			return checkDirectory(path);
		if (!Files.isRegularFile(path))
			throw new IllegalArgumentException("Substantive prompt path must reference an existing directory or Markdown " +
				"file: " + path);
		if (!path.getFileName().toString().endsWith(".md"))
			throw new IllegalArgumentException("Substantive prompt file must be Markdown: " + path);
		return List.of(checkPromptFile(path));
	}

	/**
	 * Checks every substantive Markdown prompt below one explicitly selected directory.
	 *
	 * @param directory a directory containing substantive Markdown prompt files
	 * @return the included files and explicit exclusions
	 * @throws IOException if a prompt file cannot be read or lacks Design Goals
	 */
	private static List<InventoryEntry> checkDirectory(Path directory) throws IOException
	{
		requireThat(directory, "substantivePromptDirectory").isDirectory();
		List<InventoryEntry> entries = new ArrayList<>();
		try (Stream<Path> paths = Files.walk(directory))
		{
			List<Path> promptFiles = paths.filter(Files::isRegularFile).
				filter(path -> path.getFileName().toString().endsWith(".md")).sorted().toList();
			if (promptFiles.isEmpty())
				throw new IllegalArgumentException("No Markdown prompt files found in " + directory);
			for (Path promptFile : promptFiles)
				entries.add(checkPromptFile(promptFile));
		}
		return entries;
	}

	/**
	 * Checks one selected Markdown prompt and classifies an explicit non-prompt file when applicable.
	 *
	 * @param promptFile the selected Markdown file
	 * @return an included file or its exclusion reason
	 * @throws IOException if the file cannot be read or lacks Design Goals
	 */
	private static InventoryEntry checkPromptFile(Path promptFile) throws IOException
	{
		if (isGeneratedRoutingIndex(promptFile))
			return new InventoryEntry(promptFile, "generated routing index");
		if (isDocumentationReadme(promptFile))
			return new InventoryEntry(promptFile, "directory documentation");
		String content = Files.readString(promptFile);
		if (isMetadataWrapper(promptFile, content))
			return new InventoryEntry(promptFile, "metadata-only skill wrapper");
		if (!containsDesignGoals(promptFile, content))
			throw new IOException("Substantive prompt file lacks Design Goals: " + promptFile);
		return new InventoryEntry(promptFile, null);
	}

	/**
	 * Writes the selected prompt files and their explicit exclusions for a later audit.
	 *
	 * @param inventory the retained inventory file
	 * @param entries the checked entries
	 * @throws IOException if the inventory cannot be written
	 */
	private static void writeInventory(Path inventory, List<InventoryEntry> entries) throws IOException
	{
		requireThat(inventory, "inventory").isNotNull();
		requireThat(entries, "entries").isNotNull();
		requireThat(inventory.getParent(), "inventory.parent").isDirectory();
		StringBuilder content = new StringBuilder(128).append("# Prompt File Inventory\n\n## Included\n\n");
		entries.stream().filter(entry -> entry.exclusion() == null).map(InventoryEntry::path).sorted().
			forEach(path -> content.append("- ").append(path).append('\n'));
		content.append("\n## Excluded\n\n");
		entries.stream().filter(entry -> entry.exclusion() != null).sorted().forEach(entry -> content.append("- ").
			append(entry.path()).append(": ").append(entry.exclusion()).append('\n'));
		Files.writeString(inventory, content.toString());
	}

	/**
	 * Records one selected Markdown file and, when applicable, why it is not executable prompt guidance.
	 *
	 * @param path the selected file
	 * @param exclusion the exclusion reason, or {@code null} when the file is substantive guidance
	 */
	private record InventoryEntry(Path path, String exclusion) implements Comparable<InventoryEntry>
	{
		/**
		 * Validates one inventory entry.
		 *
		 * @param path the selected file
		 * @param exclusion the exclusion reason, or {@code null} when the file is substantive guidance
		 */
		private InventoryEntry
		{
			requireThat(path, "path").isNotNull();
			if (exclusion != null)
				requireThat(exclusion, "exclusion").isNotBlank();
		}

		@Override
		public int compareTo(InventoryEntry other)
		{
			requireThat(other, "other").isNotNull();
			return path.compareTo(other.path);
		}
	}

	/**
	 * Determines whether a Markdown file is a generated routing index rather than reader-facing guidance.
	 *
	 * @param promptFile the selected Markdown file
	 * @return {@code true} if the file is generated by the rule artifact router
	 */
	private static boolean isGeneratedRoutingIndex(Path promptFile)
	{
		return GENERATED_ROUTING_INDEXES.contains(promptFile.getFileName().toString());
	}

	/**
	 * Determines whether a Markdown file documents a prompt directory instead of directing an agent.
	 *
	 * @param promptFile the selected Markdown file
	 * @return whether the file is the directory's README
	 */
	private static boolean isDocumentationReadme(Path promptFile)
	{
		return promptFile.getFileName().toString().equals(README);
	}

	/**
	 * Determines whether a delivered skill file supplies only metadata and the standard first-use route.
	 *
	 * @param promptFile the delivered Markdown file
	 * @param content its content
	 * @return {@code true} if the file is a metadata-only skill wrapper
	 */
	private static boolean isMetadataWrapper(Path promptFile, String content)
	{
		return promptFile.getFileName().toString().equals("SKILL.md") &&
			content.contains(FIRST_USE_ROUTE);
	}

	/**
	 * Determines whether a source prompt or its declared source-only body contains Design Goals.
	 *
	 * @param promptFile the source prompt file
	 * @param content the source prompt contents
	 * @return whether the assembled substantive guidance declares Design Goals
	 * @throws IOException if a declared body cannot be read
	 */
	private static boolean containsDesignGoals(Path promptFile, String content) throws IOException
	{
		if (content.contains(DESIGN_GOALS))
			return true;
		for (String line : content.lines().toList())
		{
			if (!line.startsWith(INCLUDE_PREFIX) || !line.endsWith(" -->"))
				continue;
			String target = line.substring(INCLUDE_PREFIX.length(), line.length() - " -->".length());
			Path includedBody = promptFile.getParent().resolve(target).normalize();
			if (!Files.isRegularFile(includedBody))
				throw new IOException("Prompt file references an absent included body: " + includedBody);
			if (Files.readString(includedBody).contains(DESIGN_GOALS))
				return true;
		}
		return false;
	}

	/**
	 * Prevents utility-class construction.
	 */
	private PromptFileCheck()
	{
	}
}
