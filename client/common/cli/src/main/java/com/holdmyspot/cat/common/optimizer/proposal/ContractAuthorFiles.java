package com.holdmyspot.cat.common.optimizer.proposal;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.contract.WorkspaceFiles;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

import tools.jackson.databind.json.JsonMapper;

/**
 * The files that a contract author session's project receives: the draft directory, filled before the session
 * starts, and the context file that the contract tools read.
 *
 * @param json        the JSON mapper that writes the context file
 * @param draft       the name of the draft directory, relative to the project
 * @param source      fills the draft directory
 * @param contextFile the name of the context file, relative to the project
 * @param context     what the contract tools need to validate the proposal file and the draft directory
 */
public record ContractAuthorFiles(JsonMapper json, String draft, DraftSource source, String contextFile,
	ContractContext context) implements WorkspaceFiles
{
	/**
	 * Fills a new draft directory.
	 */
	@FunctionalInterface
	public interface DraftSource
	{
		/**
		 * Fills a draft directory.
		 *
		 * @param draft the draft directory, which need not exist
		 * @throws IOException if a file cannot be read or written
		 */
		void fill(Path draft) throws IOException;
	}

	/**
	 * Creates the files.
	 *
	 * @param json        the JSON mapper that writes the context file
	 * @param draft       the name of the draft directory, relative to the project
	 * @param source      fills the draft directory
	 * @param contextFile the name of the context file, relative to the project
	 * @param context     what the contract tools need to validate the proposal file and the draft directory
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code draft} or {@code contextFile} are blank
	 */
	public ContractAuthorFiles
	{
		requireThat(json, "json").isNotNull();
		requireThat(draft, "draft").isNotBlank();
		requireThat(source, "source").isNotNull();
		requireThat(contextFile, "contextFile").isNotBlank();
		requireThat(context, "context").isNotNull();
	}

	@Override
	public boolean isEmpty()
	{
		return false;
	}

	@Override
	public void write(Path directory) throws IOException
	{
		requireThat(directory, "directory").isNotNull();
		Path draftDirectory = directory.resolve(draft);
		if (Files.exists(draftDirectory, LinkOption.NOFOLLOW_LINKS))
			throw new FileAlreadyExistsException(draftDirectory.toString());
		Path contextPath = directory.resolve(contextFile);
		if (Files.exists(contextPath, LinkOption.NOFOLLOW_LINKS))
			throw new FileAlreadyExistsException(contextPath.toString());
		Files.createDirectories(draftDirectory);
		source.fill(draftDirectory);
		context.write(json, contextPath);
	}
}
