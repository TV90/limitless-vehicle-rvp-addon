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

function Resolve-Seeker($Stage) {
    $Seeker = Get-Value $Stage 'seeker'
    if ($null -eq $Seeker) {
        $Seeker = Get-Value $Stage 'seeker_data'
    }
    return $Seeker
}

function Resolve-ActivationValue($Stage, [string] $Name, [string] $LegacyName, $Default = $null) {
    $Value = Get-Value $Stage.activation $Name
    if ($null -eq $Value) {
        $Value = Get-Value $Stage $LegacyName $Default
    }
    return $Value
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

function Move-CruiseAndSpread($Weapon, $Guidance) {
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
    $CancelDistance = Get-Value $Projectile 'gps_guidance_cancel_distance'
    if ($null -ne $CancelDistance -and [double] $CancelDistance -gt 0) {
        $Guidance['guidance_target_distance_range'] = "[[$CancelDistance,inf]]"
    }
    foreach ($Name in @(
        'gps_cep',
        'gps_cruise_start_tick',
        'gps_cruise_terminal_cylinder_radius',
        'gps_cruise_gravity_scale',
        'gps_cruise_leveling_factor',
        'gps_guidance_cancel_distance'
    )) {
        $Projectile.PSObject.Properties.Remove($Name)
    }
}

function Convert-GpsIrWeapon($Weapon) {
    $Stages = @($Weapon.guidance_data.stages)
    if ($Stages.Count -ne 2 -or
            -not (Has-ExactTypes $Stages[0] @('GPS', 'IOG')) -or
            -not (Has-ExactTypes $Stages[1] @('IR', 'GPS', 'IOG'))) {
        return $false
    }
    $LegacyHitl = Get-Value $Weapon.guidance_data 'human_in_the_loop'
    if ($null -ne $LegacyHitl -and [bool] (Get-Value $LegacyHitl 'enabled' $false)) {
        return $false
    }

    $Main = $Stages[0]
    $TerminalStage = $Stages[1]
    $MainTurning = Get-Value $Main.steering_data 'turning_factor'
    $TerminalTurning = Get-Value $TerminalStage.steering_data 'turning_factor'
    if ($null -ne $MainTurning -or $null -ne $TerminalTurning) {
        if ($null -eq $MainTurning -or $null -eq $TerminalTurning -or
                [Math]::Abs([double] $MainTurning - [double] $TerminalTurning) -gt 0.000001) {
            return $false
        }
    }

    $MainStart = [int] (Resolve-ActivationValue $Main 'start_tick' 'start_tick' 0) +
        [int] (Get-Value $Main.steering_data 'rigidity_time' 0)
    $MainEnd = Resolve-ActivationValue $Main 'end_tick' 'end_tick'
    if ($null -ne $MainEnd -and $MainStart -gt [int] $MainEnd) {
        throw "guidance rigidity exceeds main stage window: $MainStart > $MainEnd"
    }
    $MainEndText = if ($null -ne $MainEnd) { [int] $MainEnd } else { 'inf' }

    $Seeker = Resolve-Seeker $TerminalStage
    $Range = [double] (Get-Value $Seeker 'range' 512)
    $Fov = [int] (Get-Value $Seeker 'fov' 30)
    $TerminalStart = [int] (Resolve-ActivationValue $TerminalStage 'start_tick' 'start_tick' 0) +
        [int] (Get-Value $TerminalStage.steering_data 'rigidity_time' 0)
    $TerminalDistance = Resolve-ActivationValue $TerminalStage 'max_target_distance' 'max_distance'
    if ($null -eq $TerminalDistance) {
        throw 'GPS to IR terminal stage requires max target distance'
    }

    $Guidance = [ordered] @{
        guidance_type = 'GPS'
        guidance_tick_range = "[[$MainStart,$MainEndText]]"
        max_guidance_angle = [int] (Get-Value $Main.steering_data 'max_degree_of_missile' 180)
        predict_target_pos = [bool] (Get-Value $Main.steering_data 'predict_target_pos' $true)
        enable_inertial_guidance = $true
    }
    Move-CruiseAndSpread $Weapon $Guidance

    $Terminal = [ordered] @{
        guidance_type = 'IR'
        guidance_start_tick = $TerminalStart
        guidance_start_dist = [double] $TerminalDistance
        guidance_target_distance_range = "[[0,$Range]]"
        max_lock_angle = $Fov
        max_guidance_angle = [int] (Get-Value $TerminalStage.steering_data 'max_degree_of_missile' 180)
        scan_interval_tick = [int] (Get-Value $Seeker 'scan_interval_tick' 2)
        predict_target_pos = [bool] (Get-Value $TerminalStage.steering_data 'predict_target_pos' $true)
        enable_inertial_guidance = $true
    }
    $Guidance['terminal_guidance'] = [pscustomobject] $Terminal
    foreach ($Name in @('ignore_flares', 'ignore_chaff', 'dircm_resistance', 'jam_resistance', 'home_on_jam', 'decoy_filter')) {
        $Value = Get-Value $Seeker $Name
        if ($null -ne $Value) {
            $Guidance[$Name] = $Value
        }
    }

    if ($null -ne $MainTurning) {
        if ($null -ne (Get-Value $Weapon.projectile_data 'turning_factor')) {
            throw 'projectile_data.turning_factor already exists'
        }
        $Ranges = [ordered] @{ "[[$MainStart,inf]]" = [double] $MainTurning }
        $Weapon.projectile_data | Add-Member -NotePropertyName 'turning_factor' -NotePropertyValue ([pscustomobject] $Ranges)
    }

    Move-RequireLock $Weapon
    $Weapon.PSObject.Properties.Remove('seeker_data')
    $Weapon.guidance_data = [pscustomobject] $Guidance
    return $true
}

$Changed = 0
foreach ($Root in $Roots) {
    foreach ($File in Get-ChildItem -LiteralPath $Root -Filter '*.json') {
        $Text = [System.IO.File]::ReadAllText($File.FullName, [System.Text.Encoding]::UTF8)
        $Weapon = $Text | ConvertFrom-Json
        if (Convert-GpsIrWeapon $Weapon) {
            $Json = $Weapon | ConvertTo-Json -Depth 100
            [System.IO.File]::WriteAllText($File.FullName, $Json + [Environment]::NewLine, $Utf8NoBom)
            Write-Output "migrated: $($File.FullName)"
            $Changed++
        }
    }
}
Write-Output "done: $Changed file(s)"
