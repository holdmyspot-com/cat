package io.github.cowwoc.cat.common;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import org.testng.annotations.Test;

/**
 * Tests {@link BoundedInput}.
 */
public final class BoundedInputTest
{
	/**
	 * Creates the test fixture.
	 */
	public BoundedInputTest()
	{
	}

	/**
	 * Verifies a whole input at the supported limit is returned.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void readsInputAtLimit() throws IOException
	{
		String content = BoundedInput.readUtf8(new ByteArrayInputStream("test".getBytes(UTF_8)), "fixture", 4,
			"fixture input");

		requireThat(content, "content").isEqualTo("test");
	}

	/**
	 * Verifies a whole input above the supported limit is rejected.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test(expectedExceptions = IOException.class)
	public void rejectsInputAboveLimit() throws IOException
	{
		BoundedInput.readBytes(new ByteArrayInputStream(new byte[5]), "fixture", 4, "fixture input");
	}
}
