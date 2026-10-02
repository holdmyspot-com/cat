package io.github.cowwoc.cat.common.optimizer.inventory;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.Optional;

/**
 * Extracts {@link Hunk} changes and reconstructs files with a selected subset of those same changes.
 */
public final class Hunks
{
	/**
	 * Prevents instantiation.
	 */
	private Hunks()
	{
	}

	/**
	 * Compares a file's exact bytes using the canonical {@link Hunk#DEFINITION}.
	 *
	 * @param file the changed file
	 * @return the file's hunks in order
	 */
	public static List<Hunk> between(FileDelta file)
	{
		List<String> before = lines(file.before()).orElse(null);
		List<String> after = lines(file.after()).orElse(null);
		if (before == null || after == null)
			return List.of(new Hunk(file.path(), 1, 0, 0, 0, 0));
		int[][] common = new int[before.size() + 1][after.size() + 1];
		for (int i = before.size() - 1; i >= 0; --i)
		{
			for (int j = after.size() - 1; j >= 0; --j)
			{
				if (before.get(i).equals(after.get(j)))
					common[i][j] = common[i + 1][j + 1] + 1;
				else
					common[i][j] = Math.max(common[i + 1][j], common[i][j + 1]);
			}
		}
		List<Hunk> result = new ArrayList<>();
		int i = 0;
		int j = 0;
		while (i < before.size() || j < after.size())
		{
			if (i < before.size() && j < after.size() && before.get(i).equals(after.get(j)))
			{
				++i;
				++j;
				continue;
			}
			int startBefore = i;
			int startAfter = j;
			while (i < before.size() || j < after.size())
			{
				if (i < before.size() && j < after.size() && before.get(i).equals(after.get(j)))
					break;
				if (j == after.size() || i < before.size() && common[i + 1][j] >= common[i][j + 1])
					++i;
				else
					++j;
			}
			result.add(new Hunk(file.path(), result.size() + 1, startBefore, i, startAfter, j));
		}
		return List.copyOf(result);
	}

	/**
	 * Returns exact text lines when the file is present and valid UTF-8 without NUL bytes.
	 *
	 * @param content the bytes, or {@code null} for an absent file
	 * @return lines including their terminators, or empty for an absent or binary file
	 */
	private static Optional<List<String>> lines(FileContent content)
	{
		if (content == null)
			return Optional.empty();
		try
		{
			String text = UTF_8.newDecoder().decode(ByteBuffer.wrap(content.bytes())).toString();
			if (text.indexOf('\0') >= 0)
				return Optional.empty();
			if (text.isEmpty())
				return Optional.of(List.of());
			return Optional.of(List.of(text.split("(?<=\\n)")));
		}
		catch (CharacterCodingException _)
		{
			return Optional.empty();
		}
	}

	/**
	 * Applies only the selected original hunks to the working overlay.
	 *
	 * @param working the working candidate
	 * @param files the original authorized file changes
	 * @param retained the identifiers of the hunks to keep
	 * @return the reduced candidate
	 */
	public static Overlay apply(Overlay working, List<FileDelta> files, Set<String> retained)
	{
		Overlay result = working;
		for (FileDelta file : files)
		{
			List<String> before = lines(file.before()).orElse(null);
			List<String> after = lines(file.after()).orElse(null);
			FileContent content;
			if (before == null || after == null)
			{
				if (retained.contains(file.path() + "#1"))
					content = file.after();
				else
					content = file.before();
			}
			else
			{
				StringBuilder text = new StringBuilder();
				int position = 0;
				for (Hunk hunk : between(file))
				{
					text.append(String.join("", before.subList(position, hunk.beforeStart())));
					if (retained.contains(hunk.id()))
						text.append(String.join("", after.subList(hunk.afterStart(), hunk.afterEnd())));
					else
						text.append(String.join("", before.subList(hunk.beforeStart(), hunk.beforeEnd())));
					position = hunk.beforeEnd();
				}
				text.append(String.join("", before.subList(position, before.size())));
				content = new FileContent(text.toString().getBytes(UTF_8));
			}
			if (content == null)
				result = result.delete(file.path());
			else
				result = result.write(file.path(), content, file.permissions());
		}
		return result;
	}
}
