package com.ramsbaby.preply.component;

import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class ScheduledIngestionJobTest {

    @Test
    void ingestDaily_calls_ingest_7_days() {
        MailIngestionService svc = Mockito.mock(MailIngestionService.class);
        ScheduledIngestionJob job = new ScheduledIngestionJob(svc);

        job.ingestDaily();

        verify(svc).ingest(7);
    }
}
