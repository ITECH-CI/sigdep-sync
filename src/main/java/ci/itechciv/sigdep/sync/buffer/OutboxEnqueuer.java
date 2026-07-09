package ci.itechciv.sigdep.sync.buffer;

import ci.itechciv.sigdep.sync.extractor.CanonicalRecord;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Serializes a CanonicalRecord payload to JSON and inserts a row into the
 * SQLite outbox. Each record is one outbox row (one DB transaction); we
 * deliberately don't batch the inserts here because the buffer is local
 * SQLite and inserts are cheap.
 */
@Component
public class OutboxEnqueuer {

    private static final Logger log = LoggerFactory.getLogger(OutboxEnqueuer.class);

    private final OutboxRepository outbox;
    private final ObjectMapper mapper;

    public OutboxEnqueuer(OutboxRepository outbox, ObjectMapper mapper) {
        this.outbox = outbox;
        this.mapper = mapper;
    }

    public int enqueue(List<CanonicalRecord> records) {
        int n = 0;
        for (CanonicalRecord r : records) {
            try {
                String payload = mapper.writeValueAsString(r.payload());
                outbox.enqueue(r.entityType(), r.sourceUuid(), r.watermark(), r.sourceId(), payload);
                n++;
            } catch (JsonProcessingException e) {
                log.warn("Skipping record {} ({}) — JSON serialization failed: {}",
                        r.sourceUuid(), r.entityType(), e.getMessage());
            }
        }
        return n;
    }
}
