package domi.argenticpptmaster.repository;

import domi.argenticpptmaster.domain.PptJob;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Repository;

/**
 * 基于 {@link ConcurrentHashMap} 的内存 PPT 任务仓储实现。
 * <p>
 * 适用于开发/测试环境，或单实例部署场景。
 * 任务数据在应用重启后会丢失，生产环境应考虑替换为数据库实现。
 * </p>
 */
@Repository
public class InMemoryPptJobRepository implements PptJobRepository {

    private final ConcurrentMap<UUID, PptJob> jobs = new ConcurrentHashMap<>();

    @Override
    public PptJob save(PptJob job) {
        jobs.put(job.id(), job);
        return job;
    }

    @Override
    public Optional<PptJob> findById(UUID id) {
        return Optional.ofNullable(jobs.get(id));
    }
}
