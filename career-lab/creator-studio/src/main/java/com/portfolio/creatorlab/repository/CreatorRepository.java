package com.portfolio.creatorlab.repository;

import com.portfolio.creatorlab.domain.Creator;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CreatorRepository extends JpaRepository<Creator, Long> {

    Optional<Creator> findByMemberId(Long memberId);

    boolean existsByMemberId(Long memberId);
}
