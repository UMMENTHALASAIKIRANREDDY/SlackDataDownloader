package com.cloudfuze.slackexport.service;

import com.cloudfuze.slackexport.api.ExportJob;

import java.util.Optional;

/** In-memory store for export jobs. */
public interface ExportJobStore {
    void save(ExportJob job);
    Optional<ExportJob> findById(String jobId);
}
