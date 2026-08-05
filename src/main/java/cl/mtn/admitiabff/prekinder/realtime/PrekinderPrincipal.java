package cl.mtn.admitiabff.prekinder.realtime;

import java.security.Principal;

public record PrekinderPrincipal(String name) implements Principal {
    @Override public String getName() { return name; }
}
