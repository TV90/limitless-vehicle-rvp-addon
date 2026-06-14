package org.ywzj.rvp.ext;

import java.util.Collection;
import java.util.Set;

public interface RVPEraStateAccess {

    boolean rvp_isEraActive(String boneName);

    boolean rvp$consumeEra(String boneName);

    void rvp$setInactiveEraBones(Collection<String> boneNames);

    Set<String> rvp$getInactiveEraBones();

    boolean rvp$retainEraBones(Collection<String> validBoneNames);
}
