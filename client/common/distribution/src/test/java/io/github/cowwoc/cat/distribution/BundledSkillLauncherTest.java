package io.github.cowwoc.cat.distribution;

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
import java.util.stream.Stream;
import org.testng.annotations.Test;

/**
 * Tests how the bundled skills name the launchers they run.
 */
public final class BundledSkillLauncherTest
{
	/**
	 * Creates a test instance.
	 */
	public BundledSkillLauncherTest()
	{
	}

	/**
	 * Verifies that no bundled skill names a launcher through {@code ${CAT_PLUGIN_ROOT}}. Neither harness defines that
	 * variable in its tool shells, and an agent that copies the notation into a command runs a path that does not
	 * exist.
	 *
	 * @throws IOException if a skill cannot be read
	 * @throws URISyntaxException if the class path returns a malformed resource location
	 */
	@Test
	public void namesLaunchersWithoutPluginRootVariable() throws IOException, URISyntaxException
	{
		List<String> offenders = new ArrayList<>();
		Enumeration<URL> roots = BundledSkillLauncherTest.class.getClassLoader().getResources("skills");
		requireThat(roots.hasMoreElements(), "hasBundledSkills").isTrue();
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
				collectOffenders(contents.getPath("/skills"), offenders);
			}
		}
		requireThat(offenders, "offenders").isEmpty();
	}

	/**
	 * Adds each Markdown file below a skill tree that writes {@code ${CAT_PLUGIN_ROOT}}.
	 *
	 * @param skills the skill tree
	 * @param offenders receives the offending files
	 * @throws IOException if a file cannot be read
	 */
	private static void collectOffenders(Path skills, List<String> offenders) throws IOException
	{
		try (Stream<Path> files = Files.walk(skills))
		{
			for (Path file : files.filter(path -> path.toString().endsWith(".md")).toList())
			{
				if (Files.readString(file).contains("${CAT_PLUGIN_ROOT}"))
					offenders.add(file.toUri().toString());
			}
		}
	}
}
