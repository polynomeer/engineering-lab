package com.portfolio.creatorlab.repository;

import com.portfolio.creatorlab.domain.Producer;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProducerRepository extends JpaRepository<Producer, Long> {
}
