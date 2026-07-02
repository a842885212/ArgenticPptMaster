package domi.argenticpptmaster.repository;

import domi.argenticpptmaster.domain.PptJob;
import java.util.Optional;
import java.util.UUID;

/**
 * PPT 任务仓储接口。
 * <p>
 * 定义任务对象的持久化契约，当前仅支持保存和按 ID 查询。
 * 可通过 {@link org.springframework.stereotype.Repository} 注解的实现类提供不同存储后端。
 * </p>
 */
public interface PptJobRepository {

    PptJob save(PptJob job);

    Optional<PptJob> findById(UUID id);
}
