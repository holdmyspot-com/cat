package com.holdmyspot.cat.common.optimizer.execution;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.optimizer.campaign.PromptSize;
import com.holdmyspot.cat.common.optimizer.inventory.FileInventory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.file.Path;

/**
 * Measures the target prompt file of a staged snapshot.
 */
public final class PromptMeasurements
{
	/**
	 * Prevents instantiation.
	 */
	private PromptMeasurements()
	{
	}

	/**
	 * Measures the staged target prompt file, checking its bytes against the snapshot. Derived sources, project rules
	 * and every other member of the prompt system are not counted.
	 *
	 * @param staged the staged snapshot
	 * @param inventory the snapshot inventory
	 * @param target the target prompt file, relative to the project
	 * @return observed sizes, or the reason a measurement is unavailable
	 * @throws SnapshotMismatchException if the staged target differs from its frozen snapshot
	 */
	public static PromptSize measure(Path staged, FileInventory inventory, Path target) throws SnapshotMismatchException
	{
		byte[] bytes;
		try
		{
			bytes = StagedTarget.read(staged, inventory, target).bytes();
		}
		catch (SnapshotMismatchException e)
		{
			throw e;
		}
		catch (IOException e)
		{
			return PromptSize.unavailable("The target prompt file could not be measured: " + e.getMessage());
		}
		try
		{
			return new PromptSize((long) bytes.length,
				words(UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()), null);
		}
		catch (CharacterCodingException _)
		{
			return new PromptSize((long) bytes.length, null,
				"Prompt words are unavailable because the target prompt file is not UTF-8.");
		}
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
