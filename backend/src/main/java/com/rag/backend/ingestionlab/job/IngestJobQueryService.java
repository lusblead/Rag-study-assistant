package com.rag.backend.ingestionlab.job;

import com.rag.backend.common.BizException;
import org.springframework.stereotype.Service;

@Service
public class IngestJobQueryService {
    private final IngestJobMapper ingestJobMapper;

    public IngestJobQueryService(IngestJobMapper ingestJobMapper) {
        this.ingestJobMapper = ingestJobMapper;
    }

    public IngestJobQueryResponse selectById(String id) {
        IngestJob job = ingestJobMapper.selectById(id);
        if(job==null){
            throw new BizException(404,"Job not found : "+id);
        }


        IngestJobQueryResponse response = new IngestJobQueryResponse(
                job.getJobId(),
                job.getDocumentId(),
                job.getDocumentVersionId(),
                job.getJobType(),
                job.getState(),
                job.getAttempt(),
                job.getMaxAttempts(),
                job.getErrorCode()

        );
        return response;
    }
}
