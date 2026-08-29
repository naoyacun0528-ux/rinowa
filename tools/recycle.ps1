<#
    消すのではなく、ごみ箱へ送る。

    2026-08-29、頼まれていない削除で outputs の成果物を2つ失った。rm も
    Remove-Item もごみ箱を通らないので、消した瞬間に終わりだった。
    docs/DO_NOT_DELETE.md。

    規則そのもの（頼まれない限り消さない）は変わらない。これは**その規則を
    破ったときの受け皿**で、規則の代わりではない。

    使い方:

        powershell -File tools/recycle.ps1 <path> [<path> ...]

    ## なぜ SHFileOperation なのか

    最初 Microsoft.VisualBasic.FileIO.FileSystem を使ったが、この機械
    （Windows on ARM64）では "This function is not supported on this system"
    で動かなかった。SHFileOperation はエクスプローラの削除そのものが呼んで
    いる API で、FOF_ALLOWUNDO を渡すとごみ箱へ行く。

    ## C# の中は英語だけ

    Add-Type に渡す文字列に日本語を入れたら、PowerShell 5.1 の既定の
    符号化で化けて、コンパイルが通らなかった。**説明はこちら側に書く。**

    ごみ箱に入らない場合（ネットワーク上、リムーバブル、大きすぎる、ごみ箱を
    無効にしている）は、消さずに諦めて報告する。黙って完全削除に切り替えたら、
    この仕組みを作った意味が無い。
#>

param([Parameter(ValueFromRemainingArguments = $true)][string[]] $Paths)

if (-not $Paths -or $Paths.Count -eq 0) {
    Write-Host "使い方: powershell -File tools/recycle.ps1 <path> [<path> ...]"
    exit 1
}

$signature = @'
using System;
using System.Runtime.InteropServices;

public static class Recycler {
    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct SHFILEOPSTRUCT {
        public IntPtr hwnd;
        public uint wFunc;
        [MarshalAs(UnmanagedType.LPWStr)] public string pFrom;
        [MarshalAs(UnmanagedType.LPWStr)] public string pTo;
        public ushort fFlags;
        [MarshalAs(UnmanagedType.Bool)] public bool fAnyOperationsAborted;
        public IntPtr hNameMappings;
        [MarshalAs(UnmanagedType.LPWStr)] public string lpszProgressTitle;
    }

    [DllImport("shell32.dll", CharSet = CharSet.Unicode)]
    private static extern int SHFileOperation(ref SHFILEOPSTRUCT op);

    private const uint FO_DELETE = 0x0003;
    private const ushort FOF_SILENT = 0x0004;
    private const ushort FOF_NOCONFIRMATION = 0x0010;
    private const ushort FOF_ALLOWUNDO = 0x0040;
    private const ushort FOF_NOERRORUI = 0x0400;

    // Returns the raw SHFileOperation code. Zero means it worked.
    // pFrom must be double-null terminated; a single terminator makes the
    // call read past the end of the string and take neighbouring paths with it.
    public static int ToRecycleBin(string path) {
        SHFILEOPSTRUCT op = new SHFILEOPSTRUCT();
        op.wFunc = FO_DELETE;
        op.pFrom = path + "\0\0";
        op.fFlags = (ushort)(FOF_ALLOWUNDO | FOF_NOCONFIRMATION | FOF_SILENT | FOF_NOERRORUI);
        return SHFileOperation(ref op);
    }
}
'@

if (-not ([System.Management.Automation.PSTypeName]'Recycler').Type) {
    Add-Type -TypeDefinition $signature -Language CSharp
}

$failed = 0
foreach ($p in $Paths) {
    if (-not (Test-Path -LiteralPath $p)) {
        Write-Host "  無い: $p"
        continue
    }

    $full = (Resolve-Path -LiteralPath $p).Path
    $code = [Recycler]::ToRecycleBin($full)

    if ($code -eq 0 -and -not (Test-Path -LiteralPath $full)) {
        Write-Host "  ごみ箱へ: $full"
    } else {
        Write-Host "  ごみ箱へ送れなかった（消していません）: $full"
        Write-Host "    SHFileOperation code=$code"
        $failed++
    }
}

if ($failed -gt 0) { exit 1 }
