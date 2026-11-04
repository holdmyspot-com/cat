package com.holdmyspot.cat.distribution.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * Tests that the text this build ships calls the program that installs, updates and removes CAT "the installer".
 */
public final class NamingAuditTest
{
	/**
	 * The former name of the installer as a word. It does not match the launcher name {@code cat-updater} or the
	 * retired option {@code --updater-only}, which are spelled with a hyphen.
	 */
	private static final Pattern FORMER_NAME = Pattern.compile("(?<![\\w-])[Uu]pdater(?![\\w-])");

	/**
	 * Creates a test instance.
	 */
	public NamingAuditTest()
	{
	}

	/**
	 * Supplies each class-path folder that holds shipped prompt text.
	 *
	 * @return the folder's name
	 */
	@DataProvider
	public Object[][] shippedFolders()
	{
		return new Object[][]{{"skills"}, {"rules"}};
	}

	/**
	 * Verifies that no shipped skill or rule calls the installer "the updater".
	 *
	 * @param folder the class-path folder whose files to read
	 * @throws IOException        if a file cannot be read
	 * @throws URISyntaxException if the class path returns a malformed resource location
	 */
	@Test(dataProvider = "shippedFolders")
	public void shippedTextNamesTheInstaller(String folder) throws IOException, URISyntaxException
	{
		List<String> offenders = new ArrayList<>();
		Enumeration<URL> roots = NamingAuditTest.class.getClassLoader().getResources(folder);
		requireThat(roots.hasMoreElements(), "hasShippedFolder").isTrue();
		while (roots.hasMoreElements())
		{
			URI root = roots.nextElement().toURI();
			if (!root.getScheme().equals("jar"))
			{
				collectOffenders(Path.of(root), offenders);
				continue;
			}
			String location = root.getSchemeSpecificPart();
			Path jar = Path.of(new URI(location.substring(0, location.indexOf("!/"))));
			try (FileSystem contents = FileSystems.newFileSystem(jar))
			{
				collectOffenders(contents.getPath("/" + folder), offenders);
			}
		}
		requireThat(offenders, "offenders").isEmpty();
	}

	/**
	 * Adds each Markdown file below a folder that names the installer by its former name.
	 *
	 * @param folder    the folder to search
	 * @param offenders receives the offending files
	 * @throws IOException if a file cannot be read
	 */
	private static void collectOffenders(Path folder, List<String> offenders) throws IOException
	{
		try (Stream<Path> files = Files.walk(folder))
		{
			for (Path file : files.filter(path -> path.toString().endsWith(".md")).toList())
			{
				if (FORMER_NAME.matcher(Files.readString(file)).find())
					offenders.add(file.toUri().toString());
			}
		}
	}
}
