package domi.argenticpptmaster.repository;

import domi.argenticpptmaster.domain.PptJob;
import java.util.Optional;
import java.util.UUID;

public interface PptJobRepository {

    PptJob save(PptJob job);

    Optional<PptJob> findById(UUID id);
}
