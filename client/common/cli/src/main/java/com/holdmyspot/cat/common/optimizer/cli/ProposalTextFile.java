package com.holdmyspot.cat.common.optimizer.cli;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.FileReplacer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The file in a campaign's directory that holds the rendered proposal that the user reviews, so that a command's JSON
 * names the proposal's path instead of carrying its text, which can run to tens of thousands of characters.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
final class ProposalTextFile
{
	/**
	 * The file's name.
	 */
	static final String NAME = "proposal.md";
	private final Path file;
	private final FileReplacer replacer;

	/**
	 * Creates the file's accessor.
	 *
	 * @param directory the campaign's directory
	 * @param replacer  replaces the file
	 * @throws NullPointerException if any of the arguments are null
	 */
	ProposalTextFile(Path directory, FileReplacer replacer)
	{
		requireThat(directory, "directory").isNotNull();
		requireThat(replacer, "replacer").isNotNull();
		this.file = directory.resolve(NAME);
		this.replacer = replacer;
	}

	/**
	 * Replaces the file's text.
	 *
	 * @param text the rendered proposal, written exactly as it is
	 * @return the file's path
	 * @throws NullPointerException     if {@code text} is null
	 * @throws IllegalArgumentException if {@code text} is blank
	 * @throws IOException              if the file cannot be written
	 */
	Path write(String text) throws IOException
	{
		requireThat(text, "text").isNotBlank();
		Files.createDirectories(file.getParent());
		Path temporary = file.resolveSibling(NAME + ".tmp");
		Files.writeString(temporary, text, StandardCharsets.UTF_8);
		replacer.replace(temporary, file);
		return file;
	}
}
