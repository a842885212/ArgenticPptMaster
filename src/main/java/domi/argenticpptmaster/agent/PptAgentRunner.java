package domi.argenticpptmaster.agent;

import domi.argenticpptmaster.domain.PptConfirmation;
import domi.argenticpptmaster.domain.PptJob;

public interface PptAgentRunner {

    void start(PptJob job);

    void resume(PptJob job, PptConfirmation confirmation);
}
