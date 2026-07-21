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

function To-Range([double] $Upper) { return "[[0,$Upper]]" }

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

function Add-Hitl($Guidance, $LegacyHitl) {
    if ($null -eq $LegacyHitl -or -not [bool] (Get-Value $LegacyHitl 'enabled' $false)) {
        throw 'HITL migration requires enabled human_in_the_loop data'
    }
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

function Set-TurningFactors($Weapon, $Ranges) {
    if ($Ranges.Count -eq 0) { return }
    if ($null -ne (Get-Value $Weapon.projectile_data 'turning_factor')) {
        throw 'projectile_data.turning_factor already exists'
    }
    $Weapon.projectile_data | Add-Member -NotePropertyName 'turning_factor' -NotePropertyValue ([pscustomobject] $Ranges)
}

function Convert-IogIr($Weapon, $Stages) {
    if ($Stages.Count -ne 2 -or
            -not (Has-ExactTypes $Stages[0] @('IOG')) -or
            -not (Has-ExactTypes $Stages[1] @('IR'))) { return $false }
    $Main = $Stages[0]
    $TerminalStage = $Stages[1]
    $MainStart = [int] (Get-Value $Main.activation 'start_tick' 0) + [int] (Get-Value $Main.steering_data 'rigidity_time' 0)
    $MainEnd = [int] (Get-Value $Main.activation 'end_tick' 8)
    $TerminalStart = [int] (Get-Value $TerminalStage.activation 'start_tick' ($MainEnd + 1)) +
        [int] (Get-Value $TerminalStage.steering_data 'rigidity_time' 0)
    $Range = [double] (Get-Value $TerminalStage.seeker 'range' 512)
    $Fov = [int] (Get-Value $TerminalStage.seeker 'fov' 30)
    $Guidance = [ordered] @{
        guidance_type = 'NONE'
        guidance_tick_range = "[[$MainStart,$MainEnd]]"
        lock_target_distance_range = To-Range $Range
        max_lock_angle = $Fov
        max_off_axis_lock_angle = [int] (Get-Value $TerminalStage.seeker 'guide_head_max_angle' $Fov)
        max_guidance_angle = [int] (Get-Value $Main.steering_data 'max_degree_of_missile' 180)
        enable_inertial_guidance = $true
        terminal_guidance = [pscustomobject] [ordered] @{
            guidance_type = 'IR'
            guidance_start_tick = $TerminalStart
            guidance_target_distance_range = To-Range $Range
            max_lock_angle = $Fov
            max_guidance_angle = [int] (Get-Value $TerminalStage.steering_data 'max_degree_of_missile' 180)
            scan_interval_tick = [int] (Get-Value $TerminalStage.seeker 'scan_interval_tick' 2)
            predict_target_pos = [bool] (Get-Value $TerminalStage.steering_data 'predict_target_pos' $true)
            enable_inertial_guidance = $true
        }
    }
    $Dircm = Get-Value $TerminalStage.seeker 'dircm_resistance'
    if ($null -ne $Dircm) { $Guidance['dircm_resistance'] = $Dircm }
    $Turning = [ordered] @{}
    $MainFactor = Get-Value $Main.steering_data 'turning_factor'
    $TerminalFactor = Get-Value $TerminalStage.steering_data 'turning_factor'
    if ($null -ne $MainFactor) { $Turning["[[$MainStart,$MainEnd]]"] = [double] $MainFactor }
    if ($null -ne $TerminalFactor) { $Turning["[[$TerminalStart,inf]]"] = [double] $TerminalFactor }
    Set-TurningFactors $Weapon $Turning
    Move-RequireLock $Weapon
    $Weapon.guidance_data = [pscustomobject] $Guidance
    return $true
}

function Convert-SaclosArh($Weapon, $Stages) {
    if ($Stages.Count -ne 2 -or
            -not (Has-ExactTypes $Stages[0] @('MCLOS')) -or
            -not (Has-ExactTypes $Stages[1] @('ARH', 'IOG'))) { return $false }
    $Main = $Stages[0]
    $TerminalStage = $Stages[1]
    $MainStart = [int] (Get-Value $Main.activation 'start_tick' 0) + [int] (Get-Value $Main.steering_data 'rigidity_time' 0)
    $MainEnd = [int] (Get-Value $Main.activation 'end_tick' 59)
    $TerminalStart = [int] (Get-Value $TerminalStage.activation 'start_tick' ($MainEnd + 1)) +
        [int] (Get-Value $TerminalStage.steering_data 'rigidity_time' 0)
    $MainRange = [double] (Get-Value $Main.seeker 'range' 512)
    $Range = [double] (Get-Value $TerminalStage.seeker 'range' 512)
    $Fov = [int] (Get-Value $TerminalStage.seeker 'fov' 30)
    $Guidance = [ordered] @{
        guidance_type = 'SACLOS'
        guidance_tick_range = "[[$MainStart,$MainEnd]]"
        guidance_target_distance_range = To-Range $MainRange
        max_guidance_angle = [int] (Get-Value $Main.steering_data 'max_degree_of_missile' 180)
        enable_inertial_guidance = $false
        ignore_chaff = [bool] (Get-Value $TerminalStage.seeker 'ignore_chaff' $false)
        jam_resistance = [double] (Get-Value $TerminalStage.seeker 'jam_resistance' 0)
        terminal_guidance = [pscustomobject] [ordered] @{
            guidance_type = 'ARH'
            guidance_start_tick = $TerminalStart
            guidance_target_distance_range = To-Range $Range
            max_lock_angle = $Fov
            max_guidance_angle = [int] (Get-Value $TerminalStage.steering_data 'max_degree_of_missile' 180)
            scan_interval_tick = [int] (Get-Value $TerminalStage.seeker 'scan_interval_tick' 2)
            predict_target_pos = [bool] (Get-Value $TerminalStage.steering_data 'predict_target_pos' $true)
            active_radar_activation_range = 256
            enable_inertial_guidance = $true
        }
    }
    $Turning = [ordered] @{}
    $MainFactor = Get-Value $Main.steering_data 'turning_factor'
    $TerminalFactor = Get-Value $TerminalStage.steering_data 'turning_factor'
    if ($null -ne $MainFactor) { $Turning["[[$MainStart,$MainEnd]]"] = [double] $MainFactor }
    if ($null -ne $TerminalFactor) { $Turning["[[$TerminalStart,inf]]"] = [double] $TerminalFactor }
    Set-TurningFactors $Weapon $Turning
    Move-RequireLock $Weapon
    $Weapon.guidance_data = [pscustomobject] $Guidance
    return $true
}

function Convert-HitlMouse($Weapon, $Stages, $Hitl) {
    if ([string] (Get-Value $Hitl 'control_mode') -ne 'MOUSE' -or
            $Stages.Count -ne 1 -or -not (Has-ExactTypes $Stages[0] @('MCLOS'))) { return $false }
    $Stage = $Stages[0]
    $Start = [int] (Get-Value $Stage.activation 'start_tick' 0) + [int] (Get-Value $Stage.steering_data 'rigidity_time' 0)
    $Range = [double] (Get-Value $Stage.seeker 'range' 512)
    $Guidance = [ordered] @{
        guidance_type = 'HITL_CLOS_TV'
        guidance_tick_range = "[[$Start,inf]]"
        guidance_target_distance_range = To-Range $Range
        max_guidance_angle = [int] (Get-Value $Stage.steering_data 'max_degree_of_missile' 180)
        enable_inertial_guidance = $false
    }
    Add-Hitl $Guidance $Hitl
    $Turning = [ordered] @{}
    $Factor = Get-Value $Stage.steering_data 'turning_factor'
    if ($null -ne $Factor) { $Turning["[[$Start,inf]]"] = [double] $Factor }
    Set-TurningFactors $Weapon $Turning
    Move-RequireLock $Weapon
    $Weapon.guidance_data = [pscustomobject] $Guidance
    return $true
}

function Convert-HitlDesignate($Weapon, $Stages, $Hitl) {
    if ([string] (Get-Value $Hitl 'control_mode') -ne 'DESIGNATE' -or
            $Stages.Count -ne 1 -or -not (Has-ExactTypes $Stages[0] @('SACLOS', 'IOG'))) { return $false }
    $Stage = $Stages[0]
    $Start = [int] (Get-Value $Stage.activation 'start_tick' 0) + [int] (Get-Value $Stage.steering_data 'rigidity_time' 0)
    $Range = [double] (Get-Value $Stage.seeker 'range' 512)
    $Fov = [int] (Get-Value $Stage.seeker 'fov' 30)
    $Guidance = [ordered] @{
        guidance_type = 'HITL_TV'
        guidance_tick_range = "[[$Start,inf]]"
        guidance_target_distance_range = To-Range $Range
        max_lock_angle = $Fov
        max_guidance_angle = [int] (Get-Value $Stage.steering_data 'max_degree_of_missile' 180)
        enable_inertial_guidance = $true
    }
    Add-Hitl $Guidance $Hitl
    $Turning = [ordered] @{}
    $Factor = Get-Value $Stage.steering_data 'turning_factor'
    if ($null -ne $Factor) { $Turning["[[$Start,inf]]"] = [double] $Factor }
    Set-TurningFactors $Weapon $Turning
    Move-RequireLock $Weapon
    $Weapon.guidance_data = [pscustomobject] $Guidance
    return $true
}

function Convert-HitlView($Weapon, $Stages, $Hitl) {
    if ([string] (Get-Value $Hitl 'control_mode') -ne 'VIEW') { return $false }
    if ($Stages.Count -eq 1 -and (Has-ExactTypes $Stages[0] @('IOG'))) {
        $Stage = $Stages[0]
        $Start = [int] (Get-Value $Stage.activation 'start_tick' 0) + [int] (Get-Value $Stage.steering_data 'rigidity_time' 0)
        $Guidance = [ordered] @{
            guidance_type = 'NONE'
            guidance_tick_range = "[[$Start,inf]]"
            max_guidance_angle = [int] (Get-Value $Stage.steering_data 'max_degree_of_missile' 180)
            enable_inertial_guidance = $true
        }
        Add-Hitl $Guidance $Hitl
        $Turning = [ordered] @{}
        $Factor = Get-Value $Stage.steering_data 'turning_factor'
        if ($null -ne $Factor) { $Turning["[[$Start,inf]]"] = [double] $Factor }
        Set-TurningFactors $Weapon $Turning
        Move-RequireLock $Weapon
        $Weapon.guidance_data = [pscustomobject] $Guidance
        return $true
    }
    if ($Stages.Count -eq 1 -and (Has-ExactTypes $Stages[0] @('GPS', 'IOG'))) {
        $Stage = $Stages[0]
        $Start = [int] (Get-Value $Stage.activation 'start_tick' 0) + [int] (Get-Value $Stage.steering_data 'rigidity_time' 0)
        $Guidance = [ordered] @{
            guidance_type = 'GPS'
            guidance_tick_range = "[[$Start,inf]]"
            max_guidance_angle = [int] (Get-Value $Stage.steering_data 'max_degree_of_missile' 180)
            predict_target_pos = [bool] (Get-Value $Stage.steering_data 'predict_target_pos' $true)
            enable_inertial_guidance = $true
        }
        Add-Hitl $Guidance $Hitl
        $Turning = [ordered] @{}
        $Factor = Get-Value $Stage.steering_data 'turning_factor'
        if ($null -ne $Factor) { $Turning["[[$Start,inf]]"] = [double] $Factor }
        Set-TurningFactors $Weapon $Turning
        Move-RequireLock $Weapon
        $Weapon.guidance_data = [pscustomobject] $Guidance
        return $true
    }
    if ($Stages.Count -eq 2 -and
            (Has-ExactTypes $Stages[0] @('GPS', 'IOG')) -and
            (Has-ExactTypes $Stages[1] @('IR', 'GPS'))) {
        $Main = $Stages[0]
        $TerminalStage = $Stages[1]
        $Range = [double] (Get-Value $TerminalStage.seeker 'range' 512)
        $Fov = [int] (Get-Value $TerminalStage.seeker 'fov' 30)
        $Distance = [double] (Get-Value $TerminalStage.activation 'max_target_distance' 120)
        $Guidance = [ordered] @{
            guidance_type = 'GPS'
            guidance_tick_range = '[[0,inf]]'
            max_guidance_angle = [int] (Get-Value $Main.steering_data 'max_degree_of_missile' 180)
            enable_inertial_guidance = $true
            dircm_resistance = [double] (Get-Value $TerminalStage.seeker 'dircm_resistance' 0)
            terminal_guidance = [pscustomobject] [ordered] @{
                guidance_type = 'IR'
                guidance_start_dist = $Distance
                guidance_target_distance_range = To-Range $Range
                max_lock_angle = $Fov
                max_guidance_angle = [int] (Get-Value $TerminalStage.steering_data 'max_degree_of_missile' 180)
                scan_interval_tick = [int] (Get-Value $TerminalStage.seeker 'scan_interval_tick' 2)
                predict_target_pos = [bool] (Get-Value $TerminalStage.steering_data 'predict_target_pos' $true)
                enable_inertial_guidance = $true
            }
        }
        Add-Hitl $Guidance $Hitl
        $Turning = [ordered] @{}
        $TerminalFactor = Get-Value $TerminalStage.steering_data 'turning_factor'
        if ($null -ne $TerminalFactor) { $Turning['[[0,inf]]'] = [double] $TerminalFactor }
        Set-TurningFactors $Weapon $Turning
        Move-RequireLock $Weapon
        $Weapon.guidance_data = [pscustomobject] $Guidance
        return $true
    }
    return $false
}

$Changed = 0
foreach ($Root in $Roots) {
    foreach ($File in Get-ChildItem -LiteralPath $Root -Filter '*.json') {
        $Text = [System.IO.File]::ReadAllText($File.FullName, [System.Text.Encoding]::UTF8)
        $Weapon = $Text | ConvertFrom-Json
        $Stages = @($Weapon.guidance_data.stages)
        $Hitl = Get-Value $Weapon.guidance_data 'human_in_the_loop'
        $Converted = (Convert-IogIr $Weapon $Stages) -or
            (Convert-SaclosArh $Weapon $Stages) -or
            (Convert-HitlMouse $Weapon $Stages $Hitl) -or
            (Convert-HitlDesignate $Weapon $Stages $Hitl) -or
            (Convert-HitlView $Weapon $Stages $Hitl)
        if ($Converted) {
            $Json = $Weapon | ConvertTo-Json -Depth 100
            [System.IO.File]::WriteAllText($File.FullName, $Json + [Environment]::NewLine, $Utf8NoBom)
            Write-Output "migrated: $($File.FullName)"
            $Changed++
        }
    }
}
Write-Output "done: $Changed file(s)"
