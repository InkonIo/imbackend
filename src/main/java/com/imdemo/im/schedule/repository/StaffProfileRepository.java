// StaffProfileRepository.java
package com.imdemo.im.schedule.repository;

import com.imdemo.im.schedule.domain.StaffProfile;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StaffProfileRepository extends JpaRepository<StaffProfile, Long> {
}