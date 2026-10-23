package com.js.motos.clientApp.qr.repo;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.js.motos.clientApp.qr.domain.QrBatch;

public interface QrBatchRepository extends JpaRepository<QrBatch, UUID> {

    Page<QrBatch> findAllByOrderByCreatedAtDesc(Pageable pageable);
}