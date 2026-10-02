package com.portfolio.equitylab.repository;

import com.portfolio.equitylab.domain.EquityShare;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EquityShareRepository extends JpaRepository<EquityShare, Long> {
    long countByDistributorCode(String distributorCode);
}
