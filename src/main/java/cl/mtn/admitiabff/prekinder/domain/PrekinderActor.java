package cl.mtn.admitiabff.prekinder.domain;

import java.util.UUID;

public record PrekinderActor(UUID id, long legacyUserId, String role) {}
