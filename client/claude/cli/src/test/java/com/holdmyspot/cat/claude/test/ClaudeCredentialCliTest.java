package com.holdmyspot.cat.claude.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import com.holdmyspot.cat.claude.ClaudeCredentialCli;
import com.holdmyspot.cat.claude.credential.ClaudeCredentialFile;
import com.holdmyspot.cat.claude.credential.CredentialRefusedException;
import com.holdmyspot.cat.common.FileReplacer;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tests the {@code claude-credential} command through {@link ClaudeCredentialCli#run}: what each form saves, reports
 * and removes, its exit status, the arguments and environments that it refuses, and that no output holds the token.
 */
public final class ClaudeCredentialCliTest
{
	/**
	 * The fixture Anthropic API key.
	 */
	private static final String API_KEY = "sk-ant-api03-fixture";
	/**
	 * The fixture token that {@code claude setup-token} would print.
	 */
	private static final String SETUP_TOKEN = "sk-ant-oat01-fixture";
	/**
	 * Renames a saved file over the earlier one without waiting between attempts.
	 */
	private static final FileReplacer REPLACER = new FileReplacer(Platform.current(), () ->
	{
	}, Clock.systemUTC());
	private final JsonMapper json = JsonMapper.builder().build();

	/**
	 * Creates the test.
	 */
	public ClaudeCredentialCliTest()
	{
	}

	/**
	 * {@code set} saves the token that standard input holds, without its trailing CR LF, exits with status 0 and
	 * reports the token's class and the file's path, and no output stream holds the token.
	 *
	 * @throws IOException                if the fixture cannot be created or read
	 * @throws CredentialRefusedException if the saved file is refused
	 */
	@Test
	public void setSavesTokenFromInput() throws IOException, CredentialRefusedException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = home(cleanup);

			Result result = run(Map.of(), SETUP_TOKEN + "\r\n", "set", "--home", home.toString());

			requireThat(result.status(), "status").withContext(result.stderr(), "stderr").isEqualTo(0);
			requireThat(result.stdout(), "stdout").contains("subscription").
				contains(ClaudeCredentialFile.path(home).toString());
			result.requireNoToken(SETUP_TOKEN);
			requireThat(ClaudeCredentialFile.read(json, home).orElseThrow().token(), "token").isEqualTo(SETUP_TOKEN);
		}
	}

	/**
	 * {@code set --json} prints one JSON line whose result is {@code saved}, with the class, the path and whether an
	 * earlier token was replaced, and a second {@code set} reports the replacement.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void setPrintsJsonLine() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = home(cleanup);

			Result first = run(Map.of(), API_KEY + "\n", "set", "--home", home.toString(), "--json");
			Result second = run(Map.of(), SETUP_TOKEN, "set", "--home", home.toString(), "--json");

			requireThat(first.status(), "firstStatus").withContext(first.stderr(), "stderr").isEqualTo(0);
			JsonNode saved = first.jsonLine(json);
			requireThat(saved.path("result").asString(), "result").isEqualTo("saved");
			requireThat(saved.path("login_class").asString(), "login_class").isEqualTo("api_key");
			requireThat(saved.path("path").asString(), "path").isEqualTo(ClaudeCredentialFile.path(home).toString());
			requireThat(saved.path("replaced").asBoolean(true), "replaced").isFalse();
			requireThat(second.status(), "secondStatus").isEqualTo(0);
			requireThat(second.jsonLine(json).path("replaced").asBoolean(false), "replacedAgain").isTrue();
			first.requireNoToken(API_KEY);
			second.requireNoToken(SETUP_TOKEN);
		}
	}

	/**
	 * Returns runs that the command refuses before any change: the input on standard input, the environment's
	 * additions, and the arguments after {@code --home <home>}, where {@code <home>} stands for the fixture home.
	 *
	 * @return the input, the environment and the arguments
	 */
	@DataProvider
	public static Object[][] refusedRuns()
	{
		List<Object[]> runs = new ArrayList<>();
		// A token as an argument, which process listings and shell history would show
		runs.add(new Object[]{"", Map.of(), List.of("set", API_KEY, "--home", "<home>")});
		runs.add(new Object[]{"", Map.of(), List.of("set", "--token", API_KEY, "--home", "<home>")});
		// A relative source home
		runs.add(new Object[]{API_KEY, Map.of(), List.of("set", "--home", "relative/home")});
		// A source home that is no valid path, whose error would quote the argument that holds the token
		runs.add(new Object[]{"", Map.of(), List.of("set", "--home", "/home\u0000" + API_KEY)});
		// A form that the command does not have
		runs.add(new Object[]{API_KEY, Map.of(), List.of("show", "--home", "<home>")});
		// A --json that is not the last argument
		runs.add(new Object[]{API_KEY, Map.of(), List.of("set", "--json", "--home", "<home>")});
		// An empty token
		runs.add(new Object[]{" \r\n", Map.of(), List.of("set", "--home", "<home>")});
		// A token of neither accepted form
		runs.add(new Object[]{"sk-proj-fixture", Map.of(), List.of("set", "--home", "<home>")});
		// A run in a nested session's environment, whose session must not choose what its parent's launches bill
		runs.add(new Object[]{API_KEY, Map.of("CAT_NESTED_SESSION", "1"), List.of("set", "--home", "<home>")});
		return runs.toArray(Object[][]::new);
	}

	/**
	 * Each refused run exits with status 2, writes no file and no output that holds the token or the argument that
	 * held it.
	 *
	 * @param input       standard input
	 * @param environment the environment's variables
	 * @param arguments   the arguments, with {@code <home>} for the fixture home
	 * @throws IOException if the fixture cannot be created
	 */
	@Test(dataProvider = "refusedRuns")
	public void refusesBeforeAnyChange(String input, Map<String, String> environment, List<String> arguments)
		throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = home(cleanup);
			String[] args = arguments.stream().map(argument -> argument.replace("<home>", home.toString())).
				toArray(String[]::new);

			Result result = run(environment, input, args);

			requireThat(result.status(), "status").withContext(result.stdout(), "stdout").
				withContext(result.stderr(), "stderr").isEqualTo(2);
			result.requireNoToken(API_KEY);
			result.requireNoToken("sk-proj-fixture");
			requireThat(Files.exists(home.resolve(".cat"), NOFOLLOW_LINKS), "cat").isFalse();
		}
	}

	/**
	 * {@code status} reports {@code usable} with the class for a usable file and exits with status 0, and reports
	 * {@code absent} with status 2 when there is no file.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void statusReportsUsableAndAbsent() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = home(cleanup);
			Result absent = run(Map.of(), "", "status", "--home", home.toString(), "--json");
			run(Map.of(), API_KEY, "set", "--home", home.toString());

			Result usable = run(Map.of(), "", "status", "--home", home.toString(), "--json");

			requireThat(absent.status(), "absentStatus").isEqualTo(2);
			requireThat(absent.jsonLine(json).path("result").asString(), "absent").isEqualTo("absent");
			requireThat(usable.status(), "usableStatus").withContext(usable.stderr(), "stderr").isEqualTo(0);
			JsonNode line = usable.jsonLine(json);
			requireThat(line.path("result").asString(), "usable").isEqualTo("usable");
			requireThat(line.path("login_class").asString(), "login_class").isEqualTo("api_key");
			requireThat(line.path("path").asString(), "path").isEqualTo(ClaudeCredentialFile.path(home).toString());
			usable.requireNoToken(API_KEY);
		}
	}

	/**
	 * {@code status} reports a file that other users may read as {@code unusable}, naming the permissions to clear,
	 * and exits with status 2.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void statusReportsUnusableMode() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = home(cleanup);
			run(Map.of(), API_KEY, "set", "--home", home.toString());
			Files.setPosixFilePermissions(ClaudeCredentialFile.path(home), PosixFilePermissions.fromString("rw-r--r--"));

			Result result = run(Map.of(), "", "status", "--home", home.toString(), "--json");

			requireThat(result.status(), "status").isEqualTo(2);
			JsonNode line = result.jsonLine(json);
			requireThat(line.path("result").asString(), "result").isEqualTo("unusable");
			requireThat(line.path("reason").asString(), "reason").contains("group read").contains("other read");
			result.requireNoToken(API_KEY);
		}
	}

	/**
	 * Without {@code --home}, the command uses the home folder that the environment names, whatever
	 * {@code CLAUDE_CONFIG_DIR} names.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void defaultHomeIgnoresClaudeConfigDir() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = home(cleanup);
			Path configuration = Files.createDirectories(home.resolve("elsewhere/.claude"));
			Map<String, String> environment = Map.of(Platform.current().homeVariable(), home.toString(),
				"CLAUDE_CONFIG_DIR", configuration.toString());

			Result result = run(environment, "", "status", "--json");

			requireThat(result.jsonLine(json).path("path").asString(), "path").
				isEqualTo(ClaudeCredentialFile.path(home).toString());
		}
	}

	/**
	 * {@code remove} deletes a present file and exits with status 0, and exits with status 0 when no file is present.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void removeLeavesNoFile() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = home(cleanup);
			run(Map.of(), SETUP_TOKEN, "set", "--home", home.toString());

			Result removed = run(Map.of(), "", "remove", "--home", home.toString(), "--json");
			Result absent = run(Map.of(), "", "remove", "--home", home.toString(), "--json");

			requireThat(removed.status(), "removedStatus").withContext(removed.stderr(), "stderr").isEqualTo(0);
			requireThat(removed.jsonLine(json).path("result").asString(), "removed").isEqualTo("removed");
			requireThat(Files.exists(ClaudeCredentialFile.path(home), NOFOLLOW_LINKS), "file").isFalse();
			requireThat(absent.status(), "absentStatus").isEqualTo(0);
			requireThat(absent.jsonLine(json).path("result").asString(), "absent").isEqualTo("absent");
		}
	}

	/**
	 * A {@code set} whose folder the test makes unwritable exits with status 1, naming the path, and leaves the earlier
	 * file unchanged.
	 *
	 * @throws IOException if the fixture cannot be created or read
	 */
	@Test
	public void failedWriteExitsOne() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = home(cleanup);
			run(Map.of(), API_KEY, "set", "--home", home.toString());
			Path file = ClaudeCredentialFile.path(home);
			byte[] earlier = Files.readAllBytes(file);
			Files.setPosixFilePermissions(file.getParent(), PosixFilePermissions.fromString("r-x------"));
			Result result;
			try
			{
				result = run(Map.of(), SETUP_TOKEN, "set", "--home", home.toString());
			}
			finally
			{
				Files.setPosixFilePermissions(file.getParent(), PosixFilePermissions.fromString("rwx------"));
			}

			requireThat(result.status(), "status").isEqualTo(1);
			requireThat(result.stderr(), "stderr").contains(file.getParent().toString());
			requireThat(Files.readAllBytes(file), "file").isEqualTo(earlier);
			result.requireNoToken(SETUP_TOKEN);
		}
	}

	/**
	 * Creates a fixture source home.
	 *
	 * @param cleanup removes the home
	 * @return the home folder
	 * @throws IOException if the folder cannot be created
	 */
	private static Path home(TemporaryDirectories cleanup) throws IOException
	{
		return cleanup.add(Files.createTempDirectory("cat-claude-credential-")).toRealPath();
	}

	/**
	 * Runs the command with standard input that is not a terminal.
	 *
	 * @param environment the command's environment
	 * @param input       standard input
	 * @param args        the command's arguments
	 * @return the exit status and the output streams
	 */
	private static Result run(Map<String, String> environment, String input, String... args)
	{
		ByteArrayOutputStream stdout = new ByteArrayOutputStream();
		ByteArrayOutputStream stderr = new ByteArrayOutputStream();
		int status;
		try (PrintStream out = new PrintStream(stdout, true, UTF_8);
			PrintStream err = new PrintStream(stderr, true, UTF_8))
		{
			status = ClaudeCredentialCli.run(args, environment, Platform.current(), null,
				new ByteArrayInputStream(input.getBytes(UTF_8)), REPLACER, out, err);
		}
		return new Result(status, stdout.toString(UTF_8), stderr.toString(UTF_8));
	}

	/**
	 * A run's exit status and output.
	 *
	 * @param status the exit status
	 * @param stdout the standard output
	 * @param stderr the standard error
	 */
	private record Result(int status, String stdout, String stderr)
	{
		/**
		 * Requires that neither output stream holds a secret.
		 *
		 * @param secret the secret
		 */
		private void requireNoToken(String secret)
		{
			requireThat(stdout, "stdout").doesNotContain(secret);
			requireThat(stderr, "stderr").doesNotContain(secret);
		}

		/**
		 * Returns the one JSON line of the standard output.
		 *
		 * @param json parses the line
		 * @return the line's object
		 */
		private JsonNode jsonLine(JsonMapper json)
		{
			List<String> lines = stdout.lines().toList();
			requireThat(lines, "lines").withContext(stderr, "stderr").size().isEqualTo(1);
			JsonNode line = json.readTree(lines.getFirst());
			requireThat(line.isObject(), "isObject").isTrue();
			return line;
		}
	}
}
