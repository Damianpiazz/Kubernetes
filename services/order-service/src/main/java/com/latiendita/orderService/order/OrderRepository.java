package com.latiendita.orderService.order;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRepository extends JpaRepository<OrderEntity, Long> {

    @EntityGraph(attributePaths = "items")
    Optional<OrderEntity> findWithItemsById(Long id);

    @EntityGraph(attributePaths = "items")
    Page<OrderEntity> findByCustomerId(Long customerId, Pageable pageable);
}
