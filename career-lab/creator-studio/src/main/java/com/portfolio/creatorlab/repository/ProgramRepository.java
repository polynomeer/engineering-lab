package com.portfolio.creatorlab.repository;

import com.portfolio.creatorlab.domain.Program;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProgramRepository extends JpaRepository<Program, Long> {

    /** NEW-DESIGN: transfer only the selected creator's programs in the caller transaction. */
    @org.springframework.data.jpa.repository.Modifying(flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query("UPDATE Program p SET p.producerId = :target WHERE p.producerId = :source AND p.createdByCreatorId = :creator")
    int transferCreatorPrograms(@org.springframework.data.repository.query.Param("source") Long source,
                               @org.springframework.data.repository.query.Param("target") Long target,
                               @org.springframework.data.repository.query.Param("creator") Long creator);

    boolean existsByProducerId(Long producerId);

    List<Program> findByProducerId(Long producerId);
}
