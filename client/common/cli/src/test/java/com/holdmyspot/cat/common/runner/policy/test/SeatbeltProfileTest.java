package com.holdmyspot.cat.common.runner.policy.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static org.testng.Assert.expectThrows;

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
		String profile = SeatbeltProfile.confining(Path.of("/private/var/scratch"),
			List.of(Path.of("/Users/owner/held-out"), Path.of("/Users/owner/.ssh")));

		requireThat(profile, "profile").isEqualTo("""
			(version 1)
			(allow default)
			(deny network*)
			(deny file-write*)
			(allow file-write* (literal "/dev/null") (literal "/dev/zero") (literal "/dev/dtracehelper") \
			(subpath "/private/var/scratch"))
			(deny file-read* (subpath "/Users/owner/held-out"))
			(deny file-read* (subpath "/Users/owner/.ssh"))
			""");
	}

	/**
	 * Verifies that a profile without read denials still denies the network and writes.
	 */
	@Test
	public void profileWithoutReadsKeepsOtherDenials()
	{
		// Requirement: R-L21
		String profile = SeatbeltProfile.confining(Path.of("/scratch"), List.of());

		requireThat(profile, "profile").contains("(deny network*)").contains("(deny file-write*)").
			doesNotContain("file-read*");
	}

	/**
	 * Verifies that a quote or a backslash in a path cannot end the profile's string and add a rule of its own.
	 */
	@Test
	public void profileEscapesQuotesAndBackslashes()
	{
		// Requirement: R-L21
		String profile = SeatbeltProfile.confining(Path.of("/scratch"),
			List.of(Path.of("/held \") (allow file-read* (subpath \"/"), Path.of("/back\\slash")));

		requireThat(profile, "profile").
			contains("(deny file-read* (subpath \"/held \\\") (allow file-read* (subpath \\\"\"))").
			contains("(deny file-read* (subpath \"/back\\\\slash\"))");
		long allowances = profile.lines().filter(line -> line.startsWith("(allow file-read*")).count();
		requireThat(allowances, "readAllowances").isEqualTo(0L);
	}

	/**
	 * Verifies that a path that holds a line break, which could start another rule, is refused.
	 */
	@Test
	public void profileRefusesLineBreakInPath()
	{
		// Requirement: R-L21
		expectThrows(IllegalArgumentException.class, () -> SeatbeltProfile.confining(Path.of("/scratch"),
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
		ParentPolicy owner = new ParentPolicy(true, List.of(), List.of(), List.of(Path.of("/")), List.of(),
			HostAccess.ALL, true, List.of());

		IOException failure = expectThrows(IOException.class, () ->
			ReadDenials.of(owner, List.of(), "CAT", "check program launch"));

		requireThat(failure.getMessage(), "message").contains("CAT cannot represent the owner's read restriction");
	}
}
