package example.cli;

import static org.testng.Assert.assertEquals;

import org.testng.annotations.Test;

public final class ConfigLocatorTest
{
	@Test
	public void namesDefaultLocation()
	{
		assertEquals(ConfigLocator.defaultLocation(), "conf/app.toml");
	}
}
