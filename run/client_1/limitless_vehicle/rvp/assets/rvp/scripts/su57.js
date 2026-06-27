const kh38 = ["$weapon0", "$weapon1"]
const kh58 = ["$weapon2", "$weapon3"]
const forceBayOpen = false

function getWeaponUnit(context) {
    try {
        const local = Packages.org.ywzj.vehicle.vehicle.LocalVehiclePlayer.instance
        const localVehicle = local != null ? local.getVehicle() : null
        const entity = context.getEntity()
        if (localVehicle != null && entity != null && localVehicle.getId() == entity.getId()) {
            const unit = local.getWeaponUnit()
            if (unit != null) {
                return unit
            }
        }
    } catch (e) {
    }
    try {
        const part = context.getEntity().getPartUnit("sighting_system")
        if (part.isPresent()) {
            return part.get()
        }
    } catch (e) {
    }
    return null
}

function getCurrentWeaponIndex(context) {
    try {
        const unit = getWeaponUnit(context)
        if (unit != null) {
            return unit.getRootParentWeaponUnit().getCurrentWeaponIndex()
        }
    } catch (e) {
    }
    return -1
}

function getCurrentWeaponInfo(context) {
    try {
        const unit = getWeaponUnit(context)
        if (unit != null) {
            const rootUnit = unit.getRootParentWeaponUnit()
            let info = ""
            try {
                info += String(unit.getId())
            } catch (e) {
            }
            try {
                info += "|" + String(rootUnit.getId())
            } catch (e) {
            }
            const weapon = rootUnit.getCurrentWeapon()
            if (weapon.isPresent()) {
                const current = weapon.get()
                try {
                    info += String(current.getData().getWeaponId())
                } catch (e) {
                }
                try {
                    info += "|" + String(current.getData().getName())
                } catch (e) {
                }
                try {
                    info += "|" + String(current.getDisplayName().getString())
                } catch (e) {
                }
                try {
                    info += "|" + String(current.getWeaponUnit().getId())
                } catch (e) {
                }
                return info.toLowerCase()
            }
            return info.toLowerCase()
        }
    } catch (e) {
    }
    return ""
}

function isWeapon(info, currentWeaponIndex, index, idPart, displayPart) {
    return currentWeaponIndex == index
        || info.indexOf(idPart) >= 0
        || info.indexOf(displayPart) >= 0
}

function approach(current, target, step) {
    if (current < target) {
        return Math.min(target, current + step)
    }
    if (current > target) {
        return Math.max(target, current - step)
    }
    return current
}

function getFloatOr(context, name, fallback) {
    const value = context.getFloat(name, fallback)
    return typeof value === "number" && !isNaN(value) ? value : fallback
}

function updateTimedValue(context, name, target, stepPerTick) {
    let value = getFloatOr(context, name, 0)
    value = approach(value, target, stepPerTick)
    context.setFloat(name, value)
    return value
}

function updateBones(context) {
    const pitchInput = context.getPitchInput()
    const yawInput = context.getYawInput()
    const rollInput = context.getRollInput()
    const builder = createPoseBuilder();

    builder.setRotation("ctrl1", -8 * pitchInput, 0, 8 * rollInput);

    builder.setRotation("tlw", 0, -yawInput * 16, 0);
    builder.setRotation("trw", 0, -yawInput * 16, 0);

    builder.setRotation("tlw2", pitchInput * 16 - rollInput * 8, 0, 0);
    builder.setRotation("trw2", pitchInput * 16 + rollInput * 8, 0, 0);

    builder.setRotation("$tlw3", -pitchInput * 10, 0, 0);
    builder.setRotation("$trw3", -pitchInput * 10, 0, 0);

    builder.setRotation("$tlw4", -rollInput * 16, 0, 0);
    builder.setRotation("$trw4", rollInput * 16, 0, 0);

    builder.setRotation("$tlw5", pitchInput * 10, 0, 0);
    builder.setRotation("$trw5", pitchInput * 10, 0, 0);

    const remainKh38 = Math.max(0, Math.min(kh38.length, context.getWeaponRemainAmmo("sighting_system", 1)))
    for (let i = 0; i < kh38.length; i++) {
        if (i < kh38.length - remainKh38) {
            builder.hideBone(kh38[i])
        }
    }

    const remainKh58 = Math.max(0, Math.min(kh58.length, context.getWeaponRemainAmmo("sighting_system", 2)))
    for (let i = 0; i < kh58.length; i++) {
        if (i < kh58.length - remainKh58) {
            builder.hideBone(kh58[i])
        }
    }

    return builder;
}
