# RenderDoc launch helper for loom's runClientRenderDoc task.
#
# loom invokes:
#   tools\renderdoc-layer-launcher.cmd capture --wait-for-exit --working-dir <runDir> <java.exe> <args...>
# (the .cmd is a thin stub that forwards everything here)
#
# We drop loom's renderdoccmd-specific prefix, then start java directly with RenderDoc's
# Vulkan layer enabled. This avoids renderdoccmd lowering the target to Low integrity,
# which breaks the Fabric dev remap (%TEMP% / run\.fabric writes) on this machine.

$ErrorActionPreference = 'Stop'

$java = $null
$rest = @()
for ($i = 0; $i -lt $args.Count; $i++) {
    if ($args[$i] -match 'java\.exe$') {
        $java = $args[$i]
        if ($i + 1 -lt $args.Count) {
            $rest = @($args[($i + 1)..($args.Count - 1)])
        }
        break
    }
}

if (-not $java) {
    Write-Host '[renderdoc-wrapper] ERROR: java.exe not found in arguments'
    exit 9
}

$rcdDir = Join-Path (Split-Path -Parent $PSScriptRoot) 'build\renderdoc\RenderDoc_1.46_64'
if (-not (Test-Path (Join-Path $rcdDir 'renderdoc.dll'))) {
    Write-Host "[renderdoc-wrapper] ERROR: RenderDoc layer not found at $rcdDir (run: gradlew extractRenderDoc)"
    exit 8
}

$env:VK_LAYER_PATH = $rcdDir
$env:VK_INSTANCE_LAYERS = 'VK_LAYER_RENDERDOC_Capture'
Write-Host "[renderdoc-wrapper] launching java with RenderDoc Vulkan layer: $rcdDir"

& $java @rest
exit $LASTEXITCODE
