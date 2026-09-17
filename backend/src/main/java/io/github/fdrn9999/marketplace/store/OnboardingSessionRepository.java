package io.github.fdrn9999.marketplace.store;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Repository;

import io.github.fdrn9999.marketplace.domain.OnboardingSession;

@Repository
public class OnboardingSessionRepository {

    private final Map<String, OnboardingSession> sessions = new ConcurrentHashMap<>();

    public OnboardingSession save(OnboardingSession session) {
        sessions.put(session.id(), session);
        return session;
    }

    public Optional<OnboardingSession> findById(String id) {
        return id == null ? Optional.empty() : Optional.ofNullable(sessions.get(id));
    }

    public void clear() {
        sessions.clear();
    }
}
