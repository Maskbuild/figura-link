$ErrorActionPreference = "Stop"

$distDir = Join-Path $PSScriptRoot "dist"
if (Test-Path $distDir) { Remove-Item -Recurse -Force $distDir }
New-Item -ItemType Directory -Force $distDir | Out-Null

$classesDir = Join-Path $PSScriptRoot "build_temp\classes"
$staging = Join-Path $PSScriptRoot "build_temp\staging"

if (Test-Path "$PSScriptRoot\build_temp") { Remove-Item -Recurse -Force "$PSScriptRoot\build_temp" }
New-Item -ItemType Directory -Force $classesDir | Out-Null

# Classpaths for compiling
$mixin = "C:\Users\catiswin\.gradle\caches\modules-2\files-2.1\dev.architectury\mixin-patched\0.8.5.12\cf1f787f58d3b08b0cb53ecbba7928c09a1893c8\mixin-patched-0.8.5.12.jar"
$slf4j = "C:\Users\catiswin\.gradle\caches\forge_gradle\maven_downloader\org\slf4j\slf4j-api\2.0.1\slf4j-api-2.0.1.jar"
$fabric = "C:\Users\catiswin\.gradle\caches\modules-2\files-2.1\net.fabricmc\fabric-loader\0.15.11\9c2be53d84ec5c4fb58bb5b8d9c8a629047c9ee9\fabric-loader-0.15.11.jar"
$fml = "C:\Users\catiswin\.gradle\caches\minecraftforge\forgegradle\mavenizer\caches\maven\forge\net\minecraftforge\javafmllanguage\1.21.1-52.1.16\javafmllanguage-1.21.1-52.1.16.jar"
$brigadier = "C:\Users\catiswin\AppData\Roaming\PrismLauncher\libraries\com\mojang\brigadier\1.1.8\brigadier-1.1.8.jar"
$figura = "C:\Users\catiswin\.gradle\caches\modules-2\files-2.1\maven.modrinth\figura\0.1.6+1.20.1\82707830e73debfa51b51dc4ec695bc5756c8ea7\figura-0.1.6+1.20.1.jar"
$cp = "$mixin;$slf4j;$fabric;$fml;$brigadier;$figura"

Write-Host "Compiling Java sources with target Java 17 (--release 17)..."
$javaFiles = @(
    (Get-ChildItem -Path "$PSScriptRoot\common\src\main\java" -Filter "*.java" -Recurse).FullName
    (Get-ChildItem -Path "$PSScriptRoot\fabric\src\main\java" -Filter "*.java" -Recurse).FullName
    (Get-ChildItem -Path "$PSScriptRoot\forge\src\main\java" -Filter "*.java" -Recurse).FullName
)

javac --release 17 -proc:none -cp $cp -d $classesDir $javaFiles

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
Implementation-Version: 1.1.7
Specification-Title: figuralink
Specification-Version: 1.1.7
MixinConfigs: figuralink.mixins.json
"@
        Set-Content -Path "$staging\META-INF\MANIFEST.MF" -Value $manifestContent
    }

    $outJar = Join-Path $distDir $jarName
    jar -cf "$outJar" -C "$staging" .
    Write-Host "Built: $jarName"
}

# Build loaders and versions
New-ModJar "figuralink-fabric-1.20.1-1.1.7.jar" "fabric" "1.20.1"
New-ModJar "figuralink-fabric-1.21.1-1.1.7.jar" "fabric" "1.21.1"
New-ModJar "figuralink-forge-1.20.1-1.1.7.jar" "forge" "1.20.1"
New-ModJar "figuralink-forge-1.21.1-1.1.7.jar" "forge" "1.21.1"
New-ModJar "figuralink-universal-1.1.7.jar" "universal" "all"

Write-Host "`nAll mod jars successfully generated in dist\"

# Auto deploy to PrismLauncher test instances
$instances = @(
    "C:\Users\catiswin\AppData\Roaming\PrismLauncher\instances\Skin\minecraft\mods",
    "C:\Users\catiswin\AppData\Roaming\PrismLauncher\instances\Skin2\minecraft\mods"
)

foreach ($inst in $instances) {
    if (Test-Path $inst) {
        try {
            Get-ChildItem -Path $inst -Filter "figuralink-*.jar" | Remove-Item -Force
            Copy-Item (Join-Path $distDir "figuralink-fabric-1.20.1-1.1.7.jar") $inst
            Write-Host "Deployed figuralink-fabric-1.20.1-1.1.7.jar to $inst"
        } catch {
            Write-Warning "Could not update $inst (Minecraft might be running): $_"
        }
    }
}

