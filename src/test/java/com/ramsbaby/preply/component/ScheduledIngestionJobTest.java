package com.ramsbaby.preply.component;

import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class ScheduledIngestionJobTest {

    @Test
    void ingestWeekly_calls_ingest_180_days() {
        MailIngestionService svc = Mockito.mock(MailIngestionService.class);
        ScheduledIngestionJob job = new ScheduledIngestionJob(svc);

        job.ingestWeekly();

        verify(svc).ingest(180);
    }
}


