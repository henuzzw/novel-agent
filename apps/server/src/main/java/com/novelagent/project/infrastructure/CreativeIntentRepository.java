package com.novelagent.project.infrastructure;

import com.novelagent.project.domain.CreativeIntent;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 创作意图存储。
 *
 * <p>以项目 ID 为主键保存创作意图，继承 JPA 基础读写能力。意图与项目各有行版本，生成规划使用的输入快照不能与后续编辑后的意图混为一谈。</p>
 */
public interface CreativeIntentRepository extends JpaRepository<CreativeIntent, UUID> {
}
