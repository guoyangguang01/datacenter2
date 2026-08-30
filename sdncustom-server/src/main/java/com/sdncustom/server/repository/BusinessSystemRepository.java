package com.sdncustom.server.repository;

import com.sdncustom.common.model.BusinessSystem;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BusinessSystemRepository extends JpaRepository<BusinessSystem, String> {
}
