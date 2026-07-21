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

function Get-SourceTypes($Stage) {
    return @($Stage.sources | ForEach-Object { [string] $_.type })
}

function Has-ExactTypes($Stage, [string[]] $Expected) {
    $Actual = @(Get-SourceTypes $Stage | Sort-Object)
    $SortedExpected = @($Expected | Sort-Object)
    return ($Actual.Count -eq $SortedExpected.Count) -and
        ((Compare-Object $Actual $SortedExpected).Count -eq 0)
}

function To-Range([double] $Upper) {
    return "[[0,$Upper]]"
}

function Add-Optional($Target, [string] $Name, $Value) {
    if ($null -ne $Value) {
        $Target[$Name] = $Value
    }
}

function Resolve-ArhParams($Stage) {
    foreach ($Source in @($Stage.sources)) {
        if ([string] $Source.type -eq 'ARH') {
            return $Source.params
        }
    }
    return $null
}

function Build-TurningFactor($Weapon, $MainStage, $TerminalStage, [int] $MainStart,
                             $MainEnd, [int] $TerminalStart) {
    $MainFactor = Get-Value $MainStage.steering_data 'turning_factor'
    $TerminalFactor = if ($null -ne $TerminalStage) {
        Get-Value $TerminalStage.steering_data 'turning_factor'
    } else {
        $null
    }
    if ($null -eq $MainFactor -and $null -eq $TerminalFactor) {
        return
    }
    if ($null -ne (Get-Value $Weapon.projectile_data 'turning_factor')) {
        throw "projectile_data.turning_factor already exists"
    }

    $Ranges = [ordered] @{}
    $MainHasWindow = $null -eq $MainEnd -or $MainStart -le [int] $MainEnd
    if ($null -ne $MainFactor -and $MainHasWindow) {
        $End = if ($null -ne $MainEnd) { [int] $MainEnd } else { 'inf' }
        $Ranges["[[$MainStart,$End]]"] = [double] $MainFactor
    }
    if ($null -ne $TerminalFactor) {
        $Ranges["[[$TerminalStart,inf]]"] = [double] $TerminalFactor
    }
    if ($Ranges.Count -gt 0) {
        $Weapon.projectile_data | Add-Member -NotePropertyName 'turning_factor' -NotePropertyValue ([pscustomobject] $Ranges)
    }
}

function Move-RequireLock($Weapon) {
    $RequireLock = Get-Value $Weapon 'require_lock'
    if ($null -eq $RequireLock) {
        return
    }
    if ($null -eq $Weapon.fire_data) {
        $Weapon | Add-Member -NotePropertyName 'fire_data' -NotePropertyValue ([pscustomobject] [ordered] @{})
    }
    if ($null -eq (Get-Value $Weapon.fire_data 'require_lock')) {
        $Weapon.fire_data | Add-Member -NotePropertyName 'require_lock' -NotePropertyValue ([bool] $RequireLock)
    }
    $Weapon.PSObject.Properties.Remove('require_lock')
}

function Convert-ArhWeapon($Weapon) {
    $Stages = @($Weapon.guidance_data.stages)
    $SingleStage = $Stages.Count -eq 1 -and (Has-ExactTypes $Stages[0] @('ARH', 'IOG'))
    $TwoStage = $Stages.Count -eq 2 -and
        (Has-ExactTypes $Stages[0] @('IOG')) -and
        (Has-ExactTypes $Stages[1] @('ARH', 'IOG'))
    if (-not $SingleStage -and -not $TwoStage) {
        return $false
    }

    $MainStage = $Stages[0]
    $ArhStage = if ($SingleStage) { $Stages[0] } else { $Stages[1] }
    $MainActivation = $MainStage.activation
    $ArhActivation = $ArhStage.activation
    $MainStart = [int] (Get-Value $MainActivation 'start_tick' 0) +
        [int] (Get-Value $MainStage.steering_data 'rigidity_time' 0)
    $MainEnd = Get-Value $MainActivation 'end_tick'
    $ArhStart = [int] (Get-Value $ArhActivation 'start_tick' 0) +
        [int] (Get-Value $ArhStage.steering_data 'rigidity_time' 0)

    $Seeker = $ArhStage.seeker
    $Range = [double] (Get-Value $Seeker 'range' 512)
    $Fov = [int] (Get-Value $Seeker 'fov' 30)
    $OffAxis = [int] (Get-Value $Seeker 'guide_head_max_angle' $Fov)
    $ScanInterval = [int] (Get-Value $Seeker 'scan_interval_tick' 2)
    $MaxGuidance = [int] (Get-Value $ArhStage.steering_data 'max_degree_of_missile' 180)
    $Predict = [bool] (Get-Value $ArhStage.steering_data 'predict_target_pos' $true)
    $Params = Resolve-ArhParams $ArhStage
    $ActivationRange = [int] (Get-Value $Params 'active_radar_activation_range' 256)
    $LegacyHitl = Get-Value $Weapon.guidance_data 'human_in_the_loop'
    if ($null -ne $LegacyHitl -and
            [string] (Get-Value $LegacyHitl 'control_mode' 'VIEW') -ne 'VIEW') {
        throw "ARH migration only supports legacy HITL control_mode VIEW"
    }

    $MainHasWindow = $SingleStage -or $null -eq $MainEnd -or $MainStart -le [int] $MainEnd
    $Guidance = [ordered] @{
        guidance_type = if ($SingleStage) { 'ARH' } else { 'NONE' }
        lock_target_distance_range = To-Range $Range
        enable_inertial_guidance = $MainHasWindow
        max_lock_angle = $Fov
        max_off_axis_lock_angle = $OffAxis
    }
    if ($MainHasWindow) {
        $Guidance['guidance_tick_range'] = if ($null -ne $MainEnd) {
            "[[$MainStart,$([int] $MainEnd)]]"
        } else {
            "[[$MainStart,inf]]"
        }
    }

    if ($SingleStage) {
        $Guidance['guidance_target_distance_range'] = To-Range $Range
        $Guidance['max_guidance_angle'] = $MaxGuidance
        $Guidance['scan_interval_tick'] = $ScanInterval
        $Guidance['predict_target_pos'] = $Predict
        $Guidance['active_radar_activation_range'] = $ActivationRange
    } else {
        $Terminal = [ordered] @{
            guidance_type = 'ARH'
            guidance_start_tick = $ArhStart
            guidance_target_distance_range = To-Range $Range
            max_lock_angle = $Fov
            max_guidance_angle = $MaxGuidance
            scan_interval_tick = $ScanInterval
            predict_target_pos = $Predict
            active_radar_activation_range = $ActivationRange
            enable_inertial_guidance = $true
        }
        $Guidance['terminal_guidance'] = [pscustomobject] $Terminal
    }

    if ($null -ne $LegacyHitl -and [bool] (Get-Value $LegacyHitl 'enabled' $false)) {
        $Guidance['hitl_enabled'] = $true
        Add-Optional $Guidance 'signal_source' (Get-Value $LegacyHitl 'signal_source')
        Add-Optional $Guidance 'hitl_max_control_dist' (Get-Value $LegacyHitl 'control_range')
        Add-Optional $Guidance 'hitl_max_control_tick' (Get-Value $LegacyHitl 'timeout_tick')
        Add-Optional $Guidance 'hitl_max_turn_deg_per_tick' (Get-Value $LegacyHitl 'max_turn_deg_per_tick')
        Add-Optional $Guidance 'hitl_max_look_offset' (Get-Value $LegacyHitl 'max_look_offset_deg')
        Add-Optional $Guidance 'hitl_video_modes' (Get-Value $LegacyHitl 'video_modes')
    }

    foreach ($Name in @('ignore_chaff', 'jam_resistance', 'home_on_jam', 'decoy_filter')) {
        Add-Optional $Guidance $Name (Get-Value $Seeker $Name)
    }

    Build-TurningFactor $Weapon $MainStage $(if ($TwoStage) { $ArhStage } else { $null }) `
        $MainStart $MainEnd $ArhStart
    Move-RequireLock $Weapon
    $Weapon.guidance_data = [pscustomobject] $Guidance
    return $true
}

$Changed = 0
foreach ($Root in $Roots) {
    foreach ($File in Get-ChildItem -LiteralPath $Root -Filter '*.json') {
        $Text = [System.IO.File]::ReadAllText($File.FullName, [System.Text.Encoding]::UTF8)
        $Weapon = $Text | ConvertFrom-Json
        if (Convert-ArhWeapon $Weapon) {
            $Json = $Weapon | ConvertTo-Json -Depth 100
            [System.IO.File]::WriteAllText($File.FullName, $Json + [Environment]::NewLine, $Utf8NoBom)
            Write-Output "migrated: $($File.FullName)"
            $Changed++
        }
    }
}
Write-Output "done: $Changed file(s)"
