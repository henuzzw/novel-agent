package com.novelagent.project.application;

import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class CurrentActorProvider {

    private final UUID devUserId;

    public CurrentActorProvider(@Value("${app.security.dev-user-id}") UUID devUserId) {
        this.devUserId = devUserId;
    }

    public UUID currentUserId() {
        return devUserId;
    }
}

