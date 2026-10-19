package com.holdmyspot.cat.common.optimizer.execution;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.optimizer.campaign.PromptSize;
import com.holdmyspot.cat.common.optimizer.inventory.FileContent;
import com.holdmyspot.cat.common.optimizer.inventory.FileEntry.RegularFile;
import com.holdmyspot.cat.common.optimizer.inventory.FileInventory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Measures the participating files of a staged snapshot, including dereferenced prompt sources.
 */
final class PromptMeasurements
{
	/**
	 * Prevents instantiation.
	 */
	private PromptMeasurements()
	{
	}

	/**
	 * Measures each participating staged file once, checking its bytes against the snapshot.
	 *
	 * @param staged the staged snapshot
	 * @param inventory the snapshot inventory
	 * @param sources participating file and directory paths
	 * @return observed sizes, or the reason a measurement is unavailable
	 */
	static PromptSize measure(Path staged, FileInventory inventory, Set<Path> sources)
	{
		List<byte[]> contents = new ArrayList<>();
		long bytes = 0;
		try (Stream<Path> paths = Files.walk(staged))
		{
			for (Path file : paths.filter(Files::isRegularFile).sorted().toList())
			{
				Path relative = staged.relativize(file);
				Path resolved = inventory.resolve(relative).orElse(relative);
				if (!participates(relative, resolved, sources))
					continue;
				FileContent content = new FileContent(Files.readAllBytes(file));
				if (!(inventory.entries().get(resolved) instanceof RegularFile expected) ||
					!content.hash().equals(expected.contentHash()))
				{
					throw new IOException("Staged prompt bytes differ from the snapshot at " + relative);
				}
				byte[] observed = content.bytes();
				bytes += observed.length;
				contents.add(observed);
			}
		}
		catch (IOException e)
		{
			return PromptSize.unavailable("Prompt files could not be measured: " + e.getMessage());
		}
		long words = 0;
		try
		{
			for (byte[] content : contents)
				words += words(UTF_8.newDecoder().decode(ByteBuffer.wrap(content)).toString());
		}
		catch (CharacterCodingException _)
		{
			return new PromptSize(bytes, null, "Prompt words are unavailable because a prompt file is not UTF-8.");
		}
		return new PromptSize(bytes, words, null);
	}

	/**
	 * Indicates whether a staged path is within a participating source.
	 *
	 * @param relative the staged path
	 * @param resolved the snapshot's resolved target
	 * @param sources the participating roots
	 * @return whether the file participates
	 */
	private static boolean participates(Path relative, Path resolved, Set<Path> sources)
	{
		for (Path source : sources)
		{
			Path candidate = relative;
			if (source.isAbsolute())
				candidate = resolved;
			if (candidate.startsWith(source))
				return true;
		}
		return false;
	}

	/**
	 * Counts nonempty runs separated by Unicode whitespace or space characters.
	 *
	 * @param text the decoded prompt
	 * @return the word count
	 */
	private static long words(String text)
	{
		long result = 0;
		boolean inside = false;
		for (int index = 0; index < text.length();)
		{
			int codePoint = text.codePointAt(index);
			boolean whitespace = Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
			if (!whitespace && !inside)
				++result;
			inside = !whitespace;
			index += Character.charCount(codePoint);
		}
		return result;
	}
}
