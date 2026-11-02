$ErrorActionPreference = 'Stop'
$vehicleDir = Join-Path $PSScriptRoot '..\limitless_vehicle\rvp\data\rvp\vehicles'
$backupDir = Join-Path $PSScriptRoot '..\limitless_vehicle\rvp\_bak'
$files = @('zbl08a.json', 'lav25.json', 'm142.json', '9k720.json', 'bukm3.json', 'irist_slm_tel.json')
$policyKeys = @('deployable_uav_single_instance', 'deployable_uav_allow_control_switch',
    'deployable_uav_auto_link_datalink', 'deployable_uav_redeploy_cooldown_tick',
    'deployable_uav_auto_loiter_on_switch_back', 'deployable_uav_signal_range',
    'deployable_uav_allowed_seat_indexes')
$utf8NoBom = [Text.UTF8Encoding]::new($false)
New-Item -ItemType Directory -Force -Path $backupDir | Out-Null
foreach ($file in $files) {
    $path = Join-Path $vehicleDir $file
    $raw = [IO.File]::ReadAllText($path)
    $lineEnding = if ($raw.Contains("`r`n")) { "`r`n" } else { "`n" }
    $vehicle = $raw | ConvertFrom-Json
    if ($vehicle.deployable_uav_enabled -ne $true -or -not $vehicle.deployable_uav_vehicle_id) {
        throw "Expected existing flat deployable UAV configuration: $file"
    }
    if ($vehicle.PSObject.Properties.Name -contains 'deployable_uav_options') {
        throw "Already migrated; refusing to overwrite: $file"
    }
    $legacyVehicleId = [string]$vehicle.deployable_uav_vehicle_id
    $legacyRole = [string]$vehicle.deployable_uav_role
    $legacyOffset = ConvertTo-Json -InputObject $vehicle.deployable_uav_spawn_offset -Depth 8 -Compress
    $legacyYaw = [string]$vehicle.deployable_uav_spawn_yaw_mode
    $legacySpeed = ConvertTo-Json -InputObject $vehicle.deployable_uav_initial_speed -Compress
    $optionId = switch ($legacyVehicleId) {
        'rvp:suav' { 'suav'; break }
        'rvp:96l6' { 'radar_96l6'; break }
        'rvp:irist_slm_tads' { 'radar_irist_slm_tads'; break }
        default { throw "Unrecognized helper vehicle in ${file}: $legacyVehicleId" }
    }
    $optionName = switch ($legacyVehicleId) {
        'rvp:suav' { 'SUAV'; break }
        'rvp:96l6' { '96L6'; break }
        'rvp:irist_slm_tads' { 'IRIS-T SLM TADS'; break }
    }
    $indent = '  '
    $lines = [Collections.Generic.List[string]]::new()
    $lines.Add($indent + '"deployable_uav_enabled": true,')
    $lines.Add($indent + '"deployable_uav_default_option": ' + (ConvertTo-Json -InputObject $optionId -Compress) + ',')
    $lines.Add($indent + '"deployable_uav_options": [')
    $lines.Add($indent + '  {')
    $lines.Add($indent + '    "id": ' + (ConvertTo-Json -InputObject $optionId -Compress) + ',')
    $lines.Add($indent + '    "name": ' + (ConvertTo-Json -InputObject $optionName -Compress) + ',')
    $lines.Add($indent + '    "vehicle_id": ' + (ConvertTo-Json -InputObject $legacyVehicleId -Compress) + ',')
    $lines.Add($indent + '    "role": ' + (ConvertTo-Json -InputObject $legacyRole -Compress) + ',')
    $lines.Add($indent + '    "spawn_offset": ' + $legacyOffset + ',')
    $lines.Add($indent + '    "spawn_yaw_mode": ' + (ConvertTo-Json -InputObject $legacyYaw -Compress) + ',')
    $lines.Add($indent + '    "initial_speed": ' + $legacySpeed)
    $lines.Add($indent + '  }')
    $lines.Add($indent + '],')
    foreach ($key in $policyKeys) {
        $property = $vehicle.PSObject.Properties[$key]
        if ($null -eq $property) { continue }
        $lines.Add($indent + (ConvertTo-Json -InputObject $key -Compress) + ': ' + (ConvertTo-Json -InputObject $property.Value -Depth 8 -Compress) + ',')
    }
    # Keep the trailing comma because the original vehicle file has following top-level fields.
    $replacement = [string]::Join($lineEnding, $lines) + $lineEnding
    $pattern = '(?ms)^  "deployable_uav_enabled"\s*:\s*true,.*?(?=^  "(?!deployable_uav_)[^"]+"\s*:)'
    if (-not [regex]::IsMatch($raw, $pattern)) { throw "Could not locate contiguous deployable UAV field block: $file" }
    $backup = Join-Path $backupDir ($file + '.bak_' + (Get-Date -Format 'yyyyMMdd_HHmmss') + '_aux_options')
    if (Test-Path -LiteralPath $backup) { throw "Backup already exists: $backup" }
    Copy-Item -LiteralPath $path -Destination $backup
    $updated = [regex]::Replace($raw, $pattern, [System.Text.RegularExpressions.MatchEvaluator]{ param($m) $replacement }, 1)
    $parsed = $updated | ConvertFrom-Json
    if ($parsed.deployable_uav_default_option -ne $optionId -or $parsed.deployable_uav_options.Count -ne 1 -or $parsed.deployable_uav_options[0].vehicle_id -ne $legacyVehicleId) {
        throw "Post-migration schema validation failed: $file"
    }
    foreach ($legacyKey in @('deployable_uav_vehicle_id', 'deployable_uav_role', 'deployable_uav_spawn_offset',
            'deployable_uav_spawn_yaw_mode', 'deployable_uav_initial_speed')) {
        if ($parsed.PSObject.Properties.Name -contains $legacyKey) { throw "Legacy flat key remains: $file / $legacyKey" }
    }
    [IO.File]::WriteAllText($path, $updated, $utf8NoBom)
    "MIGRATED $file -> $optionId; backup=$backup"
}