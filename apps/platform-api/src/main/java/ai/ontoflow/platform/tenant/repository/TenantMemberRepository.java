package ai.ontoflow.platform.tenant.repository;

import ai.ontoflow.platform.tenant.domain.Role;
import ai.ontoflow.platform.tenant.domain.TenantMember;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TenantMemberRepository extends JpaRepository<TenantMember, UUID> {
    Optional<TenantMember> findByTenantIdAndUserId(UUID tenantId, UUID userId);

    List<TenantMember> findByUserIdOrderByCreatedAtAscIdAsc(UUID userId, Pageable pageable);

    List<TenantMember> findByTenantIdOrderByCreatedAtAscIdAsc(UUID tenantId, Pageable pageable);

    long countByUserId(UUID userId);

    long countByTenantId(UUID tenantId);

    long countByTenantIdAndRole(UUID tenantId, Role role);
}
