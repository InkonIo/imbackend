package com.imdemo.im.repo;

import com.imdemo.im.domain.AuditEvent;
import com.imdemo.im.domain.AuditEventType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;

public interface AuditEventRepository extends JpaRepository<AuditEvent, Long>, JpaSpecificationExecutor<AuditEvent> {
    Optional<AuditEvent> findFirstByShiftIdAndTypeOrderByIdAsc(Long shiftId, AuditEventType type);
}