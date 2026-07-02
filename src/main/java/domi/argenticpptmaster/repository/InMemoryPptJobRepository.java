package domi.argenticpptmaster.repository;

import domi.argenticpptmaster.domain.PptJob;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Repository;

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
