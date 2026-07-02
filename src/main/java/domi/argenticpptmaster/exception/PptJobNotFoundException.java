package domi.argenticpptmaster.exception;

import java.util.UUID;

public class PptJobNotFoundException extends RuntimeException {

    public PptJobNotFoundException(UUID jobId) {
        super("PPT job not found: " + jobId);
    }
}
