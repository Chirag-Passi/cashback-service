package com.js.motos.clientApp.user.repo;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.js.motos.clientApp.user.domain.User;
import com.js.motos.clientApp.user.domain.UserRole;
import com.js.motos.clientApp.user.domain.UserStatus;

public interface UserRepository extends JpaRepository<User, UUID> {

    @Query("SELECT u FROM User u WHERE lower(u.email) = lower(:email)")
    Optional<User> findByEmailIgnoreCase(@Param("email") String email);

    Optional<User> findByGoogleSubject(String googleSubject);

    List<User> findAllByRole(UserRole role);

    @Query("""
            SELECT u FROM User u
            WHERE (:status IS NULL OR u.status = :status)
              AND (:q IS NULL OR lower(u.email) LIKE lower(concat('%', :q, '%'))
                             OR lower(u.fullName) LIKE lower(concat('%', :q, '%'))
                             OR u.phone LIKE concat('%', :q, '%'))
            """)
    Page<User> search(@Param("q") String query,
                      @Param("status") UserStatus status,
                      Pageable pageable);

    boolean existsByRole(UserRole role);
}