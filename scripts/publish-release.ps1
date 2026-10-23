# Publish the GitHub release for a tag: create it, then upload the APK asset.
# Token comes from Windows Credential Manager (target: git:https://github.com) unless $env:GH_TOKEN is set.
param(
    [Parameter(Mandatory = $true)][string]$Tag,
    [Parameter(Mandatory = $true)][string]$ApkPath,
    [Parameter(Mandatory = $true)][string]$NotesPath,
    [string]$Repo = "hundunfirst/LlamaServer"
)

$ErrorActionPreference = "Stop"

function Get-StoredToken {
    if ($env:GH_TOKEN) { return $env:GH_TOKEN }
    $sig = @'
using System;
using System.Runtime.InteropServices;
public class NativeCred {
    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    public struct CREDENTIAL {
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
    [DllImport("advapi32.dll", EntryPoint = "CredReadW", CharSet = CharSet.Unicode, SetLastError = true)]
    public static extern bool CredRead(string target, int type, int reservedFlag, out IntPtr credentialPtr);
    [DllImport("advapi32.dll", SetLastError = true)]
    public static extern void CredFree(IntPtr cred);
}
'@
    if (-not ("NativeCred" -as [type])) { Add-Type -TypeDefinition $sig }
    foreach ($target in @("git:https://github.com", "https://github.com")) {
        $ptr = [IntPtr]::Zero
        if ([NativeCred]::CredRead($target, 1, 0, [ref]$ptr)) {
            try {
                $cred = [System.Runtime.InteropServices.Marshal]::PtrToStructure(
                    $ptr, [type][NativeCred+CREDENTIAL])
                if ($cred.CredentialBlobSize -gt 0) {
                    return [System.Runtime.InteropServices.Marshal]::PtrToStringUni(
                        $cred.CredentialBlob, $cred.CredentialBlobSize / 2)
                }
            } finally {
                [NativeCred]::CredFree($ptr) | Out-Null
            }
        }
    }
    return $null
}

$token = Get-StoredToken
if (-not $token) { throw "no token: set GH_TOKEN or store a git:https://github.com credential" }

$headers = @{
    Authorization          = "Bearer $token"
    Accept                 = "application/vnd.github+json"
    "X-GitHub-Api-Version" = "2022-11-28"
    "User-Agent"           = "LlamaServer-release-script"
}

$notes = [System.IO.File]::ReadAllText((Resolve-Path $NotesPath), [System.Text.Encoding]::UTF8)
$payload = @{
    tag_name   = $Tag
    name       = $Tag
    body       = $notes
    draft      = $false
    prerelease = $false
} | ConvertTo-Json -Depth 3
$payloadBytes = [System.Text.Encoding]::UTF8.GetBytes($payload)

Write-Host "creating release $Tag ..."
$release = Invoke-RestMethod -Method Post -Uri "https://api.github.com/repos/$Repo/releases" `
    -Headers $headers -Body $payloadBytes -ContentType "application/json; charset=utf-8"
Write-Host ("release created: id={0} url={1}" -f $release.id, $release.html_url)

$apk = (Resolve-Path $ApkPath).Path
$name = [System.IO.Path]::GetFileName($apk)
$bytes = [System.IO.File]::ReadAllBytes($apk)
Write-Host ("uploading {0} ({1:N1} MB) ..." -f $name, ($bytes.Length / 1MB))
$asset = Invoke-RestMethod -Method Post `
    -Uri ("https://uploads.github.com/repos/$Repo/releases/{0}/assets?name={1}" -f $release.id, $name) `
    -Headers $headers -Body $bytes -ContentType "application/vnd.android.package-archive" `
    -TimeoutSec 1800
Write-Host ("asset uploaded: {0} ({1:N1} MB) {2}" -f $asset.name, ($asset.size / 1MB), $asset.browser_download_url)
