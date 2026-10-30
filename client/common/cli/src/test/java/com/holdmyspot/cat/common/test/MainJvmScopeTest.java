package com.holdmyspot.cat.common.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.MainJvmScope;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.fakes.test.TestJvmScope;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.testng.annotations.Test;
import com.holdmyspot.cat.common.fakes.test.Yielding;

/**
 * Tests {@link MainJvmScope}'s process temporary directory.
 */
public final class MainJvmScopeTest
{
	/**
	 * Verifies TMPDIR takes priority and its normalized directory is created.
	 */
	@Test
	public void prefersTmpdirAndCreatesIt() throws IOException
	{
		try (TestJvmScope test = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(test.temporaryDirectory(), "jvm-"));
			Path target = root.resolve("created");
			try (MainJvmScope scope = new MainJvmScope(Map.of("TMPDIR", target.resolve("../created").toString(),
				"TEMP", root.resolve("other").toString()), Yielding.waiting()))
			{
				requireThat(scope.temporaryDirectory(), "temporaryDirectory").isEqualTo(target);
				requireThat(target, "target").isDirectory();
			}
		}
	}

	/**
	 * Verifies an empty TMPDIR falls back to TEMP and creates that directory.
	 */
	@Test
	public void fallsBackToTempAndCreatesIt() throws IOException
	{
		try (TestJvmScope test = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(test.temporaryDirectory(), "jvm-"));
			Path target = root.resolve("created");
			try (MainJvmScope scope = new MainJvmScope(Map.of("TMPDIR", "", "TEMP", target.toString()), Yielding.waiting()))
			{
				requireThat(scope.temporaryDirectory(), "temporaryDirectory").isEqualTo(target);
				requireThat(target, "target").isDirectory();
			}
		}
	}

	/**
	 * Verifies empty environment choices use the JVM temporary directory.
	 */
	@Test
	public void fallsBackToJvmDirectory() throws IOException
	{
		try (MainJvmScope scope = new MainJvmScope(Map.of("TMPDIR", "", "TEMP", ""), Yielding.waiting()))
		{
			requireThat(scope.temporaryDirectory(), "temporaryDirectory").
				isEqualTo(Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize());
		}
	}
}
