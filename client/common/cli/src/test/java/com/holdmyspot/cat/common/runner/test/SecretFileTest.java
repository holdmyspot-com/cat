package com.holdmyspot.cat.common.runner.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.runner.SecretFile;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.testng.SkipException;
import org.testng.annotations.Test;

/**
 * Tests {@link SecretFile}.
 */
public final class SecretFileTest
{
	/**
	 * A file that the decision names in its reason.
	 */
	private static final Path FILE = Path.of("/home/alice/.claude/.credentials.json");
	/**
	 * The owner of a Windows file in these tests.
	 */
	private static final String OWNER = "DESKTOP\\alice";

	/**
	 * Creates the test.
	 */
	public SecretFileTest()
	{
	}

	/**
	 * Verifies that a regular file whose mode grants its owner access and grants group and other nothing holds a secret
	 * safely, whether the owner may write it or only read it.
	 */
	@Test
	public void acceptsOwnerOnlyModes()
	{
		// Requirement: R-I14
		for (String mode : List.of("rw-------", "r--------"))
		{
			Optional<String> problem = SecretFile.problem(FILE, posix(mode));
			requireThat(problem, mode).isEmpty();
		}
	}

	/**
	 * Verifies that a mode that grants group or other any access is refused, and that the reason names the file and
	 * each permission bit that must be cleared.
	 */
	@Test
	public void refusesGroupAndOtherBits()
	{
		// Requirement: R-I14
		Map<String, String> bits = Map.of("rw-r-----", "group read", "rw--w----", "group write",
			"rw---x---", "group execute", "rw----r--", "other read", "rw-----w-", "other write", "rw------x",
			"other execute");
		for (Map.Entry<String, String> entry : bits.entrySet())
		{
			Optional<String> problem = SecretFile.problem(FILE, posix(entry.getKey()));
			requireThat(problem, entry.getKey()).isPresent();
			requireThat(problem.get(), entry.getKey()).contains(FILE.toString()).contains(entry.getValue());
		}
		Optional<String> both = SecretFile.problem(FILE, posix("rw-r--r--"));
		requireThat(both, "both").isPresent();
		requireThat(both.get(), "both").contains("group read").contains("other read");
	}

	/**
	 * Verifies that a Windows access list that grants access to its owner, {@code SYSTEM} and the Administrators group
	 * alone holds a secret safely, and that one that also grants it to {@code Users} or {@code Everyone} is refused,
	 * naming the file and the group.
	 */
	@Test
	public void decidesFromWindowsAccessList()
	{
		// Requirement: R-I14
		List<String> ownerOnly = List.of(OWNER, "NT AUTHORITY\\SYSTEM", "BUILTIN\\Administrators");
		requireThat(SecretFile.problem(FILE, acl(ownerOnly)), "ownerOnly").isEmpty();

		for (String group : List.of("BUILTIN\\Users", "Everyone"))
		{
			List<String> wider = List.of(OWNER, "NT AUTHORITY\\SYSTEM", "BUILTIN\\Administrators", group);
			Optional<String> problem = SecretFile.problem(FILE, acl(wider));
			requireThat(problem, group).isPresent();
			requireThat(problem.get(), group).contains(FILE.toString()).contains(group);
		}
	}

	/**
	 * Verifies that a file whose mode lets its owner only write it, which the test's process therefore cannot read, is
	 * accepted from its metadata alone: the check never opens the file.
	 *
	 * @throws IOException if the test's file cannot be created
	 */
	@Test
	public void acceptsUnreadableFileWithoutOpeningIt() throws IOException
	{
		// Requirement: R-I14
		requirePosix();
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("secret-file-"));
			Path key = Files.writeString(directory.resolve("key"), "sk-ant-api03-fixture");
			Files.setPosixFilePermissions(key, PosixFilePermissions.fromString("-w-------"));
			try
			{
				Optional<String> problem = SecretFile.problem(key, SecretFile.read(key));
				requireThat(problem, "problem").isEmpty();
			}
			finally
			{
				Files.setPosixFilePermissions(key, PosixFilePermissions.fromString("rw-------"));
			}
		}
	}

	/**
	 * Verifies that a symbolic link to a file that holds a secret safely is refused, because the link's target can be
	 * replaced by whoever may write the link's folder, and that the reason names the link.
	 *
	 * @throws IOException if the test's files cannot be created
	 */
	@Test
	public void refusesSymbolicLink() throws IOException
	{
		// Requirement: R-I14
		requirePosix();
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("secret-file-"));
			Path key = Files.writeString(directory.resolve("key"), "sk-ant-api03-fixture");
			Files.setPosixFilePermissions(key, PosixFilePermissions.fromString("rw-------"));
			Path link = Files.createSymbolicLink(directory.resolve("link"), key);

			Optional<String> problem = SecretFile.problem(link, SecretFile.read(link));

			requireThat(problem, "problem").isPresent();
			requireThat(problem.get(), "problem").contains(link.toString()).contains("symbolic link");
		}
	}

	/**
	 * Returns the metadata of a regular file with POSIX permissions.
	 *
	 * @param mode the permissions, as {@code ls} writes them without the file type
	 * @return the metadata
	 */
	private static SecretFile.Attributes posix(String mode)
	{
		Set<PosixFilePermission> permissions = PosixFilePermissions.fromString(mode);
		return new SecretFile.Attributes(false, true, permissions, null, null);
	}

	/**
	 * Returns the metadata of a regular file on a file system with access lists.
	 *
	 * @param grantees the users and groups to which the access list allows any access
	 * @return the metadata
	 */
	private static SecretFile.Attributes acl(List<String> grantees)
	{
		return new SecretFile.Attributes(false, true, null, OWNER, grantees);
	}

	/**
	 * Skips a test that needs POSIX permissions on a file system without them.
	 */
	private static void requirePosix()
	{
		if (!FileSystems.getDefault().supportedFileAttributeViews().contains("posix"))
			throw new SkipException("The file system has no POSIX permissions");
	}
}
