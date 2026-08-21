package dev.sorokin.eventnotificator.repository;

import dev.sorokin.eventnotificator.model.entity.NotificationEntity;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.LocalDateTime;
import java.util.List;

public interface NotificationRepository extends JpaRepository<NotificationEntity, Long> {

    @EntityGraph(attributePaths = {"payload"})
    @Query("""
        SELECT n FROM NotificationEntity n
        WHERE n.userId = :userId AND n.isRead = false
        ORDER BY n.createdAt DESC
    """)
    List<NotificationEntity> findNotificationsByUserId(@Param("userId") Long userId);

    @Modifying
    @Query("""
    UPDATE NotificationEntity n
    SET n.isRead = true, n.readAt = :readAt
    WHERE n.userId = :userId
        AND n.notificationId IN :notificationIds
        AND n.isRead = false
    """)
    int markAsReadByUserIdAndIds(
            @Param("notificationIds") List<Long> notificationIds,
            @Param("userId") Long userId,
            @Param("readAt") LocalDateTime readAt
    );

    @Query("""
        SELECT COUNT(n) FROM NotificationEntity n
        WHERE n.userId = :userId AND n.isRead = false
    """)
    long countByUserIdAndReadFalse(@Param("userId") Long userId);

}