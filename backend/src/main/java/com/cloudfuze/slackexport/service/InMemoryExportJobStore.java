package com.cloudfuze.slackexport.service;

import com.cloudfuze.slackexport.api.ExportJob;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class InMemoryExportJobStore implements ExportJobStore {

    private final Map<String, ExportJob> jobs = new ConcurrentHashMap<>();

    @Override
    public void save(ExportJob job) {
        if (job != null && job.getJobId() != null) {
            jobs.put(job.getJobId(), job);
        }
    }

    @Override
    public Optional<ExportJob> findById(String jobId) {
        return Optional.ofNullable(jobId).map(jobs::get);
    }
}
