package com.portfolio.creatorlab.repository;

import com.portfolio.creatorlab.domain.Member;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MemberRepository extends JpaRepository<Member, Long> {
}
