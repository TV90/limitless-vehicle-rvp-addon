param(
    [Parameter(Mandatory = $true)]
    [string[]] $Roots
)

$ErrorActionPreference = 'Stop'
$Utf8NoBom = New-Object System.Text.UTF8Encoding($false)

function Get-Value($Object, [string] $Name, $Default = $null) {
    if ($null -eq $Object) { return $Default }
    $Property = $Object.PSObject.Properties[$Name]
    if ($null -eq $Property -or $null -eq $Property.Value) { return $Default }
    return $Property.Value
}

function Has-ExactTypes($Stage, [string[]] $Expected) {
    $Actual = @($Stage.sources | ForEach-Object { [string] $_.type } | Sort-Object)
    $SortedExpected = @($Expected | Sort-Object)
    return ($Actual.Count -eq $SortedExpected.Count) -and
        ((Compare-Object $Actual $SortedExpected).Count -eq 0)
}

function To-Range([double] $Upper) {
    return "[[0,$Upper]]"
}

function Move-RequireLock($Weapon) {
    $RequireLock = Get-Value $Weapon 'require_lock'
    if ($null -eq $RequireLock) { return }
    if ($null -eq $Weapon.fire_data) {
        $Weapon | Add-Member -NotePropertyName 'fire_data' -NotePropertyValue ([pscustomobject] [ordered] @{})
    }
    if ($null -ne (Get-Value $Weapon.fire_data 'require_lock')) {
        throw 'fire_data.require_lock already exists'
    }
    $Weapon.fire_data | Add-Member -NotePropertyName 'require_lock' -NotePropertyValue ([bool] $RequireLock)
    $Weapon.PSObject.Properties.Remove('require_lock')
}

function Move-TurningFactor($Weapon, $Stage, [int] $StartTick, $EndTick) {
    $Factor = Get-Value $Stage.steering_data 'turning_factor'
    if ($null -eq $Factor) { return }
    if ($null -ne (Get-Value $Weapon.projectile_data 'turning_factor')) {
        throw 'projectile_data.turning_factor already exists'
    }
    $EndText = if ($null -ne $EndTick) { [int] $EndTick } else { 'inf' }
    $Ranges = [ordered] @{ "[[$StartTick,$EndText]]" = [double] $Factor }
    $Weapon.projectile_data | Add-Member -NotePropertyName 'turning_factor' -NotePropertyValue ([pscustomobject] $Ranges)
}

function Add-HitlView($Guidance, $LegacyHitl) {
    if ($null -eq $LegacyHitl) { return }
    if ([string] (Get-Value $LegacyHitl 'control_mode' 'VIEW') -ne 'VIEW') {
        throw 'standard SARH migration only supports HITL control_mode VIEW'
    }
    if (-not [bool] (Get-Value $LegacyHitl 'enabled' $false)) { return }
    $Guidance['hitl_enabled'] = $true
    $Map = [ordered] @{
        signal_source = 'signal_source'
        hitl_max_control_dist = 'control_range'
        hitl_max_control_tick = 'timeout_tick'
        hitl_max_turn_deg_per_tick = 'max_turn_deg_per_tick'
        hitl_max_look_offset = 'max_look_offset_deg'
        hitl_video_modes = 'video_modes'
    }
    foreach ($Entry in $Map.GetEnumerator()) {
        $Value = Get-Value $LegacyHitl $Entry.Value
        if ($null -ne $Value) { $Guidance[$Entry.Key] = $Value }
    }
}

function Move-GpsFields($Weapon, $Guidance) {
    $Projectile = $Weapon.projectile_data
    $Spread = Get-Value $Projectile 'gps_cep'
    if ($null -ne $Spread -and [double] $Spread -gt 0) {
        $Guidance['gps_spread_radius'] = [double] $Spread
    }
    $CruiseStart = Get-Value $Projectile 'gps_cruise_start_tick'
    $CruiseEnd = Get-Value $Projectile 'gps_cruise_terminal_cylinder_radius'
    if ($null -ne $CruiseStart -and [int] $CruiseStart -ge 0 -and
            $null -ne $CruiseEnd -and [double] $CruiseEnd -gt 0) {
        $Guidance['cruise_start_tick'] = [int] $CruiseStart
        $Guidance['cruise_end_horizontal_dist'] = [double] $CruiseEnd
        $Guidance['cruise_gravity_scale'] = [double] (Get-Value $Projectile 'gps_cruise_gravity_scale' 1.0)
        $Guidance['cruise_leveling_factor'] = [double] (Get-Value $Projectile 'gps_cruise_leveling_factor' 0.15)
    }
    $Cancel = Get-Value $Projectile 'gps_guidance_cancel_distance'
    if ($null -ne $Cancel -and [double] $Cancel -gt 0) {
        $Guidance['guidance_target_distance_range'] = "[[$Cancel,inf]]"
    }
    foreach ($Name in @(
        'gps_cep', 'gps_cruise_start_tick', 'gps_cruise_terminal_cylinder_radius',
        'gps_cruise_gravity_scale', 'gps_cruise_leveling_factor', 'gps_guidance_cancel_distance'
    )) {
        $Projectile.PSObject.Properties.Remove($Name)
    }
}

function Convert-None($Weapon, $Stages) {
    if ($Stages.Count -ne 1 -or -not (Has-ExactTypes $Stages[0] @('NONE'))) {
        return $false
    }
    $Weapon.guidance_data = [pscustomobject] [ordered] @{ guidance_type = 'NONE' }
    Move-RequireLock $Weapon
    return $true
}

function Convert-Gps($Weapon, $Stages) {
    if ($Stages.Count -ne 1 -or -not (Has-ExactTypes $Stages[0] @('GPS', 'IOG'))) {
        return $false
    }
    $Hitl = Get-Value $Weapon.guidance_data 'human_in_the_loop'
    if ($null -ne $Hitl -and [bool] (Get-Value $Hitl 'enabled' $false)) { return $false }
    $Stage = $Stages[0]
    $Start = [int] (Get-Value $Stage.activation 'start_tick' (Get-Value $Stage 'start_tick' 0)) +
        [int] (Get-Value $Stage.steering_data 'rigidity_time' 0)
    $End = Get-Value $Stage.activation 'end_tick'
    $EndText = if ($null -ne $End) { [int] $End } else { 'inf' }
    $Guidance = [ordered] @{
        guidance_type = 'GPS'
        guidance_tick_range = "[[$Start,$EndText]]"
        max_guidance_angle = [int] (Get-Value $Stage.steering_data 'max_degree_of_missile' 180)
        predict_target_pos = [bool] (Get-Value $Stage.steering_data 'predict_target_pos' $true)
        enable_inertial_guidance = $true
    }
    Move-GpsFields $Weapon $Guidance
    Move-TurningFactor $Weapon $Stage $Start $End
    Move-RequireLock $Weapon
    $Weapon.guidance_data = [pscustomobject] $Guidance
    return $true
}

function Convert-Mclos($Weapon, $Stages) {
    if ($Stages.Count -ne 1 -or -not (Has-ExactTypes $Stages[0] @('MCLOS'))) {
        return $false
    }
    $Hitl = Get-Value $Weapon.guidance_data 'human_in_the_loop'
    if ($null -ne $Hitl -and [bool] (Get-Value $Hitl 'enabled' $false)) { return $false }
    $Stage = $Stages[0]
    $Source = @($Stage.sources)[0]
    if (-not [bool] (Get-Value $Source.params 'use_weapon_unit_aim' $true) -or
            [bool] (Get-Value $Source.params 'use_owner_look' $false)) {
        return $false
    }
    $Start = [int] (Get-Value $Stage.activation 'start_tick' 0) +
        [int] (Get-Value $Stage.steering_data 'rigidity_time' 0)
    $End = Get-Value $Stage.activation 'end_tick'
    $EndText = if ($null -ne $End) { [int] $End } else { 'inf' }
    $Range = [double] (Get-Value $Stage.seeker 'range' 512)
    $Guidance = [ordered] @{
        guidance_type = 'SACLOS'
        guidance_tick_range = "[[$Start,$EndText]]"
        guidance_target_distance_range = To-Range $Range
        max_guidance_angle = [int] (Get-Value $Stage.steering_data 'max_degree_of_missile' 180)
        enable_inertial_guidance = $false
    }
    Move-TurningFactor $Weapon $Stage $Start $End
    Move-RequireLock $Weapon
    $Weapon.guidance_data = [pscustomobject] $Guidance
    return $true
}

function Convert-Sarh($Weapon, $Stages) {
    $Single = $Stages.Count -eq 1 -and (Has-ExactTypes $Stages[0] @('SARH', 'IOG'))
    $Two = $Stages.Count -eq 2 -and
        (Has-ExactTypes $Stages[0] @('IOG')) -and
        (Has-ExactTypes $Stages[1] @('SARH'))
    if (-not $Single -and -not $Two) { return $false }
    $Stage = if ($Single) { $Stages[0] } else { $Stages[1] }
    $Start = [int] (Get-Value $Stage.activation 'start_tick' 0) +
        [int] (Get-Value $Stage.steering_data 'rigidity_time' 0)
    $End = Get-Value $Stage.activation 'end_tick'
    $EndText = if ($null -ne $End) { [int] $End } else { 'inf' }
    $Range = [double] (Get-Value $Stage.seeker 'range' 512)
    $Fov = [int] (Get-Value $Stage.seeker 'fov' 30)
    $OffAxis = [int] (Get-Value $Stage.seeker 'guide_head_max_angle' $Fov)
    $Guidance = [ordered] @{
        guidance_type = 'SARH'
        guidance_tick_range = "[[$Start,$EndText]]"
        guidance_target_distance_range = To-Range $Range
        lock_target_distance_range = To-Range $Range
        max_lock_angle = $Fov
        max_off_axis_lock_angle = $OffAxis
        max_guidance_angle = [int] (Get-Value $Stage.steering_data 'max_degree_of_missile' 180)
        predict_target_pos = [bool] (Get-Value $Stage.steering_data 'predict_target_pos' $true)
        enable_inertial_guidance = $true
    }
    foreach ($Name in @('ignore_chaff', 'jam_resistance', 'home_on_jam', 'decoy_filter')) {
        $Value = Get-Value $Stage.seeker $Name
        if ($null -ne $Value) { $Guidance[$Name] = $Value }
    }
    Add-HitlView $Guidance (Get-Value $Weapon.guidance_data 'human_in_the_loop')
    Move-TurningFactor $Weapon $Stage $Start $End
    Move-RequireLock $Weapon
    $Weapon.guidance_data = [pscustomobject] $Guidance
    return $true
}

$Changed = 0
foreach ($Root in $Roots) {
    foreach ($File in Get-ChildItem -LiteralPath $Root -Filter '*.json') {
        $Text = [System.IO.File]::ReadAllText($File.FullName, [System.Text.Encoding]::UTF8)
        $Weapon = $Text | ConvertFrom-Json
        $Stages = @($Weapon.guidance_data.stages)
        $Converted = (Convert-None $Weapon $Stages) -or
            (Convert-Gps $Weapon $Stages) -or
            (Convert-Mclos $Weapon $Stages) -or
            (Convert-Sarh $Weapon $Stages)
        if ($Converted) {
            $Json = $Weapon | ConvertTo-Json -Depth 100
            [System.IO.File]::WriteAllText($File.FullName, $Json + [Environment]::NewLine, $Utf8NoBom)
            Write-Output "migrated: $($File.FullName)"
            $Changed++
        }
    }
}
Write-Output "done: $Changed file(s)"
