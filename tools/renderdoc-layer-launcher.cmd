@echo off
rem RenderDoc launch wrapper for loom's runClientRenderDoc task.
rem
rem loom calls: <this file> capture --wait-for-exit --working-dir <runDir> <java.exe> <args...>
rem
rem renderdoccmd.exe downgrades the target java process to Low integrity on this machine,
rem which breaks the Fabric dev runtime remap (cannot write %TEMP% / run\.fabric) and makes
rem renderdoc injection fail. This wrapper skips renderdoccmd and starts java directly with
rem RenderDoc's Vulkan layer enabled (VK_LAYER_PATH + VK_INSTANCE_LAYERS), which gives the same
rem result as: gradlew runClient -Prenderdoc, but keeps the runClientRenderDoc task working.
rem
rem Argument parsing is done in renderdoc-layer-launcher.ps1 (powershell handles quoting).
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0renderdoc-layer-launcher.ps1" %*
exit /b %ERRORLEVEL%
