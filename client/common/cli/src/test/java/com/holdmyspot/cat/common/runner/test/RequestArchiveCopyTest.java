package com.holdmyspot.cat.common.runner.test;

import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.fakes.test.TestJvmScope;
import com.holdmyspot.cat.common.runner.RequestArchive;
import com.holdmyspot.cat.common.runner.RequestArchiveBusyException;
import com.holdmyspot.cat.common.runner.RequestLimits;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.testng.annotations.Test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Tests the receiver's copy of a published request file.
 */
public final class RequestArchiveCopyTest
{
	/**
	 * Verifies that, on Windows, where a published file is opened exclusively so that no writer can change it while it
	 * is read, a file that another handle holds open is reported as busy instead of as a link, and is copied once the
	 * handle is closed. On other platforms, the open handle does not prevent the copy.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void reportsOpenFileAsBusy() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
		     TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "copy-")).toRealPath();
			Path published = Files.writeString(root.resolve("record.json"), "{}");
			Path copy = root.resolve("copy.json");
			try (FileChannel _ = FileChannel.open(published, StandardOpenOption.READ, StandardOpenOption.WRITE))
			{
				if (Platform.current().isWindows())
				{
					try
					{
						RequestArchive.copy(published, copy, RequestLimits.UNBOUNDED);
						throw new AssertionError("The copy opened a file that another handle holds open");
					}
					catch (RequestArchiveBusyException expected)
					{
						requireThat(expected.getMessage(), "message").contains(published.toString());
					}
				}
				else
				{
					RequestArchive.copy(published, copy, RequestLimits.UNBOUNDED);
					requireThat(Files.readString(copy), "copiedWhileOpen").isEqualTo("{}");
				}
			}
			RequestArchive.copy(published, copy, RequestLimits.UNBOUNDED);
			requireThat(Files.readString(copy), "copied").isEqualTo("{}");
		}
	}
}
