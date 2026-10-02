package com.portfolio.mdslab.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * [FACT 기반 재현] "정산 중 메타데이터 수정 차단" 시나리오의 가드 대상 엔티티.
 * 실제 FLO MDS의 앨범 엔티티는 계약·UPC·상태 전이 등 훨씬 많은 필드를 가지지만,
 * 이 랩은 "정산 중 title(메타데이터) 수정이 막히는가"만 시연하면 되므로 축소했다.
 */
@Entity
@Table(name = "album")
@Getter
@NoArgsConstructor
public class Album {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;

    @Column(name = "release_date")
    private LocalDate releaseDate;

    public Album(String title) {
        this.title = title;
    }

    public Album(String title, LocalDate releaseDate) {
        this.title = title;
        this.releaseDate = releaseDate;
    }

    /** 정산 가드가 걸리는 메타데이터 수정 동작. */
    public void updateTitle(String newTitle) {
        this.title = newTitle;
    }
}
