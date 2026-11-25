package com.holdmyspot.cat.common.runner.policy.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static org.testng.Assert.expectThrows;

import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.runner.policy.HostAccess;
import com.holdmyspot.cat.common.runner.policy.ParentPolicy;
import com.holdmyspot.cat.common.runner.policy.ReadDenials;
import com.holdmyspot.cat.common.runner.policy.ReadDenials.Denial;
import com.holdmyspot.cat.common.runner.policy.SeatbeltProfile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.testng.SkipException;
import org.testng.annotations.Test;

/**
 * Tests the text of the Seatbelt profile that confines a check program and the read denials that it shares with the
 * Bubblewrap view. The text is built on every operating system, so these tests run wherever the build runs.
 */
public final class SeatbeltProfileTest
{
	/**
	 * Creates the test.
	 */
	public SeatbeltProfileTest()
	{
	}

	/**
	 * Verifies that the profile denies the network and every write, allows writing only the scratch directory and a few
	 * devices after that denial, and then denies each read, in this order because the last matching rule decides.
	 */
	@Test
	public void profileDeniesNetworkWritesAndReads()
	{
		// Requirement: R-L21
		Path scratch = absolute("/private/var/scratch");
		Path heldOut = absolute("/Users/owner/held-out");
		Path ssh = absolute("/Users/owner/.ssh");
		String profile = SeatbeltProfile.confining(scratch, List.of(heldOut, ssh));

		requireThat(profile, "profile").isEqualTo("""
			(version 1)
			(allow default)
			(deny network*)
			(deny file-write*)
			(allow file-write* (literal "/dev/null") (literal "/dev/zero") (literal "/dev/dtracehelper") \
			(subpath "%s"))
			(deny file-read* (subpath "%s"))
			(deny file-read* (subpath "%s"))
			""".formatted(escaped(scratch), escaped(heldOut), escaped(ssh)));
	}

	/**
	 * Verifies that a profile without read denials still denies the network and writes.
	 */
	@Test
	public void profileWithoutReadsKeepsOtherDenials()
	{
		// Requirement: R-L21
		String profile = SeatbeltProfile.confining(absolute("/scratch"), List.of());

		requireThat(profile, "profile").contains("(deny network*)").contains("(deny file-write*)").
			doesNotContain("file-read*");
	}

	/**
	 * Verifies that a quote in a path cannot end the profile's string and add a rule of its own.
	 *
	 * @throws SkipException if the platform does not allow a quote in a path
	 */
	@Test
	public void profileEscapesQuotes()
	{
		// Requirement: R-L21
		if (Platform.current().isWindows())
			throw new SkipException("A Windows path cannot hold a quote, so there is no such path to quote");
		String profile = SeatbeltProfile.confining(Path.of("/scratch"),
			List.of(Path.of("/held \") (allow file-read* (subpath \"/")));

		requireThat(profile, "profile").
			contains("(deny file-read* (subpath \"/held \\\") (allow file-read* (subpath \\\"\"))");
		long allowances = profile.lines().filter(line -> line.startsWith("(allow file-read*")).count();
		requireThat(allowances, "readAllowances").isEqualTo(0L);
	}

	/**
	 * Verifies that a backslash in a path cannot escape the profile's closing quote.
	 */
	@Test
	public void profileEscapesBackslashes()
	{
		// Requirement: R-L21
		Path denied = absolute("/back\\slash");
		String profile = SeatbeltProfile.confining(absolute("/scratch"), List.of(denied));

		requireThat(profile, "profile").contains("(deny file-read* (subpath \"" + escaped(denied) + "\"))");
		requireThat(escaped(denied), "escaped").doesNotContain("\\\"");
	}

	/**
	 * Verifies that a path that holds a line break, which could start another rule, is refused.
	 */
	@Test
	public void profileRefusesLineBreakInPath()
	{
		// Requirement: R-L21
		expectThrows(IllegalArgumentException.class, () -> SeatbeltProfile.confining(absolute("/scratch"),
			List.of(Path.of("/held\n(allow default)"))));
	}

	/**
	 * Verifies that a relative path is refused, because Seatbelt matches absolute paths.
	 */
	@Test
	public void profileRefusesRelativeScratch()
	{
		// Requirement: R-L21
		expectThrows(IllegalArgumentException.class, () -> SeatbeltProfile.confining(Path.of("scratch"), List.of()));
	}

	/**
	 * Verifies that the denials that the profile uses are the ones that the Bubblewrap view masks: the owner's denied
	 * reads and the required masks, a path inside another denial or inside a re-allowance of the owner left out, and a
	 * required mask kept even when the owner re-allows it.
	 *
	 * @throws IOException if a fixture cannot be created
	 */
	@Test
	public void readDenialsAreWhatTheBubblewrapViewMasks() throws IOException
	{
		// Requirement: R-L21
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-read-denials-")).toRealPath();
			Path outer = Files.createDirectory(root.resolve("outer"));
			Path inner = Files.createDirectory(outer.resolve("inner"));
			Path reallowed = Files.createDirectory(root.resolve("denied-but-reallowed"));
			Path heldOut = Files.createDirectory(root.resolve("held-out"));
			Path single = Files.writeString(root.resolve("single.txt"), "x");
			ParentPolicy owner = new ParentPolicy(true, List.of(root), List.of(), List.of(outer, inner, reallowed,
				heldOut, single), List.of(reallowed, heldOut), HostAccess.ALL, true, List.of());

			List<Denial> denials = ReadDenials.of(owner, List.of(heldOut), "CAT", "check program launch");

			requireThat(denials.stream().map(Denial::path).toList(), "paths").
				isEqualTo(List.of(heldOut, outer, single));
			requireThat(denials.stream().filter(Denial::mandatory).map(Denial::path).toList(), "mandatory").
				isEqualTo(List.of(heldOut));
		}
	}

	/**
	 * Verifies that an unsandboxed owner with no required mask has nothing to deny.
	 *
	 * @throws IOException if a path cannot be resolved
	 */
	@Test
	public void unsandboxedOwnerDeniesNothing() throws IOException
	{
		// Requirement: R-L21
		requireThat(ReadDenials.of(ParentPolicy.UNSANDBOXED, List.of(), "CAT", "check program launch"), "denials").
			isEmpty();
	}

	/**
	 * Verifies that a denial of the file system root, which no view can represent, is refused and named.
	 */
	@Test
	public void refusesDenialOfFileSystemRoot()
	{
		// Requirement: R-L21
		Path root = absolute("/");
		ParentPolicy owner = new ParentPolicy(true, List.of(), List.of(), List.of(root), List.of(),
			HostAccess.ALL, true, List.of());

		IOException failure = expectThrows(IOException.class, () ->
			ReadDenials.of(owner, List.of(), "CAT", "check program launch"));

		requireThat(failure.getMessage(), "message").contains("CAT cannot represent the owner's read restriction");
	}

	/**
	 * Returns an absolute path for a path written with Unix separators. On Windows, the path is rooted at the current
	 * drive, because a path that starts with a separator alone is not absolute there.
	 *
	 * @param unixPath the path
	 * @return the absolute path
	 */
	private static Path absolute(String unixPath)
	{
		return Path.of(unixPath).toAbsolutePath();
	}

	/**
	 * Returns a path's text as the profile writes it inside a string, with each backslash doubled.
	 *
	 * @param path the path
	 * @return the escaped text
	 */
	private static String escaped(Path path)
	{
		return path.toString().replace("\\", "\\\\");
	}
}
