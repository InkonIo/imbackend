package com.imdemo.im.repo;

import com.imdemo.im.domain.ChecklistPhoto;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ChecklistPhotoRepository extends JpaRepository<ChecklistPhoto, Long> {
    Optional<ChecklistPhoto> findFirstBySha256AndIdNotOrderByIdAsc(String sha256, Long id);
}