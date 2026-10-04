package com.portfolio.creatorlab.repository;

import com.portfolio.creatorlab.domain.Program;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProgramRepository extends JpaRepository<Program, Long> {

    boolean existsByProducerId(Long producerId);

    List<Program> findByProducerId(Long producerId);
}
