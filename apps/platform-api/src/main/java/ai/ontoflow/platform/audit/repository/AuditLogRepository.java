package ai.ontoflow.platform.audit.repository;

import ai.ontoflow.platform.audit.domain.AuditLog;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {
    List<AuditLog> findByTenantIdOrderByCreatedAtDescIdAsc(UUID tenantId, Pageable pageable);

    long countByTenantId(UUID tenantId);
}
