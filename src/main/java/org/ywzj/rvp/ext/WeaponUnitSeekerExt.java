package org.ywzj.rvp.ext;

/**
 * Client-side seeker power-off when {@code WeaponUnit#toggleSeeker} refuses non-seeker weapons.
 */
public interface WeaponUnitSeekerExt {

    void ywzj_rvp$forceSeekerOff();

    /** Power seeker on without clearing lock progress (unlike {@code toggleSeeker(true)}). */
    void ywzj_rvp$ensureSeekerOn();
}
