package com.openlibrary.inventory;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface LocationRepository extends JpaRepository<Location, Long> {

    Optional<Location> findByCode(String code);

    boolean existsByCode(String code);

    long countByParentId(Long parentId);

    List<Location> findByParentIdOrderBySortOrderAscCodeAsc(Long parentId);
}