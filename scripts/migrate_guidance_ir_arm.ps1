param(
    [Parameter(Mandatory = $true)]
    [string[]] $Roots
)

$ErrorActionPreference = 'Stop'
$Utf8NoBom = New-Object System.Text.UTF8Encoding($false)

function Get-Value($Object, [string] $Name, $Default = $null) {
    if ($null -eq $Object) {
        return $Default
    }
    $Property = $Object.PSObject.Properties[$Name]
    if ($null -eq $Property -or $null -eq $Property.Value) {
        return $Default
    }
    return $Property.Value
}

function Has-ExactTypes($Stage, [string[]] $Expected) {
    $Actual = @($Stage.sources | ForEach-Object { [string] $_.type } | Sort-Object)
    $SortedExpected = @($Expected | Sort-Object)
    return ($Actual.Count -eq $SortedExpected.Count) -and
        ((Compare-Object $Actual $SortedExpected).Count -eq 0)
}

function Get-Source($Stage, [string] $Type) {
    return @($Stage.sources | Where-Object { [string] $_.type -eq $Type })[0]
}

function To-DistanceRange([double] $Upper) {
    return "[[0,$Upper]]"
}

function To-AltitudeRange([double] $LegacyHeight) {
    if ($LegacyHeight -gt 0) {
        return "[[$LegacyHeight,inf]]"
    }
    if ($LegacyHeight -lt 0) {
        return "[[inf,$([Math]::Abs($LegacyHeight))]]"
    }
    return $null
}

function Add-Optional($Target, [string] $Name, $Value) {
    if ($null -ne $Value) {
        $Target[$Name] = $Value
    }
}

function Add-HitlView($Guidance, $LegacyHitl) {
    if ($null -eq $LegacyHitl) {
        return
    }
    if ([string] (Get-Value $LegacyHitl 'control_mode' 'VIEW') -ne 'VIEW') {
        throw 'IR/ARM migration only supports legacy HITL control_mode VIEW'
    }
    if (-not [bool] (Get-Value $LegacyHitl 'enabled' $false)) {
        return
    }
    $Guidance['hitl_enabled'] = $true
    Add-Optional $Guidance 'signal_source' (Get-Value $LegacyHitl 'signal_source')
    Add-Optional $Guidance 'hitl_max_control_dist' (Get-Value $LegacyHitl 'control_range')
    Add-Optional $Guidance 'hitl_max_control_tick' (Get-Value $LegacyHitl 'timeout_tick')
    Add-Optional $Guidance 'hitl_max_turn_deg_per_tick' (Get-Value $LegacyHitl 'max_turn_deg_per_tick')
    Add-Optional $Guidance 'hitl_max_look_offset' (Get-Value $LegacyHitl 'max_look_offset_deg')
    Add-Optional $Guidance 'hitl_video_modes' (Get-Value $LegacyHitl 'video_modes')
}

function Move-RequireLock($Weapon) {
    $RequireLock = Get-Value $Weapon 'require_lock'
    if ($null -eq $RequireLock) {
        return
    }
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
    $TurningFactor = Get-Value $Stage.steering_data 'turning_factor'
    if ($null -eq $TurningFactor) {
        return
    }
    if ($null -ne (Get-Value $Weapon.projectile_data 'turning_factor')) {
        throw 'projectile_data.turning_factor already exists'
    }
    $End = if ($null -ne $EndTick) { [int] $EndTick } else { 'inf' }
    $Ranges = [ordered] @{ "[[$StartTick,$End]]" = [double] $TurningFactor }
    $Weapon.projectile_data | Add-Member -NotePropertyName 'turning_factor' -NotePropertyValue ([pscustomobject] $Ranges)
}

function Convert-SingleStageWeapon($Weapon) {
    $Stages = @($Weapon.guidance_data.stages)
    if ($Stages.Count -ne 1) {
        return $false
    }
    $Stage = $Stages[0]
    $Type = if (Has-ExactTypes $Stage @('IR', 'IOG')) {
        'IR'
    } elseif (Has-ExactTypes $Stage @('ARM', 'IOG')) {
        'ARM'
    } else {
        return $false
    }

    $Activation = $Stage.activation
    $StartTick = [int] (Get-Value $Activation 'start_tick' 0) +
        [int] (Get-Value $Stage.steering_data 'rigidity_time' 0)
    $EndTick = Get-Value $Activation 'end_tick'
    if ($null -ne $EndTick -and $StartTick -gt [int] $EndTick) {
        throw "guidance rigidity exceeds stage window: $StartTick > $EndTick"
    }

    $Seeker = $Stage.seeker
    $Range = [double] (Get-Value $Seeker 'range' 512)
    $Fov = [int] (Get-Value $Seeker 'fov' 30)
    $OffAxis = [int] (Get-Value $Seeker 'guide_head_max_angle' $Fov)
    $MaxGuidance = [int] (Get-Value $Stage.steering_data 'max_degree_of_missile' 180)
    $Predict = [bool] (Get-Value $Stage.steering_data 'predict_target_pos' $true)
    $End = if ($null -ne $EndTick) { [int] $EndTick } else { 'inf' }

    $Guidance = [ordered] @{
        guidance_type = $Type
        guidance_tick_range = "[[$StartTick,$End]]"
        guidance_target_distance_range = To-DistanceRange $Range
        lock_target_distance_range = To-DistanceRange $Range
        max_lock_angle = $Fov
        max_off_axis_lock_angle = $OffAxis
        max_guidance_angle = $MaxGuidance
        predict_target_pos = $Predict
        enable_inertial_guidance = $true
    }

    if ($Type -eq 'IR') {
        $Altitude = To-AltitudeRange ([double] (Get-Value $Seeker 'lock_min_height' 4))
        Add-Optional $Guidance 'lock_altitude_range' $Altitude
        Add-Optional $Guidance 'guidance_altitude_range' $Altitude
        $LegacyHms = Get-Value $Weapon 'enable_hms'
        if ($null -eq $LegacyHms) {
            $LegacyHms = Get-Value $Weapon 'enableHMS'
        }
        Add-Optional $Guidance 'enable_ir_hmd' $(if ($null -ne $LegacyHms) { [bool] $LegacyHms } else { $null })
        $Guidance['scan_interval_tick'] = [int] (Get-Value $Seeker 'scan_interval_tick' 2)
        foreach ($Name in @('ignore_flares', 'ignore_chaff', 'dircm_resistance', 'jam_resistance', 'home_on_jam', 'decoy_filter')) {
            Add-Optional $Guidance $Name (Get-Value $Seeker $Name)
        }
    } else {
        $Params = (Get-Source $Stage 'ARM').params
        $Guidance['scan_interval_tick'] = [int] (Get-Value $Params 'scan_interval_tick' (Get-Value $Seeker 'scan_interval_tick' 2))
        $Guidance['radiation_pulse_memory_tick'] = [int] (Get-Value $Params 'radiation_pulse_memory_tick' 25)
        $Guidance['arm_memory_tick'] = [int] (Get-Value $Params 'memory_tick' 0)
        $Guidance['arm_locked_emitter_bonus'] = [double] (Get-Value $Params 'locked_bonus' 0.5)
    }

    $DiveAngle = Get-Value $Stage.steering_data 'terminal_dive_angle'
    if ($null -ne $DiveAngle) {
        if ([Math]::Abs([double] $DiveAngle - 45.0) -gt 0.001) {
            throw "terminal_dive_angle $DiveAngle cannot be represented by top_attack_height"
        }
        $Guidance['top_attack_height'] = 80.0
    }

    Add-HitlView $Guidance (Get-Value $Weapon.guidance_data 'human_in_the_loop')
    Move-TurningFactor $Weapon $Stage $StartTick $EndTick
    Move-RequireLock $Weapon
    $Weapon.PSObject.Properties.Remove('enable_hms')
    $Weapon.PSObject.Properties.Remove('enableHMS')
    $Weapon.guidance_data = [pscustomobject] $Guidance
    return $true
}

$Changed = 0
foreach ($Root in $Roots) {
    foreach ($File in Get-ChildItem -LiteralPath $Root -Filter '*.json') {
        $Text = [System.IO.File]::ReadAllText($File.FullName, [System.Text.Encoding]::UTF8)
        $Weapon = $Text | ConvertFrom-Json
        if (Convert-SingleStageWeapon $Weapon) {
            $Json = $Weapon | ConvertTo-Json -Depth 100
            [System.IO.File]::WriteAllText($File.FullName, $Json + [Environment]::NewLine, $Utf8NoBom)
            Write-Output "migrated: $($File.FullName)"
            $Changed++
        }
    }
}
Write-Output "done: $Changed file(s)"
