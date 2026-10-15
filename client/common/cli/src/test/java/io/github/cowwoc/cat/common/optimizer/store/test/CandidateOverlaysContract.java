package io.github.cowwoc.cat.common.optimizer.store.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.cowwoc.cat.common.optimizer.inventory.FileContent;
import io.github.cowwoc.cat.common.optimizer.inventory.FileEntry.RegularFile;
import io.github.cowwoc.cat.common.optimizer.inventory.FileEntry.SymbolicLink;
import io.github.cowwoc.cat.common.optimizer.inventory.FileEntry;
import io.github.cowwoc.cat.common.optimizer.inventory.FileInventory;
import io.github.cowwoc.cat.common.optimizer.inventory.Overlay;
import io.github.cowwoc.cat.common.optimizer.inventory.Sha256;
import io.github.cowwoc.cat.common.optimizer.port.CandidateOverlays;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.testng.annotations.Test;

/**
 * The behaviour that every {@link CandidateOverlays} implementation must provide. Each implementation's test extends
 * this class.
 */
public abstract class CandidateOverlaysContract
{
	/**
	 * The mode of an ordinary prompt file.
	 */
	protected static final Set<PosixFilePermission> READ_WRITE = PosixFilePermissions.fromString("rw-r--r--");

	/**
	 * Creates the contract tests.
	 */
	protected CandidateOverlaysContract()
	{
	}

	/**
	 * Opens overlays over fresh, empty storage.
	 *
	 * @param fork the fork that every saved overlay is on
	 * @return the overlays and their storage
	 * @throws IOException if the storage cannot be created
	 */
	protected abstract OverlaysFixture open(FileInventory fork) throws IOException;

	/**
	 * Overlays over storage that the test owns.
	 */
	protected interface OverlaysFixture extends AutoCloseable
	{
		/**
		 * Returns the overlays.
		 *
		 * @return the overlays
		 */
		CandidateOverlays overlays();

		/**
		 * Returns new overlays over the same storage, as a restarted process would open them.
		 *
		 * @return the new overlays
		 */
		CandidateOverlays reopen();

		/**
		 * Releases the storage.
		 *
		 * @throws IOException if the storage cannot be deleted
		 */
		@Override
		void close() throws IOException;
	}

	/**
	 * Returns a fork that records an ordinary file, a file that a candidate deletes and a symbolic link.
	 *
	 * @return the fork's inventory
	 */
	protected static FileInventory fork()
	{
		return new FileInventory(Path.of("/project").toAbsolutePath(), Map.<Path, FileEntry>of(
			Path.of("prompt.md"), new RegularFile(Sha256.of(bytes("original")), READ_WRITE),
			Path.of("obsolete.md"), new RegularFile(Sha256.of(bytes("obsolete")), READ_WRITE),
			Path.of("alias.md"), new SymbolicLink(Path.of("prompt.md"))));
	}

	/**
	 * Returns a candidate with every kind of overlay entry: a content edit, a mode change, a created file, a deleted
	 * file and an edit to an external file.
	 *
	 * @param fork the fork's inventory
	 * @param variant text that distinguishes candidates
	 * @return the candidate
	 */
	protected static Overlay candidate(FileInventory fork, String variant)
	{
		return Overlay.of(fork).
			write(Path.of("prompt.md"), content("edited " + variant), READ_WRITE).
			write(Path.of("tools/run.sh"), content("#!/bin/sh\n" + variant), PosixFilePermissions.fromString(
				"rwxr-xr-x")).
			delete(Path.of("obsolete.md")).
			write(Path.of("/external/rules.md").toAbsolutePath(), new FileContent(new byte[]{0, (byte) 0xFF, 10}),
			READ_WRITE);
	}

	/**
	 * Returns UTF-8 file content.
	 *
	 * @param text the text
	 * @return the content
	 */
	protected static FileContent content(String text)
	{
		return new FileContent(bytes(text));
	}

	/**
	 * Returns the UTF-8 bytes of text.
	 *
	 * @param text the text
	 * @return the bytes
	 */
	private static byte[] bytes(String text)
	{
		return text.getBytes(UTF_8);
	}

	/**
	 * A snapshot that was never saved is not found, and requiring it fails.
	 *
	 * @throws IOException if the overlays fail
	 */
	@Test
	public void unsavedSnapshotIsNotFound() throws IOException
	{
		FileInventory fork = fork();
		try (OverlaysFixture fixture = open(fork))
		{
			Sha256 snapshot = candidate(fork, "unsaved").identity();
			requireThat(fixture.overlays().find(snapshot), "found").isEmpty();
			boolean rejected = false;
			try
			{
				fixture.overlays().require(snapshot);
			}
			catch (IOException e)
			{
				rejected = true;
				requireThat(e.getMessage(), "message").contains(snapshot.hex());
			}
			requireThat(rejected, "rejected").isTrue();
		}
	}

	/**
	 * A saved overlay, after a restart, is found under its identity, equal to the saved one and with the same
	 * identity.
	 *
	 * @throws IOException if the overlays fail
	 */
	@Test
	public void savedOverlaySurvivesRestart() throws IOException
	{
		// Requirement: R-E1, R-W1, R-W3
		FileInventory fork = fork();
		try (OverlaysFixture fixture = open(fork))
		{
			Overlay overlay = candidate(fork, "a");
			fixture.overlays().save(overlay);

			Overlay loaded = fixture.reopen().require(overlay.identity());
			requireThat(loaded, "loaded").isEqualTo(overlay);
			requireThat(loaded.identity(), "identity").isEqualTo(overlay.identity());
			requireThat(loaded.snapshot(), "snapshot").isEqualTo(overlay.snapshot());
		}
	}

	/**
	 * The incumbent, an overlay with no entries, survives a restart.
	 *
	 * @throws IOException if the overlays fail
	 */
	@Test
	public void incumbentSurvivesRestart() throws IOException
	{
		// Requirement: R-E1, R-W1
		FileInventory fork = fork();
		try (OverlaysFixture fixture = open(fork))
		{
			Overlay incumbent = Overlay.of(fork);
			fixture.overlays().save(incumbent);
			requireThat(fixture.reopen().find(incumbent.identity()), "loaded").isEqualTo(Optional.of(incumbent));
		}
	}

	/**
	 * Saving an overlay again keeps it, and other candidates are kept apart.
	 *
	 * @throws IOException if the overlays fail
	 */
	@Test
	public void savingAgainKeepsEachOverlay() throws IOException
	{
		// Requirement: R-E1
		FileInventory fork = fork();
		try (OverlaysFixture fixture = open(fork))
		{
			Overlay first = candidate(fork, "first");
			Overlay second = candidate(fork, "second");
			fixture.overlays().save(first);
			fixture.overlays().save(second);
			fixture.overlays().save(first);

			CandidateOverlays reopened = fixture.reopen();
			requireThat(reopened.find(first.identity()), "first").isEqualTo(Optional.of(first));
			requireThat(reopened.find(second.identity()), "second").isEqualTo(Optional.of(second));
		}
	}
}
