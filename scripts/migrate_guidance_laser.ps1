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

function Convert-LegacyLaserWeapon($Weapon) {
    $Stages = @($Weapon.guidance_data.stages)
    $SingleStage = $Stages.Count -eq 1 -and (Has-ExactTypes $Stages[0] @('SACLOS', 'IOG'))
    $TwoStage = $Stages.Count -eq 2 -and
        (Has-ExactTypes $Stages[0] @('IOG')) -and
        (Has-ExactTypes $Stages[1] @('SACLOS'))
    if (-not $SingleStage -and -not $TwoStage) {
        return $false
    }
    $LegacyHitl = Get-Value $Weapon.guidance_data 'human_in_the_loop'
    if ($null -ne $LegacyHitl -and [bool] (Get-Value $LegacyHitl 'enabled' $false)) {
        return $false
    }

    $LaserStage = if ($SingleStage) { $Stages[0] } else { $Stages[1] }
    $Activation = $LaserStage.activation
    $StartTick = [int] (Get-Value $Activation 'start_tick' 0) +
        [int] (Get-Value $LaserStage.steering_data 'rigidity_time' 0)
    $EndTick = Get-Value $Activation 'end_tick'
    if ($null -ne $EndTick -and $StartTick -gt [int] $EndTick) {
        throw "guidance rigidity exceeds laser stage window: $StartTick > $EndTick"
    }
    $EndText = if ($null -ne $EndTick) { [int] $EndTick } else { 'inf' }

    $Guidance = [ordered] @{
        guidance_type = 'SALH'
        guidance_tick_range = "[[$StartTick,$EndText]]"
        max_guidance_angle = [int] (Get-Value $LaserStage.steering_data 'max_degree_of_missile' 180)
        predict_target_pos = [bool] (Get-Value $LaserStage.steering_data 'predict_target_pos' $true)
        enable_inertial_guidance = $true
    }
    $MaxDistance = Get-Value $Activation 'max_target_distance'
    if ($null -ne $MaxDistance -and [double] $MaxDistance -gt 0) {
        $Guidance['guidance_target_distance_range'] = "[[0,$MaxDistance]]"
    }
    foreach ($Name in @('ignore_flares', 'ignore_chaff', 'dircm_resistance', 'jam_resistance', 'home_on_jam', 'decoy_filter')) {
        $Value = Get-Value $LaserStage.seeker $Name
        if ($null -ne $Value) {
            $Guidance[$Name] = $Value
        }
    }

    $TurningFactor = Get-Value $LaserStage.steering_data 'turning_factor'
    if ($null -ne $TurningFactor) {
        if ($null -ne (Get-Value $Weapon.projectile_data 'turning_factor')) {
            throw 'projectile_data.turning_factor already exists'
        }
        $Ranges = [ordered] @{ "[[$StartTick,$EndText]]" = [double] $TurningFactor }
        $Weapon.projectile_data | Add-Member -NotePropertyName 'turning_factor' -NotePropertyValue ([pscustomobject] $Ranges)
    }

    Move-RequireLock $Weapon
    $Weapon.guidance_data = [pscustomobject] $Guidance
    return $true
}

$Changed = 0
foreach ($Root in $Roots) {
    foreach ($File in Get-ChildItem -LiteralPath $Root -Filter '*.json') {
        $Text = [System.IO.File]::ReadAllText($File.FullName, [System.Text.Encoding]::UTF8)
        $Weapon = $Text | ConvertFrom-Json
        if (Convert-LegacyLaserWeapon $Weapon) {
            $Json = $Weapon | ConvertTo-Json -Depth 100
            [System.IO.File]::WriteAllText($File.FullName, $Json + [Environment]::NewLine, $Utf8NoBom)
            Write-Output "migrated: $($File.FullName)"
            $Changed++
        }
    }
}
Write-Output "done: $Changed file(s)"
