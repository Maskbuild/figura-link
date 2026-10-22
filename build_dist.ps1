$ErrorActionPreference = "Stop"

$distDir = Join-Path $PSScriptRoot "dist"
if (Test-Path $distDir) { Remove-Item -Recurse -Force $distDir }
New-Item -ItemType Directory -Force $distDir | Out-Null

$classesDir = Join-Path $PSScriptRoot "build_temp\classes"
$staging = Join-Path $PSScriptRoot "build_temp\staging"

function New-ModJar {
    param (
        [string]$jarName,
        [string]$loader,      # 'fabric', 'forge', or 'universal'
        [string]$mcVersion    # '1.20.1' or '1.21.1'
    )

    if (Test-Path $staging) { Remove-Item -Recurse -Force $staging }
    New-Item -ItemType Directory -Force $staging | Out-Null

    # Copy all compiled classes
    Copy-Item -Recurse "$classesDir\*" "$staging\"

    # Copy common mixins json
    Copy-Item "common\src\main\resources\figuralink.mixins.json" "$staging\"

    # Include Fabric metadata if fabric or universal
    if ($loader -eq 'fabric' -or $loader -eq 'universal') {
        Copy-Item "fabric\src\main\resources\fabric.mod.json" "$staging\"
    }

    # Include Forge metadata if forge or universal
    if ($loader -eq 'forge' -or $loader -eq 'universal') {
        New-Item -ItemType Directory -Force "$staging\META-INF" | Out-Null
        Copy-Item "forge\src\main\resources\META-INF\mods.toml" "$staging\META-INF\"
        
        # Manifest for Forge mixin loading
        $manifestContent = @"
Manifest-Version: 1.0
Implementation-Title: Figura Limit Unlocked
Implementation-Version: 1.0.0
Specification-Title: figuralink
Specification-Version: 1.0.0
MixinConfigs: figuralink.mixins.json
"@
        Set-Content -Path "$staging\META-INF\MANIFEST.MF" -Value $manifestContent
    }

    $outJar = Join-Path $distDir $jarName
    jar -cf "$outJar" -C "$staging" .
    Write-Host "Built: $jarName"
}

# Build loaders and versions
New-ModJar "figuralink-fabric-1.20.1-1.0.0.jar" "fabric" "1.20.1"
New-ModJar "figuralink-fabric-1.21.1-1.0.0.jar" "fabric" "1.21.1"
New-ModJar "figuralink-forge-1.20.1-1.0.0.jar" "forge" "1.20.1"
New-ModJar "figuralink-forge-1.21.1-1.0.0.jar" "forge" "1.21.1"
New-ModJar "figuralink-universal-1.0.0.jar" "universal" "all"

Write-Host "`nAll mod jars successfully generated in dist\"
