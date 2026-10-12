package com.latiendita.orderService.outbox;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    @Query(value = """
            SELECT * FROM outbox_events
            WHERE status IN ('PENDING', 'FAILED') AND attempts < :maxAttempts
            ORDER BY id
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> findNextBatch(@Param("limit") int limit,
                                    @Param("maxAttempts") int maxAttempts);
}
