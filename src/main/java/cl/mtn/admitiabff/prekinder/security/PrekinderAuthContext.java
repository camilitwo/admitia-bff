package cl.mtn.admitiabff.prekinder.security;

import cl.mtn.admitiabff.prekinder.domain.PrekinderActor;

public final class PrekinderAuthContext {
    private static final ThreadLocal<Principal> CURRENT = new ThreadLocal<>();
    private PrekinderAuthContext() {}
    public static void set(Principal principal) { CURRENT.set(principal); }
    public static Principal get() { return CURRENT.get(); }
    public static void clear() { CURRENT.remove(); }

    public record Principal(PrekinderActor actor, String subject, String email, String sessionId) {}
}
