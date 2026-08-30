package io.github.cowwoc.cat.common;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Lists source-code comment and Javadoc locations without interpreting their prose.
 */
public final class SourceDocumentationInventory
{
	private static final Set<String> C_STYLE_EXTENSIONS = Set.of(".java", ".js", ".mjs");
	private static final Set<String> SHELL_EXTENSIONS = Set.of(".sh", ".bats");
	private static final Set<String> GENERATED_DIRECTORY_NAMES = Set.of("target", "node_modules", ".cat");

	/**
	 * Writes one deterministic Markdown inventory for the selected source roots.
	 *
	 * @param args an output path followed by one or more source roots
	 * @throws IOException if a source root or output cannot be accessed
	 */
	public static void main(String[] args) throws IOException
	{
		requireThat(args, "args").isNotNull();
		if (args.length < 2)
		{
			throw new IllegalArgumentException("Usage: source-documentation-inventory <output.md> " +
				"<source-root>...");
		}
		Path output = Path.of(args[0]).toAbsolutePath().normalize();
		requireThat(output.getParent(), "output.parent").isDirectory();
		List<Entry> entries = new ArrayList<>();
		for (int index = 1; index < args.length; ++index)
			entries.addAll(scanRoot(Path.of(args[index]).toAbsolutePath().normalize()));
		write(output, entries);
	}

	/**
	 * Scans one source root for supported source-code documentation.
	 *
	 * @param root the selected source root
	 * @return all source-code documentation locations below the root
	 * @throws IOException if a source file cannot be read
	 */
	private static List<Entry> scanRoot(Path root) throws IOException
	{
		requireThat(root, "sourceRoot").isDirectory();
		List<Entry> entries = new ArrayList<>();
		try (Stream<Path> paths = Files.walk(root))
		{
			for (Path file : paths.filter(Files::isRegularFile).
				filter(path -> !isGeneratedOutput(root, path)).sorted().toList())
			{
				SourceStyle style = sourceStyle(file);
				if (style != null)
					entries.addAll(scan(file, style));
			}
		}
		return entries;
	}

	/**
	 * Determines whether a source path belongs to generated or workflow-owned output below a selected source root.
	 *
	 * @param root the selected source root
	 * @param path a path below the root
	 * @return whether the path is outside version-controlled source documentation
	 */
	private static boolean isGeneratedOutput(Path root, Path path)
	{
		if (GENERATED_DIRECTORY_NAMES.contains(root.getFileName().toString()))
			return true;
		Path relativePath = root.relativize(path);
		for (Path segment : relativePath)
			if (GENERATED_DIRECTORY_NAMES.contains(segment.toString()))
				return true;
		return false;
	}

	/**
	 * Selects the comment grammar supported for one file.
	 *
	 * @param file a source file
	 * @return its supported grammar, or {@code null} when the file has no supported source-comment grammar
	 */
	private static SourceStyle sourceStyle(Path file)
	{
		String name = file.getFileName().toString();
		for (String extension : C_STYLE_EXTENSIONS)
			if (name.endsWith(extension))
				return SourceStyle.C_STYLE;
		for (String extension : SHELL_EXTENSIONS)
			if (name.endsWith(extension))
				return SourceStyle.SHELL;
		return null;
	}

	/**
	 * Finds documentation starts in one source file while ignoring source literals.
	 *
	 * @param file a supported source file
	 * @param style its comment grammar
	 * @return the documentation starts in source order
	 * @throws IOException if the source file cannot be read
	 */
	private static List<Entry> scan(Path file, SourceStyle style) throws IOException
	{
		String source = Files.readString(file);
		return switch (style)
		{
			case C_STYLE -> scanCStyle(file, source);
			case SHELL -> scanShell(file, source);
		};
	}

	/**
	 * Scans C-style source while ignoring quoted and text-block literals.
	 *
	 * @param file the source file
	 * @param source its contents
	 * @return the documentation starts
	 */
	private static List<Entry> scanCStyle(Path file, String source)
	{
		List<Entry> entries = new ArrayList<>();
		boolean blockComment = false;
		boolean quoted = false;
		boolean character = false;
		boolean textBlock = false;
		boolean escaped = false;
		int line = 1;
		for (int index = 0; index < source.length(); ++index)
		{
			char current = source.charAt(index);
			char next = '\0';
			if (index + 1 < source.length())
				next = source.charAt(index + 1);
			if (current == '\n')
			{
				++line;
				if (quoted || character)
				{
					quoted = false;
					character = false;
					escaped = false;
				}
				continue;
			}
			if (blockComment)
			{
				if (current == '*' && next == '/')
				{
					blockComment = false;
					++index;
				}
				continue;
			}
			if (textBlock)
			{
				if (current == '"' && next == '"' && index + 2 < source.length() && source.charAt(index + 2) == '"')
				{
					textBlock = false;
					index += 2;
				}
				continue;
			}
			if (quoted || character)
			{
				if (escaped)
				{
					escaped = false;
					continue;
				}
				if (current == '\\')
				{
					escaped = true;
					continue;
				}
				if ((quoted && current == '"') || (character && current == '\''))
				{
					quoted = false;
					character = false;
				}
				continue;
			}
			if (current == '"' && next == '"' && index + 2 < source.length() && source.charAt(index + 2) == '"')
			{
				textBlock = true;
				index += 2;
				continue;
			}
			if (current == '"')
			{
				quoted = true;
				continue;
			}
			if (current == '\'')
			{
				character = true;
				continue;
			}
			if (current == '/' && next == '/')
			{
				entries.add(new Entry(file, line, "line comment"));
				while (index < source.length() && source.charAt(index) != '\n')
					++index;
				if (index < source.length())
					++line;
				continue;
			}
			if (current == '/' && next == '*')
			{
				boolean javadoc = index + 2 < source.length() && source.charAt(index + 2) == '*';
				String kind = "block comment";
				if (javadoc)
					kind = "Javadoc";
				entries.add(new Entry(file, line, kind));
				blockComment = true;
				++index;
			}
		}
		return entries;
	}

	/**
	 * Scans shell-like source for comments outside single- and double-quoted literals.
	 *
	 * @param file the source file
	 * @param source its contents
	 * @return the documentation starts
	 */
	private static List<Entry> scanShell(Path file, String source)
	{
		List<Entry> entries = new ArrayList<>();
		String[] lines = source.split("\\R", -1);
		for (int index = 0; index < lines.length; ++index)
		{
			String line = lines[index];
			if (index == 0 && line.startsWith("#!"))
				continue;
			boolean singleQuoted = false;
			boolean doubleQuoted = false;
			boolean escaped = false;
			for (int character = 0; character < line.length(); ++character)
			{
				char current = line.charAt(character);
				if (escaped)
				{
					escaped = false;
					continue;
				}
				if (doubleQuoted && current == '\\')
				{
					escaped = true;
					continue;
				}
				if (!doubleQuoted && current == '\'')
				{
					singleQuoted = !singleQuoted;
					continue;
				}
				if (!singleQuoted && current == '"')
				{
					doubleQuoted = !doubleQuoted;
					continue;
				}
				if (!singleQuoted && !doubleQuoted && current == '#')
				{
					entries.add(new Entry(file, index + 1, "line comment"));
					break;
				}
			}
		}
		return entries;
	}

	/**
	 * Writes the selected source-comment locations in stable order.
	 *
	 * @param output the inventory destination
	 * @param entries the documentation locations
	 * @throws IOException if the output cannot be written
	 */
	private static void write(Path output, List<Entry> entries) throws IOException
	{
		requireThat(output, "output").isNotNull();
		requireThat(entries, "entries").isNotNull();
		entries.sort(Comparator.naturalOrder());
		StringBuilder content = new StringBuilder(256);
		content.append("# Source Documentation Inventory\n\nEach entry identifies a source comment location; it does not " +
			"evaluate the comment's prose.\n\n");
		for (Entry entry : entries)
		{
			content.append("- ").append(entry.path()).append(':').append(entry.line()).append(": ").
				append(entry.kind()).append('\n');
		}
		Files.writeString(output, content.toString());
	}

	/**
	 * Identifies one source-code documentation start.
	 *
	 * @param path the source file
	 * @param line the one-based source line
	 * @param kind the comment form
	 */
	private record Entry(Path path, int line, String kind) implements Comparable<Entry>
	{
		/**
		 * Validates one documentation entry.
		 *
		 * @param path the source file
		 * @param line the one-based source line
		 * @param kind the comment form
		 */
		private Entry
		{
			requireThat(path, "path").isNotNull();
			requireThat(line, "line").isPositive();
			requireThat(kind, "kind").isNotBlank();
		}

		@Override
		public int compareTo(Entry other)
		{
			requireThat(other, "other").isNotNull();
			int pathComparison = path.compareTo(other.path);
			if (pathComparison != 0)
				return pathComparison;
			return Integer.compare(line, other.line);
		}
	}

	/**
	 * Names the supported source comment grammars.
	 */
	private enum SourceStyle
	{
		C_STYLE,
		SHELL
	}

	/**
	 * Prevents utility-class construction.
	 */
	private SourceDocumentationInventory()
	{
	}
}
