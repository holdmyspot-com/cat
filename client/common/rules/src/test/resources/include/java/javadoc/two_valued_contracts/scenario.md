# Two-valued contracts

## Design Goals

- Prove that the Javadoc of a boolean field and getter, and of a field and getter whose enum type has two constants,
  begins with “Indicates whether”, while the Javadoc of a getter with many possible values does not.

## Variables

- **flag_getter**: The name of the method that returns the `sent` field, which is `true` once the upload's last byte
  has been sent, such as `isSent` or `wasSent`.

## Scenario

Add Javadoc to every field and method of this class, which has none yet. Show the whole class.

```java
public final class Upload
{
	private final String fileName;
	private final Channel channel;
	private boolean sent;

	public Upload(String fileName, Channel channel)
	{
		this.fileName = fileName;
		this.channel = channel;
	}

	public String getFileName()
	{
		return fileName;
	}

	public Channel getChannel()
	{
		return channel;
	}

	public boolean {{flag_getter}}()
	{
		return sent;
	}

	public void markSent()
	{
		sent = true;
	}
}
```

`Channel` is declared elsewhere in the project as `enum Channel { ENCRYPTED, PLAIN }`: `ENCRYPTED` sends the upload's
bytes over an encrypted connection and `PLAIN` sends them unencrypted.

## Assertions

### boolean_indicates_whether

- **kind**: effect
- **description**: The summary description of the `sent` field and of `{{flag_getter}}()` each begins with “Indicates
  whether” and names the condition that makes the value `true`; neither begins with “Returns whether”.

### two_valued_indicates_whether

- **kind**: effect
- **description**: The summary description of the `channel` field and of `getChannel()` each begins with “Indicates
  whether”, not “Returns whether”, and names the condition that selects each of `ENCRYPTED` and `PLAIN`.

### many_valued_getter_unchanged

- **kind**: guard
- **description**: The summary description of `getFileName()` does not begin with “Indicates whether”.
