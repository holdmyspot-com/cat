package com.holdmyspot.cat.common;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Removes the content of packaged Markdown files that is written for maintainers rather than for models: the
 * {@code ## Design Goals} section and HTML comments.
 * <p>
 * Agents read many packaged files directly, such as lazily loaded rules and a skill's {@code first-use.md}, so the
 * plugin must not ship this content; removing it only where CAT injects rules would leave every other path exposed.
 * Comments that start with {@code cat:} are directives that later build or runtime steps replace, so they stay. Text
 * inside fenced code blocks and code spans is an example for the reader and stays as written.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 */
public final class SourceOnlyContent
{
	private static final int MAXIMUM_PROMPT_FILE_BYTES = 1_048_576;
	private static final String DESIGN_GOALS = "## Design Goals";
	private static final String COMMENT_START = "<!--";
	private static final String COMMENT_END = "-->";
	private static final String DIRECTIVE_START = "<!-- cat:";
	/**
	 * Matches a line that opens or closes a fenced code block, capturing its fence.
	 */
	private static final Pattern FENCE = Pattern.compile("^ {0,3}(`{3,}|~{3,}).*$");
	/**
	 * Matches a heading of level one or two, which ends the design goals section.
	 */
	private static final Pattern SECTION_HEADING = Pattern.compile("^#{1,2} .*$");

	/**
	 * Removes source-only content from every Markdown file in a directory tree.
	 *
	 * @param directory the directory that holds the packaged Markdown files
	 * @throws NullPointerException if {@code directory} is null
	 * @throws IOException if a file cannot be read or written, or contains a comment that never ends
	 */
	public static void removeFromTree(Path directory) throws IOException
	{
		requireThat(directory, "directory").isNotNull();
		if (!Files.isDirectory(directory, NOFOLLOW_LINKS))
			return;
		List<Path> files;
		try (Stream<Path> paths = Files.walk(directory))
		{
			files = paths.filter(path -> Files.isRegularFile(path, NOFOLLOW_LINKS)).
				filter(path -> path.getFileName().toString().endsWith(".md")).sorted().toList();
		}
		for (Path file : files)
		{
			String content = BoundedInput.readUtf8(file, MAXIMUM_PROMPT_FILE_BYTES, "Packaged Markdown file");
			String stripped = remove(content, file.toString());
			if (!stripped.equals(content))
				Files.writeString(file, stripped);
		}
	}

	/**
	 * Removes source-only content from one Markdown file's content.
	 *
	 * @param content the file's content
	 * @param source the name of the file, used in error messages
	 * @return the content without its design goals section and its comments other than directives
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException if {@code content} contains a comment that never ends
	 */
	public static String remove(String content, String source) throws IOException
	{
		requireThat(content, "content").isNotNull();
		requireThat(source, "source").isNotNull();
		String stripped = removeDesignGoals(removeComments(content, source));
		if (stripped.equals(content))
			return content;
		// A removed section or comment at the end of the file leaves the blank lines that separated it
		return stripped.stripTrailing() + "\n";
	}

	/**
	 * Removes HTML comments that are not directives, outside fenced code blocks and code spans. A comment that fills
	 * its lines is removed with those lines.
	 *
	 * @param content the Markdown content
	 * @param source the name of the file, used in error messages
	 * @return the content without the comments
	 * @throws IOException if a comment never ends
	 */
	private static String removeComments(String content, String source) throws IOException
	{
		StringBuilder result = new StringBuilder(content.length());
		String fence = "";
		int index = 0;
		while (index < content.length())
		{
			int lineEnd = lineEnd(content, index);
			String line = content.substring(index, lineEnd).stripTrailing();
			boolean fenced = !fence.isEmpty();
			fence = fenceAfter(fence, line);
			if (fenced || !fence.isEmpty())
			{
				result.append(content, index, lineEnd);
				index = lineEnd;
				continue;
			}
			index = removeLineComments(content, index, lineEnd, result, source);
		}
		return result.toString();
	}

	/**
	 * Copies one line outside fenced code blocks, without its comments. A comment may continue onto later lines.
	 *
	 * @param content the Markdown content
	 * @param start the index of the line's first character
	 * @param lineEnd the index after the line's line break
	 * @param result the text that receives the copy
	 * @param source the name of the file, used in error messages
	 * @return the index after the last character that this method consumed
	 * @throws IOException if a comment never ends
	 */
	private static int removeLineComments(String content, int start, int lineEnd, StringBuilder result, String source)
		throws IOException
	{
		int index = start;
		int end = lineEnd;
		while (index < end)
		{
			char character = content.charAt(index);
			if (character == '`')
			{
				int runEnd = index;
				while (runEnd < end && content.charAt(runEnd) == '`')
					++runEnd;
				String run = content.substring(index, runEnd);
				int close = content.indexOf(run, runEnd);
				if (close < 0 || close >= end)
				{
					result.append(run);
					index = runEnd;
					continue;
				}
				result.append(content, index, close + run.length());
				index = close + run.length();
				continue;
			}
			if (!content.startsWith(COMMENT_START, index) || content.startsWith(DIRECTIVE_START, index))
			{
				result.append(character);
				++index;
				continue;
			}
			int close = content.indexOf(COMMENT_END, index + COMMENT_START.length());
			if (close < 0)
				throw new IOException("A comment in " + source + " never ends");
			int afterComment = close + COMMENT_END.length();
			int afterLine = lineEnd(content, afterComment);
			boolean startsLine = content.substring(lineStart(content, index), index).isBlank();
			boolean endsLine = content.substring(afterComment, afterLine).isBlank();
			if (startsLine && endsLine)
			{
				// Drop the whole lines, including the indentation already copied, so no blank line takes their place
				result.setLength(result.length() - (index - lineStart(content, index)));
				return afterLine;
			}
			index = afterComment;
			end = afterLine;
		}
		return end;
	}

	/**
	 * Removes the design goals section, from its heading to the next heading of level one or two, outside fenced
	 * code blocks.
	 *
	 * @param content the Markdown content
	 * @return the content without the section
	 */
	private static String removeDesignGoals(String content)
	{
		StringBuilder result = new StringBuilder(content.length());
		String fence = "";
		boolean inSection = false;
		int index = 0;
		while (index < content.length())
		{
			int lineEnd = lineEnd(content, index);
			String line = content.substring(index, lineEnd).stripTrailing();
			boolean fenced = !fence.isEmpty();
			fence = fenceAfter(fence, line);
			if (!fenced && fence.isEmpty())
			{
				if (line.equals(DESIGN_GOALS))
					inSection = true;
				else if (inSection && SECTION_HEADING.matcher(line).matches())
					inSection = false;
			}
			if (!inSection)
				result.append(content, index, lineEnd);
			index = lineEnd;
		}
		return result.toString();
	}

	/**
	 * Returns the fenced code block that is open after a line.
	 *
	 * @param fence the fence of the block that is open before the line, or an empty string if none is open
	 * @param line the line, without its line break
	 * @return the fence of the block that is open after the line, or an empty string if none is open
	 */
	private static String fenceAfter(String fence, String line)
	{
		Matcher matcher = FENCE.matcher(line);
		if (!matcher.matches())
			return fence;
		String marker = matcher.group(1);
		if (fence.isEmpty())
			return marker;
		if (marker.charAt(0) == fence.charAt(0) && marker.length() >= fence.length() && line.strip().equals(marker))
			return "";
		return fence;
	}

	/**
	 * Returns the index after a line's line break.
	 *
	 * @param content the text
	 * @param index an index inside the line
	 * @return the index after the line break, or the text's length if the line has none
	 */
	private static int lineEnd(String content, int index)
	{
		int newline = content.indexOf('\n', index);
		if (newline < 0)
			return content.length();
		return newline + 1;
	}

	/**
	 * Returns the index of a line's first character.
	 *
	 * @param content the text
	 * @param index an index inside the line
	 * @return the index after the previous line break, or 0
	 */
	private static int lineStart(String content, int index)
	{
		return content.lastIndexOf('\n', index - 1) + 1;
	}

	/**
	 * Prevents instantiation.
	 */
	private SourceOnlyContent()
	{
	}
}
