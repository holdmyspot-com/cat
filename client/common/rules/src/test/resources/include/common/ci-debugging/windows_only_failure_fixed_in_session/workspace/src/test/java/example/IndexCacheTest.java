package example;

import static org.testng.Assert.assertFalse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import org.testng.annotations.Test;

public final class IndexCacheTest
{
	@Test
	public void {{test_method}}() throws IOException
	{
		Path directory = Files.createTempDirectory("cache");
		IndexCache cache = new IndexCache(directory);
		cache.put("a", 1);
		deleteTree(directory);
		cache.close();
		assertFalse(Files.exists(directory));
	}

	private static void deleteTree(Path root) throws IOException
	{
		try (Stream<Path> paths = Files.walk(root))
		{
			for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
				Files.delete(path);
		}
	}
}
