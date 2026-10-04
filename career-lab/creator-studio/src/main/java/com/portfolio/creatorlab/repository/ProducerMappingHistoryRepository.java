package com.portfolio.creatorlab.repository;

import com.portfolio.creatorlab.domain.ProducerMappingHistory;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProducerMappingHistoryRepository extends JpaRepository<ProducerMappingHistory, Long> {

    List<ProducerMappingHistory> findByCreatorId(Long creatorId);
}
