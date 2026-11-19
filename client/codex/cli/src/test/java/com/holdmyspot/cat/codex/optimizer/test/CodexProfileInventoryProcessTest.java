package com.holdmyspot.cat.codex.optimizer.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.codex.optimizer.CodexProfileInventory;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.optimizer.cli.HarnessVersion;
import com.holdmyspot.cat.common.optimizer.contract.ProfileCatalog;
import com.holdmyspot.cat.common.optimizer.contract.TrialProfile;
import com.holdmyspot.cat.common.test.ExecutableFixture;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.testng.annotations.Test;
import tools.jackson.core.JacksonException;

/**
 * Tests {@link CodexProfileInventory} over the launcher that {@link HarnessVersion#process()} returns, with a fake
 * {@code codex} executable that writes the model list to stdout and diagnostics to stderr.
 */
public final class CodexProfileInventoryProcessTest
{
	private static final String MODELS_START = "{\"models\":[{\"slug\":\"gpt-6-sol\",\"visibility\":\"list\",\n";
	private static final String MODELS_END = "\"supported_reasoning_levels\":[{\"effort\":\"low\"}]}]}\n";

	/**
	 * Creates the test.
	 */
	public CodexProfileInventoryProcessTest()
	{
	}

	/**
	 * Writes a fake {@code codex} executable that answers {@code --version} and otherwise prints the first part of
	 * stdout, then stderr, then the rest of stdout.
	 *
	 * @param directory the directory that receives the executable and its output files
	 * @param stdoutStart the first part of stdout
	 * @param stderr the text written to stderr between the two parts of stdout
	 * @param stdoutEnd the rest of stdout
	 * @return the path of the executable, without the extension that Windows adds
	 * @throws IOException if the files cannot be written
	 */
	private static Path writeCodex(Path directory, String stdoutStart, String stderr, String stdoutEnd)
		throws IOException
	{
		Files.writeString(directory.resolve("stdout-start.txt"), stdoutStart, UTF_8);
		Files.writeString(directory.resolve("stderr.txt"), stderr, UTF_8);
		Files.writeString(directory.resolve("stdout-end.txt"), stdoutEnd, UTF_8);
		if (Platform.current().isWindows())
		{
			ExecutableFixture.write(directory.resolve("codex.cmd"), """
				@echo off\r
				if "%~1"=="--version" (\r
				  echo codex-cli 0.169.0\r
				  exit /b 0\r
				)\r
				type "%~dp0stdout-start.txt"\r
				type "%~dp0stderr.txt" 1>&2\r
				type "%~dp0stdout-end.txt"\r
				""");
		}
		else
		{
			ExecutableFixture.write(directory.resolve("codex"), """
				#!/bin/sh
				if [ "$1" = "--version" ]; then
				  echo codex-cli 0.169.0
				  exit 0
				fi
				directory=$(dirname "$0")
				cat "$directory/stdout-start.txt"
				cat "$directory/stderr.txt" >&2
				cat "$directory/stdout-end.txt"
				""");
		}
		return directory.resolve("codex");
	}

	/**
	 * Verifies that diagnostics that {@code codex debug models} writes to stderr, before the model list and in the
	 * middle of it, including text that contains a brace, do not change the models that the inventory reads.
	 *
	 * @throws IOException if the inventory cannot be read
	 */
	@Test
	public void readsTheModelListFromStdoutOnly() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("cat-codex-models-")).toAbsolutePath();
			Path codex = writeCodex(directory, MODELS_START,
				"WARNING: {\"event\":\"stale\"} could not refresh the model cache\n", MODELS_END);
			ProfileCatalog inventory = new CodexProfileInventory(directory, codex.toString(),
				HarnessVersion.process()).inventory();

			requireThat(inventory.profiles(), "profiles").isEqualTo(List.of(new TrialProfile("gpt-6-sol", "low")));
			requireThat(inventory.source(), "source").contains("codex-cli 0.169.0");
		}
	}

	/**
	 * Verifies that a malformed model list is reported with the parser's failure as the cause, with the size and a
	 * bounded excerpt of the stdout that was read, and with the stderr that the command wrote.
	 *
	 * @throws IOException if the fake executable cannot be written
	 */
	@Test
	public void reportsWhatAMalformedModelListRead() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("cat-codex-models-")).toAbsolutePath();
			String padding = "x".repeat(200_000);
			String stdout = "{\"models\":[{\"slug\":\"" + padding + "-unterminated";
			Path codex = writeCodex(directory, stdout, "error: the connection was reset\n", "");
			CodexProfileInventory inventory = new CodexProfileInventory(directory, codex.toString(),
				HarnessVersion.process());
			try
			{
				inventory.inventory();
			}
			catch (IOException e)
			{
				requireThat(e.getCause(), "cause").isInstanceOf(JacksonException.class);
				String message = e.getMessage();
				requireThat(message, "message").contains("malformed model list");
				requireThat(message, "message").contains(e.getCause().getMessage().lines().findFirst().orElseThrow());
				requireThat(message, "message").contains(stdout.length() + " characters");
				requireThat(message, "message").contains("{\"models\":[{\"slug\":\"xxx");
				requireThat(message, "message").contains("x-unterminated");
				requireThat(message, "message").contains("error: the connection was reset");
				requireThat(message.length(), "message.length()").isLessThan(10_000);
				return;
			}
			throw new AssertionError("A malformed model list must be reported");
		}
	}
}
