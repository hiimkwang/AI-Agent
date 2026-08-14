package com.ai.aiagent.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** Lay {@link AccessScope} cua request dang xu ly. */
public final class CurrentScope {

    private CurrentScope() {
    }

    /** @return pham vi truy cap, hoac {@link AccessScope#internal()} khi goi tu tac vu noi bo. */
    public static AccessScope get() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a != null && a.getPrincipal() instanceof AccessScope scope) {
            return scope;
        }
        return AccessScope.internal();
    }
}
