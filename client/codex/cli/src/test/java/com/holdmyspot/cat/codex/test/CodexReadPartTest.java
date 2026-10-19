package com.holdmyspot.cat.codex.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.codex.CodexReadPart;
import com.holdmyspot.cat.codex.CommandShell;
import com.holdmyspot.cat.common.TemporaryDirectories;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Verifies that {@code read-part} prints output that {@code run-part} saved in parts that fit the output budget of a
 * Codex call, that following the line that ends each part prints the whole output, and that it refuses every other
 * file.
 */
public final class CodexReadPartTest
{
	/**
	 * The shell that runs the commands that {@code read-part} prints on this platform.
	 */
	private static final CommandShell SHELL = CommandShell.current();
	private static final Path READ_PART = Path.of("/plugin/bin").toAbsolutePath().resolve(SHELL.launcher("read-part"));
	/**
	 * The line separator that ends each line of diagnostics.
	 */
	private static final String EOL = System.lineSeparator();
	private static final JsonMapper JSON = JsonMapper.builder().build();
	/**
	 * The line that ends a part that more parts follow.
	 */
	private static final Pattern NEXT_PART = Pattern.compile("\\[(?<inside>The last line continues at the start of " +
		"the next part\\. )?More output follows\\. Before any other command or answer, run: " +
		Pattern.quote(SHELL.command(READ_PART.toString())) + " (?<path>.+) (?<position>\\S+)]\n\\z");

	/**
	 * Creates a test instance.
	 */
	public CodexReadPartTest()
	{
	}

	/**
	 * Verifies that a saved output that fits in one part is printed whole, followed by the line that ends the last
	 * part, which names the output and says that it was deleted.
	 *
	 * @throws IOException if the file cannot be written
	 */
	@Test
	public void shortOutputEndsWithItsPath() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path temporary = cleanup.add(Files.createTempDirectory("read-part-"));
			Path file = savedOutput(temporary, "first\nsecond\n");

			Call call = readPart(Map.of("TMPDIR", temporary.toString()), file.toString());

			requireThat(call.output(), "output").isEqualTo("first\nsecond\n" + closing(file));
			requireThat(call.status(), "status").isEqualTo(0);
		}
	}

	/**
	 * Verifies that a saved output in the temporary directory of a CAT session is printed.
	 *
	 * @throws IOException if the file cannot be written
	 */
	@Test
	public void sessionOutputIsPrinted() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("read-part-"));
			Path file = savedOutput(project.resolve(".cat/sessions/01a147d8-bf56-7283-92b4-12dcaeb53738/temp"), "a\n");

			Call call = readPart(Map.of(), file.toString());

			requireThat(call.output(), "output").isEqualTo("a\n" + closing(file));
			requireThat(call.status(), "status").isEqualTo(0);
		}
	}

	/**
	 * Verifies that the last part exits with the status that the saved output's {@code .exit-status} file holds.
	 *
	 * @throws IOException if the file cannot be written
	 */
	@Test
	public void lastPartExitsWithSavedStatus() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path temporary = cleanup.add(Files.createTempDirectory("read-part-"));
			Path file = savedOutput(temporary, "failed\n");
			Files.writeString(Path.of(file + ".exit-status"), "3\n");

			Call call = readPart(Map.of("TMPDIR", temporary.toString()), file.toString());

			requireThat(call.output(), "output").isEqualTo("failed\n" + closing(file));
			requireThat(call.status(), "status").isEqualTo(3);
		}
	}

	/**
	 * Verifies that a file that {@code run-part} did not save is refused: one outside a {@code run-part-*} folder, one
	 * without the saved exit status beside it, one with another name, and one in a folder that is not a temporary
	 * directory.
	 *
	 * @throws IOException if the files cannot be written
	 */
	@Test
	public void fileNotSavedByRunPartIsRefused() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path temporary = cleanup.add(Files.createTempDirectory("read-part-"));
			Path notes = Files.writeString(Files.createDirectory(temporary.resolve("notes")).resolve("output.txt"),
				"a\n");
			Files.writeString(Path.of(notes + ".exit-status"), "0\n");
			Path withoutStatus = savedOutput(temporary, "a\n");
			Files.delete(Path.of(withoutStatus + ".exit-status"));
			Path otherName = Files.writeString(savedOutput(temporary, "a\n").resolveSibling("other.txt"), "a\n");
			Path elsewhere = savedOutput(Files.createDirectory(temporary.resolve("project")), "a\n");

			for (Path file : new Path[]{notes, withoutStatus, otherName, elsewhere})
			{
				Call call = readPart(Map.of("TMPDIR", temporary.toString()), file.toString());

				requireThat(call.status(), "status").isEqualTo(1);
				requireThat(call.output(), "output").isEmpty();
				requireThat(call.error(), "error").isEqualTo("read-part: \"" + file + "\" is not output that " +
					"run-part saved, and read-part prints nothing else" + EOL);
			}
		}
	}

	/**
	 * Verifies that a saved output whose folder is gone says that it was deleted after its last part was read.
	 *
	 * @throws IOException if the file cannot be written
	 */
	@Test
	public void deletedOutputSaysWhy() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path temporary = cleanup.add(Files.createTempDirectory("read-part-"));
			Path file = savedOutput(temporary, "a\n");
			Files.delete(Path.of(file + ".exit-status"));
			Files.delete(file);
			Files.delete(file.getParent());

			Call call = readPart(Map.of("TMPDIR", temporary.toString()), file.toString(), "2");

			requireThat(call.status(), "status").isEqualTo(1);
			requireThat(call.output(), "output").isEmpty();
			requireThat(call.error(), "error").isEqualTo("read-part: \"" + file + "\" was deleted after its last " +
				"part was read" + EOL);
		}
	}

	/**
	 * Verifies that in a Codex thread each part fits the call's budget after JSON escaping, that a part that more parts
	 * follow exits with status 0, and that following the line that ends each part prints the file whole, including
	 * escaped characters, characters that are not ASCII, a byte that is not part of a UTF-8 character, and lines too
	 * long for one part.
	 *
	 * @throws IOException if the file cannot be written
	 */
	@Test
	public void partsReassembleFile() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("read-part-"));
			Path codexHome = Files.createDirectory(directory.resolve("codex"));
			Files.writeString(codexHome.resolve("config.toml"), "model = \"m\"\ntool_output_token_limit = 300\n");
			ByteArrayOutputStream content = new ByteArrayOutputStream();
			for (int i = 1; i <= 30; ++i)
				content.writeBytes(("line " + i + ": \"quoted\" \\ back\ttab\n").getBytes(UTF_8));
			content.writeBytes("control \u0001\u0002\u001f bytes\n".getBytes(UTF_8));
			content.writeBytes(new byte[]{'a', (byte) 0xFF, 'b', '\n'});
			content.writeBytes(("lorem ipsum dolor sit amet ".repeat(120) + "\n").getBytes(UTF_8));
			content.writeBytes(("é".repeat(1000) + "\n").getBytes(UTF_8));
			content.writeBytes(("x".repeat(1500) + "\n").getBytes(UTF_8));
			content.writeBytes("last\n".getBytes(UTF_8));
			Path file = savedOutput(directory, "");
			Files.write(file, content.toByteArray());
			Map<String, String> environment = Map.of("CODEX_THREAD_ID", "thread", "CODEX_HOME", codexHome.toString(),
				"TMPDIR", directory.toString());

			byte[] printed = readAll(environment, file, 300 * 4 - 512);

			requireThat(Arrays.equals(printed, content.toByteArray()), "reassembled").isTrue();
		}
	}

	/**
	 * Verifies that a line too long for one part is split between UTF-8 characters, so that each part shows its
	 * characters whole.
	 *
	 * @throws IOException if the file cannot be written
	 */
	@Test
	public void splitKeepsCharactersWhole() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("read-part-"));
			Path codexHome = Files.createDirectory(directory.resolve("codex"));
			Files.writeString(codexHome.resolve("config.toml"), "tool_output_token_limit = 300\n");
			Path file = savedOutput(directory, "€".repeat(1000) + "\n");
			Map<String, String> environment = Map.of("CODEX_THREAD_ID", "thread", "CODEX_HOME", codexHome.toString(),
				"TMPDIR", directory.toString());

			Call call = readPart(environment, file.toString());
			Matcher footer = NEXT_PART.matcher(call.output());

			requireThat(footer.find(), "footer").isTrue();
			requireThat(footer.group("inside"), "inside").isNotNull();
			String piece = call.output().substring(0, footer.start() - 1);
			requireThat(piece, "piece").matches("€+");
			requireThat(footer.group("position"), "position").isEqualTo("1:" + (piece.getBytes(UTF_8).length + 1));
		}
	}

	/**
	 * Verifies that outside a Codex thread there is no budget, so a long file is printed in one part.
	 *
	 * @throws IOException if the file cannot be written
	 */
	@Test
	public void noThreadPrintsOnePart() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path temporary = cleanup.add(Files.createTempDirectory("read-part-"));
			String text = "a line of the file\n".repeat(5000);
			Path file = savedOutput(temporary, text);

			Call call = readPart(Map.of("TMPDIR", temporary.toString()), file.toString());

			requireThat(call.output(), "output").isEqualTo(text + closing(file));
		}
	}

	/**
	 * Verifies that a part starts at the position that the agent names.
	 *
	 * @throws IOException if the file cannot be written
	 */
	@Test
	public void positionStartsPart() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path temporary = cleanup.add(Files.createTempDirectory("read-part-"));
			Path file = savedOutput(temporary, "one\ntwo words\nthree\n");

			Call call = readPart(Map.of("TMPDIR", temporary.toString()), file.toString(), "2:5");

			requireThat(call.output(), "output").isEqualTo("words\nthree\n" + closing(file));
		}
	}

	/**
	 * Verifies that a position after the file's last line says that the file ends before it.
	 *
	 * @throws IOException if the file cannot be written
	 */
	@Test
	public void positionAfterEndSaysFileEnds() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path temporary = cleanup.add(Files.createTempDirectory("read-part-"));
			Path file = savedOutput(temporary, "one\n");

			Call call = readPart(Map.of("TMPDIR", temporary.toString()), file.toString(), "9");

			requireThat(call.output(), "output").isEqualTo("[" + file + " ends before position 9]\n");
			requireThat(call.status(), "status").isEqualTo(0);
		}
	}

	/**
	 * Verifies that a position that {@code read-part} could not have printed is a usage error.
	 *
	 * @throws IOException if the file cannot be written
	 */
	@Test
	public void invalidPositionIsUsageError() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path temporary = cleanup.add(Files.createTempDirectory("read-part-"));
			Path file = savedOutput(temporary, "one\n");

			for (String position : new String[]{"0", "x", "1:0", "1:9"})
			{
				Call call = readPart(Map.of("TMPDIR", temporary.toString()), file.toString(), position);

				requireThat(call.status(), "status").isEqualTo(2);
				requireThat(call.output(), "output").isEmpty();
				requireThat(call.error(), "error").isEqualTo("read-part: \"" + position +
					"\" is not a position that read-part printed" + EOL);
			}
		}
	}

	/**
	 * Verifies that a call without a path is a usage error.
	 */
	@Test
	public void missingPathIsUsageError()
	{
		Call call = readPart(Map.of());

		requireThat(call.status(), "status").isEqualTo(2);
		requireThat(call.error(), "error").isEqualTo("usage: read-part <path> [<position>]" + EOL);
	}

	/**
	 * Verifies that a path in the folder of a saved output that names no file suggests the one file of the folder whose
	 * name is close to it.
	 *
	 * @throws IOException if the folder cannot be written
	 */
	@Test
	public void missingFileSuggestsCloseName() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path temporary = cleanup.add(Files.createTempDirectory("read-part-"));
			Path file = savedOutput(temporary, "one\n");
			Path directory = file.getParent();
			Path typo = directory.resolve("outpt.txt");

			Call call = readPart(Map.of("TMPDIR", temporary.toString()), typo.toString(), "4");

			requireThat(call.status(), "status").isEqualTo(1);
			requireThat(call.error(), "error").isEqualTo("read-part: cannot read \"" + typo + "\"" + EOL +
				"read-part: \"" + directory + "\" holds no such file, but it holds \"output.txt\". If you meant it, " +
				"run " + SHELL.command(READ_PART.toString(), file.toString(), "4") +
				" before any other command or your answer." + EOL);
		}
	}

	/**
	 * Verifies that a file that holds a NUL byte is refused as binary.
	 *
	 * @throws IOException if the file cannot be written
	 */
	@Test
	public void binaryFileIsRefused() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path temporary = cleanup.add(Files.createTempDirectory("read-part-"));
			Path file = savedOutput(temporary, "");
			Files.write(file, new byte[]{'a', 0, 'b'});

			Call call = readPart(Map.of("TMPDIR", temporary.toString()), file.toString());

			requireThat(call.status(), "status").isEqualTo(1);
			requireThat(call.output(), "output").isEmpty();
			requireThat(call.error(), "error").isEqualTo("read-part: \"" + file + "\" holds NUL bytes, so it is " +
				"binary, not text; inspect it with a tool for its format instead" + EOL);
		}
	}

	/**
	 * Saves output in a {@code run-part-*} folder the way {@code run-part} does, with exit status 0.
	 *
	 * @param temporary the temporary directory that holds the folder
	 * @param content the output
	 * @return the saved output
	 * @throws IOException if the folder cannot be created
	 */
	private static Path savedOutput(Path temporary, String content) throws IOException
	{
		assert temporary != null;
		assert content != null;
		Path folder = Files.createTempDirectory(Files.createDirectories(temporary), "run-part-");
		Path saved = Files.writeString(folder.resolve("output.txt"), content);
		Files.writeString(Path.of(saved + ".exit-status"), "0\n");
		return saved;
	}

	/**
	 * Returns the line that ends the last part of a saved output.
	 *
	 * @param file the saved output
	 * @return the line
	 */
	private static String closing(Path file)
	{
		assert file != null;
		return "[End of " + file + ". The file was deleted]\n";
	}

	/**
	 * Returns a file's path as the command that prints the next part names it, quoted if the shell needs quotes.
	 *
	 * @param file the file
	 * @return the argument
	 */
	private static String argument(Path file)
	{
		String launcher = SHELL.command(READ_PART.toString());
		return SHELL.command(READ_PART.toString(), file.toString()).substring(launcher.length() + 1);
	}

	/**
	 * Prints every part of a file by following the line that ends each part, checking that each part fits the budget
	 * and that each part that more parts follow exits with status 0.
	 *
	 * @param environment the environment of each call
	 * @param file the file
	 * @param limit the number of bytes that each part may take after JSON escaping
	 * @return the file's bytes as the parts printed them
	 */
	private static byte[] readAll(Map<String, String> environment, Path file, int limit)
	{
		ByteArrayOutputStream printed = new ByteArrayOutputStream();
		String position = "1";
		for (int calls = 0; calls < 1000; ++calls)
		{
			Call call = readPart(environment, file.toString(), position);
			requireThat(call.error(), "error").isEmpty();
			requireThat(shownSize(call.bytes()), "shownSize").isLessThanOrEqualTo(limit);
			if (call.output().endsWith(closing(file)))
			{
				requireThat(call.status(), "status").isEqualTo(0);
				printed.write(call.bytes(), 0, call.bytes().length - closing(file).getBytes(UTF_8).length);
				return printed.toByteArray();
			}
			requireThat(call.status(), "status").isEqualTo(0);
			Matcher footer = NEXT_PART.matcher(call.output());
			requireThat(footer.find(), "footer").isTrue();
			requireThat(footer.group("path"), "path").isEqualTo(argument(file));
			int end = call.bytes().length - call.output().substring(footer.start()).getBytes(UTF_8).length;
			// A part that ends inside a line ends that piece of the line with a newline that the file does not hold.
			if (footer.group("inside") != null)
				--end;
			printed.write(call.bytes(), 0, end);
			position = footer.group("position");
		}
		throw new AssertionError("The parts did not end");
	}

	/**
	 * Returns the number of bytes that Codex shows of a part after JSON escaping, with each byte that is not part of a
	 * UTF-8 character shown as U+FFFD.
	 *
	 * @param part the part
	 * @return the number of bytes
	 */
	private static int shownSize(byte[] part)
	{
		return JSON.writeValueAsString(new String(part, UTF_8)).getBytes(UTF_8).length - 2;
	}

	/**
	 * Runs {@code read-part}.
	 *
	 * @param environment the environment
	 * @param args the arguments
	 * @return the call's streams and exit status
	 */
	private static Call readPart(Map<String, String> environment, String... args)
	{
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		ByteArrayOutputStream error = new ByteArrayOutputStream();
		int status = CodexReadPart.run(args, SHELL, READ_PART, environment, output, new PrintStream(error, true, UTF_8));
		return new Call(output.toByteArray(), error.toString(UTF_8), status);
	}

	/**
	 * The streams and exit status of one call.
	 *
	 * @param bytes the standard output
	 * @param error the standard error
	 * @param status the exit status
	 */
	private record Call(byte[] bytes, String error, int status)
	{
		/**
		 * Creates a call.
		 *
		 * @param bytes the standard output
		 * @param error the standard error
		 * @param status the exit status
		 */
		private Call
		{
			assert bytes != null;
			assert error != null;
		}

		/**
		 * Returns the standard output as text.
		 *
		 * @return the text
		 */
		String output()
		{
			return new String(bytes, UTF_8);
		}
	}
}
