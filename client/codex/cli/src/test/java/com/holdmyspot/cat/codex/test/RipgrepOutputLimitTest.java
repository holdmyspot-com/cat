package com.holdmyspot.cat.codex.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.codex.RipgrepOutputLimit;
import com.holdmyspot.cat.common.TemporaryDirectories;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.testng.annotations.Test;

/**
 * Verifies that {@code run-part} keeps the saved output of an {@code rg} search that runs in PowerShell short.
 */
public final class RipgrepOutputLimitTest
{
	/**
	 * Creates a test instance.
	 */
	public RipgrepOutputLimitTest()
	{
	}

	/**
	 * Verifies that a command that is one search runs with each line cut to its first 500 bytes.
	 */
	@Test
	public void searchCutsLongLines()
	{
		requireThat(RipgrepOutputLimit.limitedCommand("\n  rg -n 'a b' src\n"), "limitedCommand").
			isEqualTo(Optional.of("rg --max-columns=500 --max-columns-preview -n 'a b' src"));
		requireThat(RipgrepOutputLimit.limitedCommand("rg -- --help"), "pattern").
			isEqualTo(Optional.of("rg --max-columns=500 --max-columns-preview -- --help"));
	}

	/**
	 * Verifies that a command that does more than one search, asks for help, or runs another program runs unchanged.
	 */
	@Test
	public void otherCommandsRunUnchanged()
	{
		for (String command : List.of("rg needle | Select-Object -First 5", "rg needle > found.txt", "rg --help",
			"rg -V", "rga needle", "Get-ChildItem", "$env:CAT_RG_LINE_LIMIT = 0; rg needle"))
		{
			requireThat(RipgrepOutputLimit.limitedCommand(command), "limitedCommand").withContext(command, "command").
				isEqualTo(Optional.empty());
		}
	}

	/**
	 * Verifies that the line limit is 250 unless {@code CAT_RG_LINE_LIMIT} sets another one, and that a limit that is
	 * not a whole number is rejected.
	 */
	@Test
	public void lineLimitDefaultsTo250()
	{
		requireThat(RipgrepOutputLimit.lineLimit(Map.of()), "default").isEqualTo(250);
		requireThat(RipgrepOutputLimit.lineLimit(Map.of("CAT_RG_LINE_LIMIT", "0")), "none").isEqualTo(0);
		try
		{
			RipgrepOutputLimit.lineLimit(Map.of("CAT_RG_LINE_LIMIT", "many"));
			throw new AssertionError("A limit that is not a number was accepted");
		}
		catch (IllegalArgumentException e)
		{
			requireThat(e.getMessage(), "message").contains("CAT_RG_LINE_LIMIT").contains("many");
		}
	}

	/**
	 * Verifies that output with more lines than the limit is replaced by a request to narrow the search, with exit
	 * status 2, and that output within the limit, or with no limit, keeps its lines and the search's exit status.
	 *
	 * @throws IOException if the temporary file cannot be written or read
	 */
	@Test
	public void longOutputIsReplaced() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path saved = cleanup.add(Files.createTempDirectory("rg-limit-")).resolve("output.txt");
			Files.writeString(saved, "a\nb\nc");

			requireThat(RipgrepOutputLimit.limitOutput(saved, 1, 3), "withinLimit").isEqualTo(1);
			requireThat(RipgrepOutputLimit.limitOutput(saved, 0, 0), "noLimit").isEqualTo(0);
			requireThat(Files.readString(saved), "kept").isEqualTo("a\nb\nc");

			requireThat(RipgrepOutputLimit.limitOutput(saved, 0, 2), "overLimit").isEqualTo(2);
			requireThat(Files.readString(saved), "replaced").startsWith("rg: the search produced more than 2 lines").
				contains("$env:CAT_RG_LINE_LIMIT = 0;");
		}
	}
}
