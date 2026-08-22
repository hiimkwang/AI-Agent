package com.ai.aiagent.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public final class CurrentScope {

    private CurrentScope() {
    }

    public static AccessScope get() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a != null && a.getPrincipal() instanceof AccessScope scope) {
            return scope;
        }
        return AccessScope.internal();
    }
}
