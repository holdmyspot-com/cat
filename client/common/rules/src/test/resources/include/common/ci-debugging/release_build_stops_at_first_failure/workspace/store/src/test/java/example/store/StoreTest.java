package example.store;

import static org.testng.Assert.assertEquals;

import example.core.Entry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.testng.annotations.Test;

public final class StoreTest
{
	@Test
	public void {{test_method}}() throws IOException
	{
		Path directory = Files.createTempDirectory("store");
		try (SnapshotStore store = new SnapshotStore(directory.resolve("snapshot.db")))
		{
			store.append(new Entry("a", 1));
			store.clear();
			assertEquals(store.size(), 0);
		}
	}
}
