package com.portfolio.mdslab.repository;

import com.portfolio.mdslab.domain.Album;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AlbumRepository extends JpaRepository<Album, Long> {
    List<Album> findByTitle(String title);
}
