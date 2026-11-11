package com.holdmyspot.cat.common.test;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;
import org.testng.annotations.DataProvider;

/**
 * Paths that start at a root without being absolute on some platform, for tests of inputs that must be relative.
 * <p>
 * On Windows, {@code /etc/hosts} has a root but no drive, so {@link Path#isAbsolute()} is {@code false} for it.
 */
public final class RootedPaths
{
	/**
	 * Prevents instantiation.
	 */
	private RootedPaths()
	{
	}

	/**
	 * Returns the texts of paths that start at a root and name no project-relative file on any platform:
	 * {@code /etc/hosts} and, where {@code \} separates names, {@code \etc\hosts}.
	 *
	 * @return the texts
	 */
	public static List<String> texts()
	{
		List<String> texts = new ArrayList<>();
		texts.add("/etc/hosts");
		if (File.separatorChar == '\\')
			texts.add("\\etc\\hosts");
		return texts;
	}

	/**
	 * Returns {@link #texts()} as test data.
	 *
	 * @return one row per text
	 */
	@DataProvider(name = "rootedTexts")
	public static Object[][] rootedTexts()
	{
		return texts().stream().map(text -> new Object[]{text}).toArray(Object[][]::new);
	}

	/**
	 * Returns the text of the path that names a file of the same drive through its root alone.
	 * <p>
	 * On Windows, {@code C:\work\rules\a.md} becomes {@code /work/rules/a.md}, a rooted path that is not absolute. On
	 * other platforms, the text is the file's absolute path.
	 *
	 * @param file an absolute path
	 * @return the text of the path from the root of {@code file}
	 */
	public static String withoutDrive(Path file)
	{
		StringJoiner joiner = new StringJoiner("/", "/", "");
		for (Path name : file.getRoot().relativize(file))
			joiner.add(name.toString());
		return joiner.toString();
	}
}
