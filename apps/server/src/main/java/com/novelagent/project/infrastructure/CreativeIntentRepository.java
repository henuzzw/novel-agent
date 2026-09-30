package com.novelagent.project.infrastructure;

import com.novelagent.project.domain.CreativeIntent;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CreativeIntentRepository extends JpaRepository<CreativeIntent, UUID> {
}

