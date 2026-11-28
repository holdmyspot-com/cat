param([Parameter(Mandatory)] [string] $Target, [Parameter(Mandatory)] [string] $CodexHome)
# Prints the Windows credential's fields and its secret's SHA-256, never the secret, and the age file's hash, time and
# access list.
$ErrorActionPreference = 'Stop'
Add-Type -TypeDefinition @"
using System;
using System.Runtime.InteropServices;
public static class ProbeCredential
{
	[StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
	public struct CREDENTIAL
	{
		public int Flags;
		public int Type;
		public string TargetName;
		public string Comment;
		public long LastWritten;
		public int CredentialBlobSize;
		public IntPtr CredentialBlob;
		public int Persist;
		public int AttributeCount;
		public IntPtr Attributes;
		public string TargetAlias;
		public string UserName;
	}

	[DllImport("advapi32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
	public static extern bool CredReadW(string target, int type, int flags, out IntPtr credential);

	[DllImport("advapi32.dll")]
	public static extern void CredFree(IntPtr buffer);

	public static string Describe(string target)
	{
		IntPtr pointer;
		if (!CredReadW(target, 1, 0, out pointer))
			return "CredReadW failed with error " + Marshal.GetLastWin32Error();
		try
		{
			CREDENTIAL c = (CREDENTIAL) Marshal.PtrToStructure(pointer, typeof(CREDENTIAL));
			byte[] blob = new byte[c.CredentialBlobSize];
			Marshal.Copy(c.CredentialBlob, blob, 0, blob.Length);
			string hash;
			using (var sha = System.Security.Cryptography.SHA256.Create())
				hash = BitConverter.ToString(sha.ComputeHash(blob)).Replace("-", "");
			bool utf16 = blob.Length % 2 == 0 && blob.Length > 0;
			for (int i = 1; i < blob.Length; i += 2)
				if (blob[i] != 0) utf16 = false;
			Array.Clear(blob, 0, blob.Length);
			return "target=" + c.TargetName + " type=" + c.Type + " flags=" + c.Flags + " persist=" + c.Persist +
				" user=" + c.UserName + " attributes=" + c.AttributeCount + " comment=" + c.Comment + " alias=" +
				c.TargetAlias + " lastWritten=" + DateTime.FromFileTimeUtc(c.LastWritten).ToString("o") + " blobBytes=" +
				c.CredentialBlobSize + " blobLooksUtf16Ascii=" + utf16 + " blobSha256=" + hash;
		}
		finally
		{
			CredFree(pointer);
		}
	}
}
"@
"credential: " + [ProbeCredential]::Describe($Target)
$secrets = Join-Path $CodexHome 'secrets'
$age = Join-Path $secrets 'codex_auth.age'
if (Test-Path -LiteralPath $age)
{
	$item = Get-Item -LiteralPath $age
	"age file: sha256=" + (Get-FileHash -LiteralPath $age -Algorithm SHA256).Hash + " bytes=" + $item.Length +
		" lastWrite=" + $item.LastWriteTimeUtc.ToString('o')
	"age file acl: " + (Get-Acl -LiteralPath $age).Sddl
	"secrets folder acl: " + (Get-Acl -LiteralPath $secrets).Sddl
}
else
{
	"age file: missing"
}
"auth.json present: " + (Test-Path -LiteralPath (Join-Path $CodexHome 'auth.json'))
