package ai.ontoflow.platform.audit.service;

import ai.ontoflow.platform.audit.domain.AuditLog;
import ai.ontoflow.platform.audit.repository.AuditLogRepository;

import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
@Transactional(propagation = Propagation.MANDATORY)
public class AuditEventService {
    private final AuditLogRepository audits;

    public AuditEventService(AuditLogRepository audits) {
        this.audits = audits;
    }

    public void record(UUID tenantId, UUID userId, String action, UUID targetId) {
        audits.save(
                new AuditLog(
                        UUID.randomUUID(),
                        tenantId,
                        userId,
                        action,
                        targetId,
                        MDC.get("traceId"),
                        Instant.now()));
    }
}
