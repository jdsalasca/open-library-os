package com.openlibrary.inventory;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CopyMoveRepository extends JpaRepository<CopyMove, Long> {

    List<CopyMove> findByCopyIdOrderByMovedAtDesc(Long copyId);
}