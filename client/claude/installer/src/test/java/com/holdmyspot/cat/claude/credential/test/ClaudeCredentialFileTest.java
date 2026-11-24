package com.holdmyspot.cat.claude.credential.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import com.holdmyspot.cat.claude.credential.ClaudeCredentialFile;
import com.holdmyspot.cat.claude.credential.CredentialRefusedException;
import com.holdmyspot.cat.common.FileReplacer;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.optimizer.port.HostLogin;
import com.holdmyspot.cat.common.runner.SecretFile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tests that {@link ClaudeCredentialFile} reads, saves and removes CAT's Claude credential file of a source home with
 * owner-only access, refuses every content and path that a launch cannot use, and never names the token.
 */
public final class ClaudeCredentialFileTest
{
	/**
	 * The fixture Anthropic API key.
	 */
	private static final String API_KEY = "sk-ant-api03-fixture";
	/**
	 * The fixture token that {@code claude setup-token} would print.
	 */
	private static final String SETUP_TOKEN = "sk-ant-oat01-fixture";
	private final JsonMapper json = JsonMapper.builder().build();
	/**
	 * Renames a new file over the earlier one without waiting between attempts.
	 */
	private final FileReplacer replacer = new FileReplacer(Platform.current(), () ->
	{
	}, Clock.systemUTC());

	/**
	 * Creates the test.
	 */
	public ClaudeCredentialFileTest()
	{
	}

	/**
	 * The file of a source home is {@code .cat/claude/.credentials.json} in that home.
	 */
	@Test
	public void pathIsInSourceHome()
	{
		Path home = Path.of("/home/user").toAbsolutePath();

		requireThat(ClaudeCredentialFile.path(home), "path").isEqualTo(home.resolve(".cat/claude/.credentials.json"));
	}

	/**
	 * Returns each fixture token with the class that its prefix gives.
	 *
	 * @return the token and its class
	 */
	@DataProvider
	public static Object[][] tokenClasses()
	{
		return new Object[][]
		{
			{API_KEY, HostLogin.API_KEY},
			{SETUP_TOKEN, HostLogin.SUBSCRIPTION}
		};
	}

	/**
	 * Saving a token into a home without {@code .cat/claude} creates the folder readable by its owner alone and a file
	 * that its owner alone may read and write, with mode {@code 0700} and {@code 0600} on a file system with POSIX
	 * permissions, whose content reads back as the token with the class that its prefix gives.
	 *
	 * @param token      the token
	 * @param loginClass the class that the token's prefix gives
	 * @throws IOException                if the file cannot be written or read
	 * @throws CredentialRefusedException if the file refuses the token
	 */
	@Test(dataProvider = "tokenClasses")
	public void savesOwnerOnlyFile(String token, HostLogin loginClass) throws IOException,
		CredentialRefusedException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory("cat-credential-")).toRealPath();

			ClaudeCredentialFile.Saved saved = ClaudeCredentialFile.save(json, home, token, replacer);

			Path file = ClaudeCredentialFile.path(home);
			requireThat(saved.file(), "file").isEqualTo(file);
			requireThat(saved.loginClass(), "loginClass").isEqualTo(loginClass);
			requireThat(saved.replaced(), "replaced").isFalse();
			requireThat(SecretFile.problem(file, SecretFile.read(file)), "problem").isEmpty();
			if (home.getFileSystem().supportedFileAttributeViews().contains("posix"))
			{
				requireThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file.getParent(),
					NOFOLLOW_LINKS)), "folderMode").isEqualTo("rwx------");
				requireThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file, NOFOLLOW_LINKS)),
					"fileMode").isEqualTo("rw-------");
			}
			ClaudeCredentialFile read = ClaudeCredentialFile.read(json, home).orElseThrow();
			requireThat(read.token(), "token").isEqualTo(token);
			requireThat(read.loginClass(), "readClass").isEqualTo(loginClass);
			try (Stream<Path> entries = Files.list(file.getParent()))
			{
				requireThat(entries.toList(), "folder").isEqualTo(List.of(file));
			}
		}
	}

	/**
	 * Saving over an existing file replaces its token and reports the replacement.
	 *
	 * @throws IOException                if the file cannot be written or read
	 * @throws CredentialRefusedException if the file refuses a token
	 */
	@Test
	public void replacesEarlierToken() throws IOException, CredentialRefusedException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory("cat-credential-")).toRealPath();
			ClaudeCredentialFile.save(json, home, API_KEY, replacer);

			ClaudeCredentialFile.Saved saved = ClaudeCredentialFile.save(json, home, SETUP_TOKEN, replacer);

			requireThat(saved.replaced(), "replaced").isTrue();
			requireThat(saved.loginClass(), "loginClass").isEqualTo(HostLogin.SUBSCRIPTION);
			requireThat(ClaudeCredentialFile.read(json, home).orElseThrow().token(), "token").isEqualTo(SETUP_TOKEN);
		}
	}

	/**
	 * A save that cannot write its new file, here because the test makes the folder unwritable, fails with the path,
	 * leaves the earlier file byte for byte unchanged, and leaves no new file behind.
	 *
	 * @throws IOException                if the fixture cannot be written or read
	 * @throws CredentialRefusedException if the file refuses the first token
	 */
	@Test
	public void failedSaveKeepsEarlierFile() throws IOException, CredentialRefusedException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory("cat-credential-")).toRealPath();
			ClaudeCredentialFile.save(json, home, API_KEY, replacer);
			Path file = ClaudeCredentialFile.path(home);
			byte[] earlier = Files.readAllBytes(file);
			Path folder = file.getParent();
			Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("r-x------"));
			try
			{
				ClaudeCredentialFile.save(json, home, SETUP_TOKEN, replacer);
				throw new AssertionError("The save succeeded in an unwritable folder");
			}
			catch (IOException e)
			{
				requireThat(String.valueOf(e.getMessage()), "message").contains(folder.toString());
			}
			finally
			{
				Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("rwx------"));
			}

			requireThat(Files.readAllBytes(file), "file").isEqualTo(earlier);
			try (Stream<Path> entries = Files.list(folder))
			{
				requireThat(entries.toList(), "folder").isEqualTo(List.of(file));
			}
		}
	}

	/**
	 * Returns tokens that the file refuses, each with the words that the refusal must hold.
	 *
	 * @return the token and the words
	 */
	@DataProvider
	public static Object[][] refusedTokens()
	{
		List<String> bothForms = List.of("sk-ant-api", "sk-ant-oat", "claude setup-token");
		return new Object[][]
		{
			// An Anthropic admin key, which no session may bill
			{"sk-ant-admin01-fixture", bothForms},
			// Another provider's key
			{"sk-proj-fixture", bothForms},
			// No token at all
			{"", bothForms}
		};
	}

	/**
	 * A token of neither accepted form, or no token, is refused with both forms named, without the token, and without
	 * creating the folder or the file.
	 *
	 * @param token the token
	 * @param words the words that the refusal must hold
	 * @throws IOException if the fixture cannot be created
	 */
	@Test(dataProvider = "refusedTokens")
	public void refusesTokenOfOtherForm(String token, List<String> words) throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory("cat-credential-")).toRealPath();
			try
			{
				ClaudeCredentialFile.save(json, home, token, replacer);
				throw new AssertionError("The token was saved");
			}
			catch (CredentialRefusedException e)
			{
				for (String word : words)
					requireThat(e.getMessage(), "message").contains(word);
				if (!token.isEmpty())
					requireThat(e.getMessage(), "message").doesNotContain(token);
			}
			requireThat(Files.exists(home.resolve(".cat/claude"), NOFOLLOW_LINKS), "folder").isFalse();
		}
	}

	/**
	 * Returns file contents that a launch cannot use, each with the fault that the refusal names.
	 *
	 * @return the content and the fault's words
	 */
	@DataProvider
	public static Object[][] unusableContents()
	{
		// The content around the token takes 34 bytes
		String largest = "x".repeat(ClaudeCredentialFile.MAXIMUM_BYTES + 1 - 34);
		return new Object[][]
		{
			// An Anthropic admin key
			{"{\"format_version\": 1, \"token\": \"sk-ant-admin01-fixture\"}", List.of("sk-ant-api", "sk-ant-oat")},
			// Another provider's key
			{"{\"format_version\": 1, \"token\": \"sk-proj-fixture\"}", List.of("sk-ant-api", "sk-ant-oat")},
			// No token at all
			{"{\"format_version\": 1, \"token\": \"\"}", List.of("no token")},
			// A field beyond the format's
			{"{\"format_version\": 1, \"token\": \"" + API_KEY + "\", \"login_class\": \"api_key\"}",
				List.of("login_class")},
			// A later format
			{"{\"format_version\": 2, \"token\": \"" + API_KEY + "\"}", List.of("format_version")},
			// No JSON object
			{"[\"" + API_KEY + "\"]", List.of("JSON object")},
			// No JSON at all
			{API_KEY, List.of("not valid JSON")},
			// One byte more than the file may hold
			{"{\"format_version\": 1, \"token\": \"" + largest + "\"}", List.of("16384")}
		};
	}

	/**
	 * A file that a launch cannot use is refused with a reason that names the file and the fault, and never the
	 * fixture tokens.
	 *
	 * @param content the file's content
	 * @param fault   the words that the refusal must hold
	 * @throws IOException if the fixture cannot be written
	 */
	@Test(dataProvider = "unusableContents")
	public void refusesUnusableFile(String content, List<String> fault) throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory("cat-credential-")).toRealPath();
			Path file = ClaudeCredentialFile.path(home);
			Files.createDirectories(file.getParent());
			Files.writeString(file, content, UTF_8);
			Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
			try
			{
				ClaudeCredentialFile.read(json, home);
				throw new AssertionError("The file was read");
			}
			catch (CredentialRefusedException e)
			{
				requireThat(e.getMessage(), "message").contains(file.toString());
				for (String word : fault)
					requireThat(e.getMessage(), "message").contains(word);
				for (String token : List.of(API_KEY, SETUP_TOKEN, "sk-ant-admin01-fixture", "sk-proj-fixture"))
					requireThat(e.getMessage(), "message").doesNotContain(token);
			}
		}
	}

	/**
	 * A {@code .cat/claude} folder that is a symbolic link is refused, naming it, and nothing is written through it.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void refusesLinkedFolder() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-credential-")).toRealPath();
			Path home = Files.createDirectories(root.resolve("home"));
			Path elsewhere = Files.createDirectories(root.resolve("elsewhere"));
			Path folder = Files.createSymbolicLink(Files.createDirectories(home.resolve(".cat")).resolve("claude"),
				elsewhere);

			List<String> messages = refusals(home);

			for (String message : messages)
				requireThat(message, "message").contains(folder.toString());
			try (Stream<Path> entries = Files.list(elsewhere))
			{
				requireThat(entries.toList(), "elsewhere").isEmpty();
			}
		}
	}

	/**
	 * A {@code .credentials.json} that is a symbolic link is refused, naming it, and the file that it leads to keeps its
	 * content.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void refusesLinkedFile() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-credential-")).toRealPath();
			Path home = Files.createDirectories(root.resolve("home"));
			Path target = Files.writeString(root.resolve("target"), "kept");
			Path file = ClaudeCredentialFile.path(home);
			Files.createDirectories(file.getParent());
			Files.createSymbolicLink(file, target);

			List<String> messages = refusals(home);

			for (String message : messages)
				requireThat(message, "message").contains(file.toString());
			requireThat(Files.readString(target), "target").isEqualTo("kept");
			requireThat(Files.isSymbolicLink(file), "link").isTrue();
		}
	}

	/**
	 * A {@code .credentials.json} that is a folder is refused, naming it, and the folder stays.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void refusesFolderAsFile() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory("cat-credential-")).toRealPath();
			Path file = Files.createDirectories(ClaudeCredentialFile.path(home));

			List<String> messages = refusals(home);

			for (String message : messages)
				requireThat(message, "message").contains(file.toString());
			requireThat(file, "file").isDirectory();
		}
	}

	/**
	 * Saves a token into a home and removes the home's file, requiring that both are refused.
	 *
	 * @param home the home
	 * @return the save's and the removal's refusals
	 * @throws IOException if the save or the removal fails other than by a refusal
	 */
	private List<String> refusals(Path home) throws IOException
	{
		List<String> messages = new ArrayList<>();
		try
		{
			ClaudeCredentialFile.save(json, home, API_KEY, replacer);
			throw new AssertionError("The token was saved");
		}
		catch (CredentialRefusedException e)
		{
			messages.add(e.getMessage());
		}
		try
		{
			ClaudeCredentialFile.remove(home);
			throw new AssertionError("The file was removed");
		}
		catch (CredentialRefusedException e)
		{
			messages.add(e.getMessage());
		}
		return messages;
	}

	/**
	 * Removal deletes the file and the {@code .cat/claude} folder that this leaves empty, and reports the removal; a
	 * second removal finds no file.
	 *
	 * @throws IOException                if the fixture cannot be written or read
	 * @throws CredentialRefusedException if the file refuses the token or the removal
	 */
	@Test
	public void removesFileAndEmptiedFolder() throws IOException, CredentialRefusedException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory("cat-credential-")).toRealPath();
			ClaudeCredentialFile.save(json, home, SETUP_TOKEN, replacer);

			requireThat(ClaudeCredentialFile.remove(home), "removed").isTrue();

			requireThat(Files.exists(home.resolve(".cat/claude"), NOFOLLOW_LINKS), "folder").isFalse();
			requireThat(home.resolve(".cat"), "cat").isDirectory();
			requireThat(ClaudeCredentialFile.remove(home), "removedAgain").isFalse();
		}
	}

	/**
	 * Removal keeps a {@code .cat/claude} folder that holds another file.
	 *
	 * @throws IOException                if the fixture cannot be written or read
	 * @throws CredentialRefusedException if the file refuses the token or the removal
	 */
	@Test
	public void keepsFolderWithOtherFile() throws IOException, CredentialRefusedException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory("cat-credential-")).toRealPath();
			ClaudeCredentialFile.save(json, home, API_KEY, replacer);
			Path other = Files.writeString(home.resolve(".cat/claude/other"), "other");

			requireThat(ClaudeCredentialFile.remove(home), "removed").isTrue();

			requireThat(Files.exists(ClaudeCredentialFile.path(home), NOFOLLOW_LINKS), "file").isFalse();
			requireThat(other, "other").isRegularFile();
		}
	}
}
