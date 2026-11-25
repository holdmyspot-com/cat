package com.holdmyspot.cat.common.optimizer.execution;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static java.nio.file.StandardOpenOption.WRITE;

import com.holdmyspot.cat.common.PortablePaths;
import com.holdmyspot.cat.common.optimizer.contract.PromptTestFiles;
import com.holdmyspot.cat.common.optimizer.contract.TrialRecord;
import com.holdmyspot.cat.common.optimizer.evidence.ActivityFile;
import com.holdmyspot.cat.common.optimizer.inventory.FileContent;
import com.holdmyspot.cat.common.optimizer.inventory.FileInventory;
import com.holdmyspot.cat.common.optimizer.workspace.FileTree;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes the evidence presented to a check program.
 */
public final class CheckProgramInput
{
	/** The version of the complete directory layout and its serialized contents. */
	static final int FORMAT_VERSION = 1;
	private static final String PROJECT = "project";
	private static final String INITIAL = "initial";
	private static final String REPLIES = "replies.json";

	/**
	 * Stages the same input layout for local trials, retained peer trials and assertion examples.
	 *
	 * @param json the process mapper
	 * @param directory a new empty directory owned by the caller
	 * @param record the trial or example record
	 * @param checkFiles the confirmed bytes of the check files, keyed by normalized paths below the checks directory
	 * @throws NullPointerException if any argument or check file entry is null
	 * @throws IllegalArgumentException if a check file path is rooted, empty, not normalized or starts with {@code ..}
	 * @throws IOException if the evidence cannot be copied or written
	 */
	public static void stage(JsonMapper json, Path directory, TrialRecord record, Map<String, FileContent> checkFiles)
		throws IOException
	{
		requireThat(json, "json").isNotNull();
		requireThat(directory, "directory").isNotNull();
		requireThat(record, "record").isNotNull();
		requireThat(checkFiles, "checkFiles").isNotNull();

		copySnapshot(record.effects(), directory.resolve(PROJECT));
		copySnapshot(record.initialProject(), directory.resolve(INITIAL));
		ActivityFile.write(json, directory.resolve(ActivityFile.EXAMPLE_NAME), record.activity());
		Path replies = directory.resolve(REPLIES);
		try (OutputStream output = Files.newOutputStream(replies, CREATE_NEW, WRITE))
		{
			json.writeValue(output, record.replies());
		}
		catch (JacksonException e)
		{
			throw new IOException("Cannot write trial replies to " + replies, e);
		}

		Path checks = Files.createDirectory(directory.resolve(PromptTestFiles.CHECKS));
		for (Map.Entry<String, FileContent> entry : checkFiles.entrySet())
		{
			Path path = Path.of(entry.getKey());
			FileInventory.requireInventoryPath(path, "checkFile");
			requireThat(PortablePaths.isRooted(path), "isRooted(checkFile)").isFalse();
			Path file = checks.resolve(path);
			Files.createDirectories(file.getParent());
			Files.write(file, entry.getValue().bytes(), CREATE_NEW, WRITE);
		}
	}

	/**
	 * Copies a completed project snapshot into its own directory.
	 *
	 * @param source the recorded snapshot directory
	 * @param destination the path that will contain its copy
	 * @throws IOException if the snapshot cannot be copied
	 */
	private static void copySnapshot(Path source, Path destination) throws IOException
	{
		if (!Files.isDirectory(source, NOFOLLOW_LINKS))
			throw new IOException("Expected a project snapshot directory: " + source);
		Files.createDirectory(destination);
		FileTree.copy(source, destination, path -> false);
	}

	/**
	 * Prevents construction.
	 */
	private CheckProgramInput()
	{
	}
}
